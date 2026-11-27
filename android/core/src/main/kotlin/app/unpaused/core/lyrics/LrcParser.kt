package app.unpaused.core.lyrics

data class LyricLine(val timeMs: Long?, val text: String)

data class Lyrics(
    val lines: List<LyricLine>,
    val synced: Boolean,
    val title: String? = null,
    val artist: String? = null,
) {
    val isEmpty: Boolean get() = lines.none { it.text.isNotBlank() }

    companion object {
        val EMPTY = Lyrics(emptyList(), synced = false)
    }
}

/**
 * Parses LRC (time-tagged) lyrics and plain-text lyrics.
 * - `[mm:ss.xx]`, `[mm:ss.xxx]`, `[mm:ss]` and `[mm:ss:xx]` time tags; several tags per line.
 * - `[offset:±ms]`: positive offset shows lines earlier, as in the LRC convention.
 * - Enhanced (A2) word timestamps `<mm:ss.xx>` are stripped.
 * Text without any time tags is returned as unsynced lyrics.
 */
object LrcParser {

    private val TAG = Regex("""^\[([^\]]*)]""")
    private val TIME = Regex("""^(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?$""")
    private val WORD_TIME = Regex("""<\d{1,3}:\d{1,2}(?:[.:]\d{1,3})?>""")

    fun parse(text: String): Lyrics {
        var offsetMs = 0L
        var title: String? = null
        var artist: String? = null
        val timed = mutableListOf<Pair<Long, String>>()
        val plain = mutableListOf<String>()

        for (rawLine in text.replace("\r\n", "\n").replace('\r', '\n').split('\n')) {
            var rest = rawLine.trim().removePrefix("﻿")
            val times = mutableListOf<Long>()
            var sawTag = false
            while (true) {
                val m = TAG.find(rest) ?: break
                val body = m.groupValues[1].trim()
                val time = parseTime(body)
                when {
                    time != null -> times += time
                    body.startsWith("offset:", ignoreCase = true) ->
                        offsetMs = body.substringAfter(':').trim().removePrefix("+").toLongOrNull() ?: offsetMs
                    body.startsWith("ti:", ignoreCase = true) -> title = body.substringAfter(':').trim().ifEmpty { null }
                    body.startsWith("ar:", ignoreCase = true) -> artist = body.substringAfter(':').trim().ifEmpty { null }
                    body.contains(':') -> Unit // other metadata: al, by, length, re, ve, #
                    else -> break // not a tag (e.g. "[Chorus]" in plain lyrics)
                }
                sawTag = true
                rest = rest.substring(m.range.last + 1)
            }
            val lyric = WORD_TIME.replace(rest, "").trim()
            if (times.isNotEmpty()) {
                for (t in times) timed += t to lyric
            } else if (!sawTag) {
                plain += rawLine.trimEnd()
            }
        }

        if (timed.isEmpty()) {
            val lines = plain.dropWhile { it.isBlank() }.dropLastWhile { it.isBlank() }.map { LyricLine(null, it) }
            return Lyrics(lines, synced = false, title = title, artist = artist)
        }
        val lines = timed
            .map { (t, s) -> LyricLine((t - offsetMs).coerceAtLeast(0), s) }
            .sortedBy { it.timeMs } // stable: keeps file order for equal times
        return Lyrics(lines, synced = true, title = title, artist = artist)
    }

    /** Index of the line to highlight at [positionMs], or -1 before the first line / for unsynced lyrics. */
    fun lineIndexAt(lyrics: Lyrics, positionMs: Long): Int {
        if (!lyrics.synced || lyrics.lines.isEmpty()) return -1
        var lo = 0
        var hi = lyrics.lines.size - 1
        var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if ((lyrics.lines[mid].timeMs ?: 0) <= positionMs) {
                ans = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return ans
    }

    private fun parseTime(body: String): Long? {
        val m = TIME.matchEntire(body) ?: return null
        val min = m.groupValues[1].toLong()
        val sec = m.groupValues[2].toLong()
        if (sec >= 60) return null
        val frac = m.groupValues[3]
        val fracMs = when (frac.length) {
            0 -> 0L
            1 -> frac.toLong() * 100
            2 -> frac.toLong() * 10
            else -> frac.toLong()
        }
        return (min * 60 + sec) * 1000 + fracMs
    }
}
