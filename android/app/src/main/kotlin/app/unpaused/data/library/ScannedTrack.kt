package app.unpaused.data.library

import app.unpaused.core.library.TrackKey
import app.unpaused.data.db.TrackEntity

/** Raw facts a scanner found about one file, before normalisation into a [TrackEntity]. */
data class ScannedTrack(
    val uri: String,
    val source: Int,
    val mediaStoreId: Long?,
    val treeUri: String?,
    val title: String?,
    val artist: String?,
    val albumArtist: String?,
    val album: String?,
    val genre: String?,
    val year: Int?,
    val trackNumber: Int?,
    val discNumber: Int?,
    val durationMs: Long,
    val sizeBytes: Long,
    val mimeType: String?,
    val folderPath: String,
    val fileName: String,
    val dateAdded: Long,
    val dateModified: Long,
) {
    fun toEntity(id: Long, seenAt: Long): TrackEntity {
        val cleanTitle = title.clean() ?: fileName.substringBeforeLast('.').ifBlank { fileName }
        val cleanArtist = artist.clean() ?: UNKNOWN_ARTIST
        val cleanAlbumArtist = albumArtist.clean()
        val folderName = folderPath.trimEnd('/').substringAfterLast('/').ifBlank { folderPath }
        val cleanAlbum = album.clean() ?: folderName.ifBlank { UNKNOWN_ALBUM }
        // Without an album artist, group by folder so a compilation in one folder stays one album.
        val albumOwner = cleanAlbumArtist?.let(TrackKey::normalize) ?: "folder:" + folderPath.lowercase()
        return TrackEntity(
            id = id,
            uri = uri,
            source = source,
            mediaStoreId = mediaStoreId,
            treeUri = treeUri,
            title = cleanTitle,
            artist = cleanArtist,
            albumArtist = cleanAlbumArtist,
            album = cleanAlbum,
            albumId = TrackKey.normalize(cleanAlbum) + "|" + albumOwner,
            artistKey = TrackKey.normalize(cleanArtist).ifEmpty { "unknown" },
            genre = genre.clean(),
            year = year?.takeIf { it in 1000..3000 },
            trackNumber = trackNumber?.takeIf { it > 0 },
            discNumber = discNumber?.takeIf { it > 0 },
            durationMs = durationMs.coerceAtLeast(0),
            sizeBytes = sizeBytes,
            mimeType = mimeType,
            folderPath = folderPath,
            fileName = fileName,
            dateAdded = dateAdded,
            dateModified = dateModified,
            trackKey = TrackKey.of(cleanTitle, cleanArtist, cleanAlbum, durationMs),
            titleSort = TrackKey.sortKey(cleanTitle),
            artistSort = TrackKey.sortKey(cleanArtist),
            albumSort = TrackKey.sortKey(cleanAlbum),
            seenAt = seenAt,
        )
    }

    companion object {
        const val UNKNOWN_ARTIST = "Unknown artist"
        const val UNKNOWN_ALBUM = "Unknown album"

        private fun String?.clean(): String? {
            val t = this?.trim() ?: return null
            if (t.isEmpty() || t == "<unknown>" || t.equals("unknown", ignoreCase = true)) return null
            return t
        }

        /** "3/12" → 3 */
        fun parseLeadingInt(raw: String?): Int? = raw?.trim()?.takeWhile { it.isDigit() }?.toIntOrNull()

        val AUDIO_EXTENSIONS = setOf("mp3", "flac", "m4a", "m4b", "mp4", "aac", "ogg", "oga", "opus", "wav", "wave", "aif", "aiff", "aifc", "mka", "webm", "amr", "3gp")
    }
}
