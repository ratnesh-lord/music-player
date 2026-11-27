package app.unpaused.data.lyrics

import android.content.Context
import android.net.Uri
import app.unpaused.core.lyrics.LrcParser
import app.unpaused.core.lyrics.Lyrics
import app.unpaused.data.db.AppDatabase
import app.unpaused.data.db.TrackEntity
import app.unpaused.data.library.MetadataEnricher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * Lyrics come only from the user's own files: a `.lrc` next to the track (folders added in the app)
 * or lyrics embedded in the tags. No lyrics service is queried (see docs/research/02-music-providers.md).
 */
class LyricsRepository(private val context: Context, private val db: AppDatabase, private val enricher: MetadataEnricher) {

    private var cacheKey: String? = null
    private var cacheValue: Lyrics? = null

    suspend fun load(track: TrackEntity): Lyrics? = withContext(Dispatchers.IO) {
        if (cacheKey == track.uri) return@withContext cacheValue
        val text = db.lyricsSidecars().get(track.uri)?.let { readText(Uri.parse(it.lrcUri)) }
            ?: if (track.hasLyrics || track.enrichedAt == null) enricher.read(track.uri, track.fileName, track.sizeBytes)?.lyrics else null
        val lyrics = text?.let(LrcParser::parse)?.takeIf { it.lines.isNotEmpty() }
        cacheKey = track.uri
        cacheValue = lyrics
        lyrics
    }

    private fun readText(uri: Uri): String? = runCatching {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        decode(bytes)
    }.getOrNull()

    companion object {
        /** UTF-8 (with or without BOM) when valid, otherwise Windows-1252, which most legacy .lrc files use. */
        fun decode(bytes: ByteArray): String {
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            return try {
                decoder.decode(ByteBuffer.wrap(bytes)).toString()
            } catch (_: CharacterCodingException) {
                String(bytes, charset("windows-1252"))
            }
        }
    }
}
