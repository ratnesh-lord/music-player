package app.unpaused.core.audio

/**
 * Codec identity of the *source* file. [lossless] comes from the codec alone, never from bitrate,
 * so a 320 kbps MP3 is always lossy (the brief forbids labelling compressed audio as lossless).
 */
enum class AudioCodec(val label: String, val lossless: Boolean) {
    FLAC("FLAC", true),
    ALAC("ALAC", true),
    WAV("WAV", true),
    AIFF("AIFF", true),
    MP3("MP3", false),
    AAC("AAC", false),
    VORBIS("Vorbis", false),
    OPUS("Opus", false),
    AC3("AC-3", false),
    EAC3("E-AC-3", false),
    UNKNOWN("Unknown", false),
}

enum class QualityTier { LOSSY, LOSSLESS, HI_RES_LOSSLESS, UNKNOWN }

data class SourceQuality(
    val codec: AudioCodec,
    val sampleRateHz: Int? = null,
    /** Only meaningful for lossless/PCM codecs; ignored for lossy ones. */
    val bitDepth: Int? = null,
    val channels: Int? = null,
    val bitrateBps: Long? = null,
)

object AudioQuality {

    /**
     * Identifies the codec from Media3/Android MIME types, falling back to the file extension.
     * PCM ("audio/raw") is disambiguated by container or extension (WAV vs AIFF).
     */
    fun codecFor(sampleMime: String?, containerMime: String? = null, fileName: String? = null): AudioCodec {
        val ext = fileName?.substringAfterLast('.', "")?.lowercase().orEmpty()
        when (sampleMime?.lowercase()) {
            "audio/flac", "audio/x-flac" -> return AudioCodec.FLAC
            "audio/alac" -> return AudioCodec.ALAC
            "audio/mpeg", "audio/mpeg-l2", "audio/mpeg-l1", "audio/mp3" -> return AudioCodec.MP3
            "audio/mp4a-latm", "audio/aac", "audio/mp4", "audio/x-m4a" -> return AudioCodec.AAC
            "audio/vorbis" -> return AudioCodec.VORBIS
            "audio/opus" -> return AudioCodec.OPUS
            "audio/ac3" -> return AudioCodec.AC3
            "audio/eac3", "audio/eac3-joc" -> return AudioCodec.EAC3
            "audio/raw", "audio/wav", "audio/x-wav", "audio/aiff", "audio/x-aiff" -> {
                val c = containerMime?.lowercase().orEmpty()
                return when {
                    "aiff" in c || ext in AIFF_EXT -> AudioCodec.AIFF
                    else -> AudioCodec.WAV
                }
            }
        }
        return when (ext) {
            "flac" -> AudioCodec.FLAC
            "wav", "wave" -> AudioCodec.WAV
            in AIFF_EXT -> AudioCodec.AIFF
            "mp3" -> AudioCodec.MP3
            "aac" -> AudioCodec.AAC
            "ogg", "oga" -> AudioCodec.VORBIS
            "opus" -> AudioCodec.OPUS
            // .m4a can be AAC or ALAC; without a decoder MIME we cannot tell, so don't guess.
            else -> AudioCodec.UNKNOWN
        }
    }

    fun tier(q: SourceQuality): QualityTier = when {
        q.codec == AudioCodec.UNKNOWN -> QualityTier.UNKNOWN
        !q.codec.lossless -> QualityTier.LOSSY
        (q.bitDepth ?: 16) > 16 || (q.sampleRateHz ?: 44_100) > 48_000 -> QualityTier.HI_RES_LOSSLESS
        else -> QualityTier.LOSSLESS
    }

    /** "FLAC · 24-bit · 96 kHz · 2,304 kbps". Bit depth is shown only for lossless codecs. */
    fun describe(q: SourceQuality): String = buildList {
        add(q.codec.label)
        if (q.codec.lossless) q.bitDepth?.takeIf { it > 0 }?.let { add("$it-bit") }
        q.sampleRateHz?.takeIf { it > 0 }?.let { add(formatSampleRate(it)) }
        when (q.channels) {
            null, 0, 2 -> Unit
            1 -> add("mono")
            else -> add("${q.channels} ch")
        }
        q.bitrateBps?.takeIf { it > 0 }?.let { add(formatBitrate(it)) }
    }.joinToString(" · ")

    fun tierLabel(tier: QualityTier): String = when (tier) {
        QualityTier.LOSSY -> "Lossy"
        QualityTier.LOSSLESS -> "Lossless"
        QualityTier.HI_RES_LOSSLESS -> "Hi-Res Lossless"
        QualityTier.UNKNOWN -> "Unknown"
    }

    /** 44100 → "44.1 kHz", 22050 → "22.05 kHz", 96000 → "96 kHz". */
    fun formatSampleRate(hz: Int): String {
        val frac = hz % 1000
        if (frac == 0) return "${hz / 1000} kHz"
        val digits = frac.toString().padStart(3, '0').trimEnd('0')
        return "${hz / 1000}.$digits kHz"
    }

    fun formatBitrate(bps: Long): String = "${group(Math.round(bps / 1000.0))} kbps"

    /** Bitrate of a file from its size and duration (used for lossless files without a header bitrate). */
    fun averageBitrate(fileSizeBytes: Long, durationMs: Long): Long? =
        if (fileSizeBytes <= 0 || durationMs <= 0) null else fileSizeBytes * 8_000L / durationMs

    private fun group(n: Long): String {
        val s = n.toString()
        val sb = StringBuilder()
        s.forEachIndexed { i, ch ->
            if (i > 0 && (s.length - i) % 3 == 0) sb.append(',')
            sb.append(ch)
        }
        return sb.toString()
    }

    private val AIFF_EXT = setOf("aif", "aiff", "aifc")
}
