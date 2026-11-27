package app.unpaused.core.library

import java.text.Normalizer
import java.util.Locale

/**
 * Device-independent identity of a song, used to match favourites, playlists and history across the
 * phone and the browser (each plays its own copy of the file). The web app implements the same rules
 * in `web/src/lib/library/trackKey.ts`; `shared/test-vectors/track-keys.json` keeps them identical.
 */
object TrackKey {

    private val MARKS = Regex("""\p{M}+""")
    private val NON_WORD = Regex("""[^\p{L}\p{N}]+""")

    /** Lower-case, accent-free, punctuation-free, single-spaced. */
    fun normalize(value: String?): String {
        if (value.isNullOrBlank()) return ""
        val decomposed = Normalizer.normalize(value, Normalizer.Form.NFKD)
        return MARKS.replace(decomposed, "")
            .lowercase(Locale.ROOT)
            .replace(NON_WORD, " ")
            .trim()
    }

    /** Durations are bucketed to 2 s so re-encodes of the same track still match. */
    fun durationBucket(durationMs: Long?): Long = if (durationMs == null || durationMs <= 0) 0 else Math.round(durationMs / 2000.0)

    fun of(title: String?, artist: String?, album: String?, durationMs: Long?): String =
        listOf(normalize(artist), normalize(album), normalize(title), durationBucket(durationMs).toString()).joinToString("|")

    /** Sort key that ignores a leading "The " / "A " and case, like most music libraries. */
    fun sortKey(value: String?): String {
        val n = normalize(value)
        for (article in ARTICLES) if (n.startsWith(article) && n.length > article.length) return n.substring(article.length)
        return n
    }

    private val ARTICLES = listOf("the ", "a ", "an ")
}
