package app.unpaused.data.library

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.util.Log
import androidx.core.content.ContextCompat
import app.unpaused.core.audio.AudioQuality
import app.unpaused.data.db.AlbumRow
import app.unpaused.data.db.AppDatabase
import app.unpaused.data.db.ArtistRow
import app.unpaused.data.db.FolderEntity
import app.unpaused.data.db.GroupRow
import app.unpaused.data.db.LyricsSidecarEntity
import app.unpaused.data.db.TrackEntity
import app.unpaused.data.db.TrackSource
import app.unpaused.data.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

sealed interface ScanState {
    data object Idle : ScanState
    data class Running(val phase: Phase, val done: Int = 0, val total: Int = 0) : ScanState
    data class Finished(val atMs: Long, val tracks: Int, val added: Int, val removed: Int, val notes: List<String>) : ScanState

    enum class Phase { MEDIA_STORE, FOLDERS, DETAILS, ARTWORK }
}

/**
 * The on-device library. Two sources, both read-only:
 *  - MediaStore (needs READ_MEDIA_AUDIO / READ_EXTERNAL_STORAGE), fast and indexed by Android;
 *  - folders the user picked with the system file picker (SAF), for files MediaStore skips and for .lrc sidecars.
 * Nothing here leaves the device.
 */
class LibraryRepository(
    private val context: Context,
    private val db: AppDatabase,
    private val settings: SettingsRepository,
    private val enricher: MetadataEnricher,
    private val artwork: ArtworkCache,
) {
    private val dao = db.tracks()
    private val scanMutex = Mutex()
    private val _scan = MutableStateFlow<ScanState>(ScanState.Idle)
    val scanState: StateFlow<ScanState> = _scan.asStateFlow()

    // ---- Queries -------------------------------------------------------------------------------

    val count: Flow<Int> = dao.observeCount()
    fun tracks(): Flow<List<TrackEntity>> = dao.observeAll()
    fun albums(): Flow<List<AlbumRow>> = dao.observeAlbums()
    fun recentAlbums(limit: Int = 12): Flow<List<AlbumRow>> = dao.observeRecentAlbums(limit)
    fun artists(): Flow<List<ArtistRow>> = dao.observeArtists()
    fun genres(): Flow<List<GroupRow>> = dao.observeGenres()
    fun folders(): Flow<List<GroupRow>> = dao.observeFolders()
    fun albumTracks(albumId: String) = dao.observeAlbumTracks(albumId)
    fun artistTracks(artistKey: String) = dao.observeArtistTracks(artistKey)
    fun genreTracks(genre: String) = dao.observeGenreTracks(genre)
    fun folderTracks(folder: String) = dao.observeFolderTracks(folder)
    fun recentlyAdded(limit: Int = 50) = dao.observeRecentlyAdded(limit)
    fun pickedFolders(): Flow<List<FolderEntity>> = db.folders().observeFolders()
    fun byTrackKeys(keys: List<String>): Flow<List<TrackEntity>> = dao.observeByTrackKeys(keys)

    suspend fun track(id: Long): TrackEntity? = dao.byId(id)
    suspend fun trackByUri(uri: String): TrackEntity? = dao.byUri(uri)
    suspend fun random(limit: Int): List<TrackEntity> = dao.random(limit)

    /** Rows for [ids], in the order given. */
    suspend fun tracks(ids: List<Long>): List<TrackEntity> {
        if (ids.isEmpty()) return emptyList()
        val byId = ids.chunked(500).flatMap { dao.byIds(it) }.associateBy { it.id }
        return ids.mapNotNull { byId[it] }
    }

    /** Best local file for each key (keys come from playlists/favourites, possibly synced from another device). */
    suspend fun resolveKeys(keys: List<String>): List<TrackEntity> {
        if (keys.isEmpty()) return emptyList()
        val byKey = keys.distinct().chunked(500).flatMap { dao.byTrackKeys(it) }.groupBy { it.trackKey }
        return keys.mapNotNull { k -> byKey[k]?.maxWithOrNull(compareBy<TrackEntity> { qualityRank(it) }.thenBy { it.sizeBytes }) }
    }

    suspend fun search(query: String, limit: Int = 200): List<TrackEntity> {
        val fts = ftsQuery(query) ?: return emptyList()
        return runCatching { dao.search(fts, limit) }.getOrElse { emptyList() }
    }

    // ---- Permissions & folders ---------------------------------------------------------------------

    fun hasAudioPermission(): Boolean = ContextCompat.checkSelfPermission(context, audioPermission()) == PackageManager.PERMISSION_GRANTED

    suspend fun addFolder(treeUri: Uri): Boolean = withContext(Dispatchers.IO) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
        runCatching { context.contentResolver.takePersistableUriPermission(treeUri, flags) }
            .onFailure { Log.w(TAG, "No persistable permission for $treeUri", it); return@withContext false }
        db.folders().upsert(FolderEntity(treeUri.toString(), folderName(treeUri), System.currentTimeMillis()))
        true
    }

    suspend fun removeFolder(treeUri: String) = withContext(Dispatchers.IO) {
        runCatching { context.contentResolver.releasePersistableUriPermission(Uri.parse(treeUri), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        dao.deleteTree(treeUri)
        db.folders().delete(treeUri)
    }

    // ---- Scanning --------------------------------------------------------------------------------

    /** Full incremental scan. Safe to call repeatedly; concurrent calls wait for the running one. */
    suspend fun scan(): ScanState.Finished = scanMutex.withLock {
        withContext(Dispatchers.IO) {
            val s = settings.current()
            val minMs = s.minTrackSeconds * 1000L
            val startedAt = System.currentTimeMillis()
            val notes = ArrayList<String>()
            var added = 0
            var removed = 0

            // 1. MediaStore.
            if (s.useMediaStore && hasAudioPermission()) {
                _scan.value = ScanState.Running(ScanState.Phase.MEDIA_STORE)
                val found = runCatching { MediaStoreScanner(context).scan(minMs) }
                    .onFailure { notes += "Android's media index couldn't be read: ${it.message}" }
                    .getOrDefault(emptyList())
                added += merge(found, dao.fingerprints(TrackSource.MEDIA_STORE).associateBy { it.uri }, startedAt)
                if (found.isNotEmpty() || notes.isEmpty()) removed += dao.deleteStale(TrackSource.MEDIA_STORE, startedAt)
            } else if (s.useMediaStore) {
                notes += "Music access isn't granted, so only folders you added are scanned."
            } else {
                removed += dao.deleteStale(TrackSource.MEDIA_STORE, Long.MAX_VALUE)
            }

            // 2. Picked folders.
            val covers = HashMap<String, Uri>()
            val folders = db.folders().all()
            val granted = context.contentResolver.persistedUriPermissions.filter { it.isReadPermission }.map { it.uri.toString() }.toSet()
            folders.forEachIndexed { i, f ->
                _scan.value = ScanState.Running(ScanState.Phase.FOLDERS, i, folders.size)
                if (f.treeUri !in granted) {
                    notes += "Access to “${f.displayName}” was revoked. Add the folder again to keep it."
                    return@forEachIndexed
                }
                val known = dao.fingerprintsInTree(f.treeUri).associateBy { it.uri }
                val result = runCatching {
                    SafScanner(context).scan(Uri.parse(f.treeUri), f.displayName, minMs, known.mapValues { it.value.dateModified })
                }.onFailure { notes += "“${f.displayName}” couldn't be read: ${it.message}" }.getOrNull() ?: return@forEachIndexed
                added += merge(result.tracks, known, startedAt)
                removed += dao.deleteStaleInTree(TrackSource.FOLDER, f.treeUri, startedAt)
                db.lyricsSidecars().upsertAll(result.sidecarLyrics.map { (t, l) -> LyricsSidecarEntity(t, l) })
                result.folderCovers.forEach { (folder, uri) -> covers[folder] = uri }
                db.folders().upsert(f.copy(lastScanAt = startedAt, trackCount = result.tracks.size))
            }

            // 3. Technical details (codec, bit depth, ReplayGain, embedded lyrics).
            enrichAll()

            // 4. Album artwork.
            for (path in dao.artworkPaths()) if (!artwork.exists(path)) dao.clearArtworkPath(path)
            var artDone = 0
            while (true) {
                val batch = dao.albumsWithoutArtwork(50)
                if (batch.isEmpty()) break
                for (albumId in batch) {
                    _scan.value = ScanState.Running(ScanState.Phase.ARTWORK, artDone++, 0)
                    val first = dao.firstOfAlbum(albumId) ?: continue
                    dao.setAlbumArtwork(albumId, artwork.extract(first, covers[first.folderPath]))
                }
            }

            val total = dao.count()
            ScanState.Finished(System.currentTimeMillis(), total, added, removed, notes).also { _scan.value = it }
        }
    }

    private suspend fun enrichAll() {
        var done = 0
        val total = dao.unenrichedCount()
        while (true) {
            val batch = dao.unenriched(50)
            if (batch.isEmpty()) break
            for (t in batch) {
                _scan.value = ScanState.Running(ScanState.Phase.DETAILS, done++, total)
                val e = enricher.read(t.uri, t.fileName, t.sizeBytes)
                val now = System.currentTimeMillis()
                if (e == null) {
                    // Unreadable by Media3 (e.g. ALAC without a device decoder): keep a best-effort codec label.
                    dao.updateEnrichment(
                        t.id, AudioQuality.codecFor(t.mimeType, null, t.fileName).name, null, null, null,
                        AudioQuality.averageBitrate(t.sizeBytes, t.durationMs), null, null, null, null, false, 0, now,
                    )
                } else {
                    dao.updateEnrichment(
                        t.id, e.codec, e.sampleRate, e.bitDepth, e.channels, e.bitrate,
                        e.replayGain.trackGainDb, e.replayGain.trackPeak, e.replayGain.albumGainDb, e.replayGain.albumPeak,
                        e.lyrics != null, e.durationMs, now,
                    )
                }
            }
        }
    }

    /**
     * Upserts changed/new files, re-using row ids so playlists' and the queue's references stay valid.
     * Unchanged files (same modified time and size) only get their `seenAt` bumped, keeping enrichment.
     * Returns the number of new files.
     */
    private suspend fun merge(found: List<ScannedTrack>, known: Map<String, app.unpaused.data.db.TrackFingerprint>, at: Long): Int {
        var added = 0
        val unchanged = ArrayList<String>()
        val changed = ArrayList<TrackEntity>()
        for (t in found) {
            val k = known[t.uri]
            if (k != null && (t.durationMs < 0 || (k.dateModified == t.dateModified && k.sizeBytes == t.sizeBytes))) {
                unchanged += t.uri
            } else if (t.durationMs >= 0) {
                if (k == null) added++
                changed += t.toEntity(id = k?.id ?: 0, seenAt = at)
            }
        }
        unchanged.chunked(500).forEach { dao.markSeen(it, at) }
        changed.chunked(200).forEach { dao.upsert(it) }
        return added
    }

    private fun folderName(treeUri: Uri): String = runCatching {
        val docId = DocumentsContract.getTreeDocumentId(treeUri)
        val doc = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
        context.contentResolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }.getOrNull() ?: treeUri.lastPathSegment?.substringAfterLast(':')?.ifBlank { null } ?: "Folder"

    companion object {
        private const val TAG = "UnpausedLibrary"

        fun audioPermission(): String =
            if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

        /** Turns free text into a safe FTS4 prefix query: `daft punk` → `"daft*" "punk*"` (quoted so AND/OR/NEAR stay words). */
        fun ftsQuery(raw: String): String? {
            val tokens = raw.split(Regex("""[^\p{L}\p{M}\p{N}]+""")).filter { it.isNotBlank() }.take(8)
            if (tokens.isEmpty()) return null
            return tokens.joinToString(" ") { "\"" + it + "*\"" }
        }

        /** Prefer lossless, then higher sample rate, when several files share a track key. */
        fun qualityRank(t: TrackEntity): Int {
            val lossless = t.codec?.let { c -> runCatching { app.unpaused.core.audio.AudioCodec.valueOf(c).lossless }.getOrNull() } ?: false
            return (if (lossless) 1_000_000 else 0) + (t.sampleRate ?: 0) / 100 + (t.bitDepth ?: 0)
        }
    }
}
