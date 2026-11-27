package app.unpaused.data.library

import androidx.media3.common.Metadata
import androidx.media3.extractor.metadata.id3.BinaryFrame
import androidx.media3.extractor.metadata.id3.CommentFrame
import androidx.media3.extractor.metadata.id3.InternalFrame
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.metadata.vorbis.VorbisComment
import java.nio.charset.Charset

/** Free-form tags (upper-case keys) and embedded lyrics, from the metadata Media3's extractors attach to a Format. */
data class FileTags(val values: Map<String, String>, val lyrics: String?)

object TagReader {

    /**
     * Collects tags from FLAC/Ogg Vorbis comments, ID3 TXXX/USLT frames (MP3, AIFF, WAV) and MP4
     * freeform atoms (iTunes "----" atoms arrive as InternalFrame; ©lyr arrives as a "USLT" text frame).
     */
    fun read(vararg metadata: Metadata?): FileTags {
        val tags = LinkedHashMap<String, String>()
        var lyrics: String? = null
        for (m in metadata) {
            m ?: continue
            for (j in 0 until m.length()) {
                when (val e = m.get(j)) {
                    is VorbisComment -> {
                        tags.putIfAbsent(e.key.uppercase(), e.value)
                        if (e.key.equals("LYRICS", true) || e.key.equals("UNSYNCEDLYRICS", true)) lyrics = lyrics ?: e.value
                    }
                    is TextInformationFrame -> {
                        val v = e.values.firstOrNull().orEmpty()
                        val description = e.description
                        if (e.id == "TXXX" && description != null) tags.putIfAbsent(description.uppercase(), v)
                        if (e.id == "USLT" && v.isNotBlank()) lyrics = lyrics ?: v
                    }
                    is InternalFrame -> tags.putIfAbsent(e.description.uppercase(), e.text)
                    is CommentFrame -> if (e.description.contains("lyrics", true)) lyrics = lyrics ?: e.text
                    is BinaryFrame -> if (e.id == "USLT") lyrics = lyrics ?: decodeUslt(e.data)
                    else -> Unit
                }
            }
        }
        return FileTags(tags, lyrics?.takeIf { it.isNotBlank() })
    }

    /** ID3v2 USLT: encoding(1) language(3) descriptor(NUL-terminated) text. */
    fun decodeUslt(data: ByteArray): String? {
        if (data.size < 5) return null
        val enc = data[0].toInt()
        val charset: Charset = when (enc) {
            0 -> Charsets.ISO_8859_1
            1 -> Charsets.UTF_16
            2 -> Charsets.UTF_16BE
            else -> Charsets.UTF_8
        }
        val wide = enc == 1 || enc == 2
        var i = 4
        if (wide) {
            while (i + 1 < data.size && !(data[i].toInt() == 0 && data[i + 1].toInt() == 0)) i += 2
            i += 2
        } else {
            while (i < data.size && data[i].toInt() != 0) i++
            i += 1
        }
        if (i >= data.size) return null
        return String(data, i, data.size - i, charset).trim('\u0000', ' ', '\n', '\r').ifBlank { null }
    }
}
