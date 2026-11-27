package app.unpaused.core.audio

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** ReplayGain values read from a file's tags. Gains are in dB, peaks are linear (1.0 = full scale). */
data class ReplayGainInfo(
    val trackGainDb: Float? = null,
    val trackPeak: Float? = null,
    val albumGainDb: Float? = null,
    val albumPeak: Float? = null,
) {
    val isEmpty: Boolean get() = trackGainDb == null && albumGainDb == null
}

enum class ReplayGainMode { OFF, TRACK, ALBUM }

data class ReplayGainSettings(
    val mode: ReplayGainMode = ReplayGainMode.TRACK,
    /** Added to tagged files' gain. */
    val preampDb: Float = 0f,
    /** Applied to files without ReplayGain tags (e.g. -6 dB so they sit near tagged tracks). */
    val untaggedPreampDb: Float = 0f,
    /** Limit the gain so the tagged peak never exceeds full scale. */
    val preventClipping: Boolean = true,
)

/**
 * How to apply a gain. [attenuation] is a linear factor (0..1] for the player volume; [boostDb] (>= 0)
 * must be applied by an effect with a limiter because player volume cannot exceed 1.0.
 */
data class GainDecision(val totalDb: Float, val attenuation: Float, val boostDb: Float, val source: Source) {
    enum class Source { OFF, TRACK, ALBUM, UNTAGGED }

    companion object {
        val NONE = GainDecision(0f, 1f, 0f, Source.OFF)
    }
}

object ReplayGain {

    private val NUMBER = Regex("""[-+]?\d+(?:[.,]\d+)?""")

    /**
     * Reads ReplayGain from tag key/value pairs (Vorbis comments, ID3 TXXX descriptions, MP4 freeform
     * names, APE keys). Keys are matched case-insensitively. Opus R128 gains are converted to the
     * ReplayGain 2 reference (-18 LUFS).
     */
    fun fromTags(tags: Map<String, String>): ReplayGainInfo {
        val lower = tags.entries.associate { (k, v) -> k.trim().lowercase() to v }
        fun gain(key: String) = lower[key]?.let(::parseGainDb)
        fun peak(key: String) = lower[key]?.let(::parsePeak)
        return ReplayGainInfo(
            trackGainDb = gain("replaygain_track_gain") ?: lower["r128_track_gain"]?.let(::fromR128),
            trackPeak = peak("replaygain_track_peak"),
            albumGainDb = gain("replaygain_album_gain") ?: lower["r128_album_gain"]?.let(::fromR128),
            albumPeak = peak("replaygain_album_peak"),
        )
    }

    /** "-6.48 dB", "+1.2dB", "-6,48 dB" → dB. Returns null for anything unparseable or absurd. */
    fun parseGainDb(raw: String): Float? {
        val m = NUMBER.find(raw.trim()) ?: return null
        val v = m.value.replace(',', '.').toFloatOrNull() ?: return null
        return v.takeIf { it.isFinite() && it in -60f..60f }
    }

    fun parsePeak(raw: String): Float? {
        val m = NUMBER.find(raw.trim()) ?: return null
        val v = m.value.replace(',', '.').toFloatOrNull() ?: return null
        return v.takeIf { it.isFinite() && it > 0f && it < 100f }
    }

    /** Opus R128_*_GAIN: Q7.8 fixed-point dB relative to -23 LUFS. ReplayGain 2 references -18 LUFS. */
    fun fromR128(raw: String): Float? {
        val q = raw.trim().toIntOrNull() ?: return null
        if (q !in -32768..32767) return null
        return q / 256f + 5f
    }

    fun decide(info: ReplayGainInfo, settings: ReplayGainSettings): GainDecision {
        if (settings.mode == ReplayGainMode.OFF) return GainDecision.NONE
        val preferAlbum = settings.mode == ReplayGainMode.ALBUM
        val gain = if (preferAlbum) info.albumGainDb ?: info.trackGainDb else info.trackGainDb ?: info.albumGainDb
        val peak = if (preferAlbum) info.albumPeak ?: info.trackPeak else info.trackPeak ?: info.albumPeak
        val source = when {
            gain == null -> GainDecision.Source.UNTAGGED
            preferAlbum -> if (info.albumGainDb != null) GainDecision.Source.ALBUM else GainDecision.Source.TRACK
            else -> if (info.trackGainDb != null) GainDecision.Source.TRACK else GainDecision.Source.ALBUM
        }
        var total = if (gain == null) settings.untaggedPreampDb else gain + settings.preampDb
        if (settings.preventClipping && gain != null && peak != null && peak > 0f) {
            total = min(total, linearToDb(1f / peak))
        }
        total = total.coerceIn(-60f, 24f)
        val attenuation = if (total < 0f) dbToLinear(total) else 1f
        return GainDecision(totalDb = round2(total), attenuation = attenuation, boostDb = round2(max(total, 0f)), source = source)
    }

    fun dbToLinear(db: Float): Float = 10.0.pow(db / 20.0).toFloat()

    fun linearToDb(linear: Float): Float = (20.0 * log10(linear.toDouble())).toFloat()

    private fun round2(v: Float): Float = kotlin.math.round(v * 100f) / 100f
}
