package app.unpaused.data.library

import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import app.unpaused.data.db.TrackSource

/** Reads the device's audio index. Requires READ_MEDIA_AUDIO (13+) / READ_EXTERNAL_STORAGE (≤12). */
class MediaStoreScanner(private val context: Context) {

    fun scan(minDurationMs: Long): List<ScannedTrack> {
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }
        val columns = mutableListOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.DATE_MODIFIED,
            MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.YEAR,
            MediaStore.Audio.Media.DISPLAY_NAME,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) columns += MediaStore.Audio.Media.RELATIVE_PATH
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) columns += MediaStore.Audio.Media.DATA
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            columns += MediaStore.Audio.Media.ALBUM_ARTIST
            columns += MediaStore.Audio.Media.GENRE
        }

        val selection = buildString {
            append("${MediaStore.Audio.Media.IS_RINGTONE} = 0 AND ${MediaStore.Audio.Media.IS_NOTIFICATION} = 0")
            append(" AND ${MediaStore.Audio.Media.IS_ALARM} = 0")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) append(" AND ${MediaStore.Audio.Media.IS_RECORDING} = 0")
            // Some formats (e.g. AIFF) are indexed without a duration; keep them and measure later.
            append(" AND (${MediaStore.Audio.Media.DURATION} >= ? OR ${MediaStore.Audio.Media.DURATION} IS NULL OR ${MediaStore.Audio.Media.DURATION} = 0)")
        }

        val out = ArrayList<ScannedTrack>()
        context.contentResolver.query(collection, columns.toTypedArray(), selection, arrayOf(minDurationMs.toString()), null)?.use { c ->
            fun idx(name: String) = c.getColumnIndex(name)
            val iId = idx(MediaStore.Audio.Media._ID)
            val iTitle = idx(MediaStore.Audio.Media.TITLE)
            val iArtist = idx(MediaStore.Audio.Media.ARTIST)
            val iAlbum = idx(MediaStore.Audio.Media.ALBUM)
            val iDuration = idx(MediaStore.Audio.Media.DURATION)
            val iSize = idx(MediaStore.Audio.Media.SIZE)
            val iMime = idx(MediaStore.Audio.Media.MIME_TYPE)
            val iAdded = idx(MediaStore.Audio.Media.DATE_ADDED)
            val iModified = idx(MediaStore.Audio.Media.DATE_MODIFIED)
            val iTrack = idx(MediaStore.Audio.Media.TRACK)
            val iYear = idx(MediaStore.Audio.Media.YEAR)
            val iName = idx(MediaStore.Audio.Media.DISPLAY_NAME)
            val iRelPath = idx("relative_path")
            val iData = idx("_data")
            val iAlbumArtist = idx("album_artist")
            val iGenre = idx("genre")

            while (c.moveToNext()) {
                val id = c.getLong(iId)
                val name = c.getString(iName) ?: "Track $id"
                val folder = when {
                    iRelPath >= 0 -> c.getString(iRelPath)?.trimEnd('/') ?: ""
                    iData >= 0 -> c.getString(iData)?.substringBeforeLast('/')?.removePrefix("/storage/emulated/0/") ?: ""
                    else -> ""
                }
                if (EXCLUDED_FOLDERS.any { folder.contains(it, ignoreCase = true) }) continue
                val trackRaw = if (iTrack >= 0 && !c.isNull(iTrack)) c.getInt(iTrack) else 0
                out += ScannedTrack(
                    uri = ContentUris.withAppendedId(collection, id).toString(),
                    source = TrackSource.MEDIA_STORE,
                    mediaStoreId = id,
                    treeUri = null,
                    title = c.getString(iTitle),
                    artist = c.getString(iArtist),
                    albumArtist = if (iAlbumArtist >= 0) c.getString(iAlbumArtist) else null,
                    album = c.getString(iAlbum),
                    genre = if (iGenre >= 0) c.getString(iGenre) else null,
                    year = if (iYear >= 0 && !c.isNull(iYear)) c.getInt(iYear) else null,
                    // MediaStore encodes disc 2 track 3 as 2003.
                    trackNumber = (trackRaw % 1000).takeIf { it > 0 },
                    discNumber = (trackRaw / 1000).takeIf { it > 0 },
                    durationMs = if (iDuration >= 0 && !c.isNull(iDuration)) c.getLong(iDuration) else 0L,
                    sizeBytes = if (iSize >= 0) c.getLong(iSize) else 0L,
                    mimeType = c.getString(iMime),
                    folderPath = folder,
                    fileName = name,
                    dateAdded = c.getLong(iAdded) * 1000,
                    dateModified = c.getLong(iModified) * 1000,
                )
            }
        }
        return out
    }

    companion object {
        /** Folders that hold voice notes, call recordings and system sounds rather than music. */
        val EXCLUDED_FOLDERS = listOf(
            "WhatsApp Voice Notes",
            "WhatsApp/Media/WhatsApp Audio",
            "Call recordings",
            "Recordings/Call",
            "Notifications",
            "Ringtones",
            "Alarms",
        )
    }
}
