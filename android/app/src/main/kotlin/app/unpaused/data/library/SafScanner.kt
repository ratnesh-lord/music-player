package app.unpaused.data.library

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import app.unpaused.core.aiff.AiffParser
import app.unpaused.core.aiff.ByteArraySource
import app.unpaused.data.db.TrackSource

/** A document found under a user-picked folder (Storage Access Framework tree). */
data class SafDocument(
    val uri: Uri,
    val name: String,
    val mimeType: String?,
    val size: Long,
    val lastModified: Long,
    /** Path relative to the tree root, e.g. "Music/Album". */
    val folder: String,
)

data class SafScanResult(
    val tracks: List<ScannedTrack>,
    /** Track uri → .lrc document uri (same base name, same folder). */
    val sidecarLyrics: Map<String, String>,
    /** Folder path → cover image uri (cover/folder/front .jpg/.png). */
    val folderCovers: Map<String, Uri>,
)

/**
 * Walks a SAF tree with DocumentsContract queries (much faster than DocumentFile) and reads tags with
 * MediaMetadataRetriever. No storage permission is needed: the user granted this folder.
 */
class SafScanner(private val context: Context) {

    fun scan(treeUri: Uri, treeName: String, minDurationMs: Long, known: Map<String, Long>): SafScanResult {
        val audio = ArrayList<SafDocument>()
        val lrc = HashMap<String, Uri>() // "folder/basename" (lower-case) → uri
        val covers = HashMap<String, Uri>()

        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        val stack = ArrayDeque<Pair<String, String>>() // docId to folder path
        stack.addLast(rootId to treeName)
        var visited = 0
        while (stack.isNotEmpty() && visited < MAX_FOLDERS) {
            val (docId, folder) = stack.removeLast()
            visited++
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
            runCatching {
                context.contentResolver.query(children, PROJECTION, null, null, null)?.use { c ->
                    while (c.moveToNext()) {
                        val id = c.getString(0) ?: continue
                        val name = c.getString(1) ?: continue
                        val mime = c.getString(2)
                        val size = if (c.isNull(3)) 0L else c.getLong(3)
                        val modified = if (c.isNull(4)) 0L else c.getLong(4)
                        if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                            if (!name.startsWith(".")) stack.addLast(id to "$folder/$name")
                            continue
                        }
                        val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                        val ext = name.substringAfterLast('.', "").lowercase()
                        when {
                            ext in ScannedTrack.AUDIO_EXTENSIONS || mime?.startsWith("audio/") == true ->
                                audio += SafDocument(uri, name, mime, size, modified, folder)
                            ext == "lrc" -> lrc["${folder.lowercase()}/${name.substringBeforeLast('.').lowercase()}"] = uri
                            name.lowercase() in COVER_NAMES -> covers.putIfAbsent(folder, uri)
                        }
                    }
                }
            }.onFailure { Log.w(TAG, "Could not list $folder", it) }
        }

        val tracks = ArrayList<ScannedTrack>(audio.size)
        val sidecars = HashMap<String, String>()
        for (doc in audio) {
            val uriString = doc.uri.toString()
            val key = "${doc.folder.lowercase()}/${doc.name.substringBeforeLast('.').lowercase()}"
            lrc[key]?.let { sidecars[uriString] = it.toString() }
            // Unchanged files only need their fingerprint; the repository keeps the old row.
            if (known[uriString] == doc.lastModified && doc.lastModified != 0L) {
                tracks += placeholder(doc, treeUri)
                continue
            }
            val t = read(doc, treeUri) ?: continue
            if (t.durationMs in 1 until minDurationMs) continue
            tracks += t
        }
        return SafScanResult(tracks, sidecars, covers)
    }

    private fun placeholder(doc: SafDocument, treeUri: Uri) = ScannedTrack(
        uri = doc.uri.toString(), source = TrackSource.FOLDER, mediaStoreId = null, treeUri = treeUri.toString(),
        title = null, artist = null, albumArtist = null, album = null, genre = null, year = null,
        trackNumber = null, discNumber = null, durationMs = -1, sizeBytes = doc.size, mimeType = doc.mimeType,
        folderPath = doc.folder, fileName = doc.name, dateAdded = doc.lastModified, dateModified = doc.lastModified,
    )

    private fun read(doc: SafDocument, treeUri: Uri): ScannedTrack? {
        val ext = doc.name.substringAfterLast('.', "").lowercase()
        var title: String? = null
        var artist: String? = null
        var albumArtist: String? = null
        var album: String? = null
        var genre: String? = null
        var year: Int? = null
        var track: Int? = null
        var disc: Int? = null
        var duration = 0L
        var mime = doc.mimeType
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(context, doc.uri)
            title = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
            artist = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
            albumArtist = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
            album = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
            genre = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE)
            year = ScannedTrack.parseLeadingInt(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR))
            track = ScannedTrack.parseLeadingInt(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER))
            disc = ScannedTrack.parseLeadingInt(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER))
            duration = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            mime = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE) ?: mime
        } catch (e: Exception) {
            // Android has no AIFF extractor; read the header ourselves. Other failures keep the file name.
            if (ext in setOf("aif", "aiff", "aifc")) duration = aiffDuration(doc.uri)
        } finally {
            runCatching { mmr.release() }
        }
        if (duration == 0L && ext in setOf("aif", "aiff", "aifc")) duration = aiffDuration(doc.uri)
        return ScannedTrack(
            uri = doc.uri.toString(),
            source = TrackSource.FOLDER,
            mediaStoreId = null,
            treeUri = treeUri.toString(),
            title = title,
            artist = artist,
            albumArtist = albumArtist,
            album = album,
            genre = genre,
            year = year,
            trackNumber = track,
            discNumber = disc,
            durationMs = duration,
            sizeBytes = doc.size,
            mimeType = mime,
            folderPath = doc.folder,
            fileName = doc.name,
            dateAdded = doc.lastModified.takeIf { it > 0 } ?: System.currentTimeMillis(),
            dateModified = doc.lastModified,
        )
    }

    private fun aiffDuration(uri: Uri): Long = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val head = ByteArray(64 * 1024)
            var read = 0
            while (read < head.size) {
                val n = input.read(head, read, head.size - read)
                if (n < 0) break
                read += n
            }
            AiffParser.parseHeader(ByteArraySource(head.copyOf(read))).durationUs / 1000
        } ?: 0L
    }.getOrDefault(0L)

    companion object {
        private const val TAG = "UnpausedSaf"
        private const val MAX_FOLDERS = 20_000
        private val PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        private val COVER_NAMES = setOf("cover.jpg", "cover.png", "folder.jpg", "folder.png", "front.jpg", "front.png", "albumart.jpg")
    }
}
