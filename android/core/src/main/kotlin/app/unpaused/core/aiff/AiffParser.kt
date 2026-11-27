package app.unpaused.core.aiff

import kotlin.math.pow

class AiffFormatException(message: String) : Exception(message)

/** Sample layouts we can hand to Media3 as raw PCM. */
enum class AiffSampleFormat(val bytesPerSample: Int) {
    /** Plain AIFF 8-bit is signed; Media3's 8-bit PCM is unsigned, so the extractor flips the sign bit. */
    PCM_S8(1),
    PCM_U8(1),
    PCM_S16_BE(2),
    PCM_S16_LE(2),
    PCM_S24_BE(3),
    PCM_S24_LE(3),
    PCM_S32_BE(4),
    PCM_S32_LE(4),
    FLOAT32_BE(4),
    FLOAT64_BE(8),
}

data class AiffHeader(
    val isAifc: Boolean,
    val channels: Int,
    val sampleFrames: Long,
    /** Declared sample size; may be < 8 * bytesPerSample (e.g. 20-bit stored in 3 bytes). */
    val bitsPerSample: Int,
    val sampleRate: Double,
    val compressionType: String,
    val format: AiffSampleFormat,
    /** Absolute offset of the first audio byte. */
    val dataOffset: Long,
    /** Number of audio bytes (whole frames only). */
    val dataSize: Long,
) {
    val bytesPerFrame: Int get() = channels * format.bytesPerSample
    val durationUs: Long get() = if (sampleRate <= 0) 0 else (dataSize / bytesPerFrame * 1_000_000.0 / sampleRate).toLong()
}

/** Minimal sequential reader so the parser runs both on Media3's ExtractorInput and on byte arrays in tests. */
interface ByteSource {
    val position: Long
    fun readFully(target: ByteArray, offset: Int, length: Int)
    fun skip(count: Long)
}

class ByteArraySource(private val data: ByteArray) : ByteSource {
    override var position: Long = 0
        private set

    override fun readFully(target: ByteArray, offset: Int, length: Int) {
        if (position + length > data.size) throw AiffFormatException("Unexpected end of file")
        System.arraycopy(data, position.toInt(), target, offset, length)
        position += length
    }

    override fun skip(count: Long) {
        if (position + count > data.size) throw AiffFormatException("Unexpected end of file")
        position += count
    }
}

/**
 * Parses AIFF and AIFF-C headers (Apple "Audio Interchange File Format", 1989; AIFF-C draft 1991).
 * Supports uncompressed PCM and the common AIFF-C PCM variants (twos, sowt, in24, in32, 42ni, 23ni,
 * raw, fl32, fl64). Compressed AIFF-C (ima4, ulaw, alaw, MACE) is rejected with a clear message.
 */
object AiffParser {

    fun sniff(header: ByteArray): Boolean =
        header.size >= 12 && fourCc(header, 0) == "FORM" && fourCc(header, 8).let { it == "AIFF" || it == "AIFC" }

    fun parseHeader(src: ByteSource): AiffHeader {
        val buf = ByteArray(12)
        src.readFully(buf, 0, 12)
        if (!sniff(buf)) throw AiffFormatException("Not an AIFF file")
        val isAifc = fourCc(buf, 8) == "AIFC"

        var comm: Comm? = null
        var ssndOffset: Long? = null
        var ssndSize: Long? = null

        val chunkHeader = ByteArray(8)
        while (comm == null || ssndOffset == null) {
            try {
                src.readFully(chunkHeader, 0, 8)
            } catch (e: AiffFormatException) {
                break
            }
            val id = fourCc(chunkHeader, 0)
            val size = u32(chunkHeader, 4)
            val padded = size + (size and 1L)
            when (id) {
                "COMM" -> {
                    if (size < 18 || size > 4096) throw AiffFormatException("Bad COMM chunk size $size")
                    val c = ByteArray(size.toInt())
                    src.readFully(c, 0, c.size)
                    if (padded > size) src.skip(1)
                    comm = parseComm(c, isAifc)
                }

                "SSND" -> {
                    if (size < 8) throw AiffFormatException("Bad SSND chunk size $size")
                    val s = ByteArray(8)
                    src.readFully(s, 0, 8)
                    val offset = u32(s, 0)
                    ssndOffset = src.position + offset
                    ssndSize = size - 8 - offset
                    if (comm == null) {
                        // Rare layout: sound data before COMM. Skip the data and keep looking.
                        src.skip(padded - 8)
                    }
                }

                else -> src.skip(padded)
            }
        }

        val c = comm ?: throw AiffFormatException("Missing COMM chunk")
        val dataOffset = ssndOffset ?: throw AiffFormatException("Missing SSND chunk")
        val format = formatFor(c)
        val bytesPerFrame = c.channels * format.bytesPerSample
        val declared = c.sampleFrames * bytesPerFrame
        // Some writers leave the SSND size as 0 when streaming; fall back to the COMM frame count.
        val available = ssndSize?.takeIf { it > 0 } ?: declared
        val size = minOf(declared, available) / bytesPerFrame * bytesPerFrame
        return AiffHeader(
            isAifc = isAifc,
            channels = c.channels,
            sampleFrames = c.sampleFrames,
            bitsPerSample = c.bits,
            sampleRate = c.sampleRate,
            compressionType = c.compression,
            format = format,
            dataOffset = dataOffset,
            dataSize = size,
        )
    }

    /** IEEE 754 80-bit extended precision (big-endian) to Double. */
    fun extendedToDouble(b: ByteArray, off: Int): Double {
        val exponentAndSign = ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)
        val negative = exponentAndSign and 0x8000 != 0
        val exponent = exponentAndSign and 0x7FFF
        var mantissa = 0L
        for (i in 0 until 8) mantissa = (mantissa shl 8) or (b[off + 2 + i].toLong() and 0xFF)
        if (exponent == 0 && mantissa == 0L) return 0.0
        if (exponent == 0x7FFF) return Double.NaN
        // Mantissa has an explicit integer bit; interpret it as an unsigned 64-bit integer.
        val m = if (mantissa < 0) (mantissa ushr 1).toDouble() * 2.0 + (mantissa and 1L) else mantissa.toDouble()
        val value = m * 2.0.pow(exponent - 16383 - 63)
        return if (negative) -value else value
    }

    private class Comm(val channels: Int, val sampleFrames: Long, val bits: Int, val sampleRate: Double, val compression: String)

    private fun parseComm(c: ByteArray, isAifc: Boolean): Comm {
        val channels = s16(c, 0)
        val frames = u32(c, 2)
        val bits = s16(c, 6)
        val rate = extendedToDouble(c, 8)
        val compression = if (isAifc && c.size >= 22) fourCc(c, 18) else "NONE"
        if (channels !in 1..32) throw AiffFormatException("Unsupported channel count $channels")
        if (bits !in 1..64) throw AiffFormatException("Unsupported sample size $bits")
        if (!(rate > 0 && rate <= 1_536_000)) throw AiffFormatException("Unsupported sample rate $rate")
        return Comm(channels, frames, bits, rate, compression)
    }

    private fun formatFor(c: Comm): AiffSampleFormat {
        val bytes = (c.bits + 7) / 8
        fun bigEndian() = when (bytes) {
            1 -> AiffSampleFormat.PCM_S8
            2 -> AiffSampleFormat.PCM_S16_BE
            3 -> AiffSampleFormat.PCM_S24_BE
            4 -> AiffSampleFormat.PCM_S32_BE
            else -> throw AiffFormatException("Unsupported PCM sample size ${c.bits}")
        }
        return when (c.compression) {
            "NONE", "twos" -> bigEndian()
            "sowt" -> when (bytes) {
                1 -> AiffSampleFormat.PCM_S8
                2 -> AiffSampleFormat.PCM_S16_LE
                3 -> AiffSampleFormat.PCM_S24_LE
                4 -> AiffSampleFormat.PCM_S32_LE
                else -> throw AiffFormatException("Unsupported sowt sample size ${c.bits}")
            }
            "in24" -> AiffSampleFormat.PCM_S24_BE
            "in32" -> AiffSampleFormat.PCM_S32_BE
            "42ni" -> AiffSampleFormat.PCM_S24_LE
            "23ni" -> AiffSampleFormat.PCM_S32_LE
            "raw " -> AiffSampleFormat.PCM_U8
            "fl32", "FL32" -> AiffSampleFormat.FLOAT32_BE
            "fl64", "FL64" -> AiffSampleFormat.FLOAT64_BE
            else -> throw AiffFormatException("Compressed AIFF-C (${c.compression.trim()}) is not supported")
        }
    }

    private fun fourCc(b: ByteArray, off: Int) = String(CharArray(4) { (b[off + it].toInt() and 0xFF).toChar() })

    private fun u32(b: ByteArray, off: Int): Long =
        ((b[off].toLong() and 0xFF) shl 24) or ((b[off + 1].toLong() and 0xFF) shl 16) or
            ((b[off + 2].toLong() and 0xFF) shl 8) or (b[off + 3].toLong() and 0xFF)

    private fun s16(b: ByteArray, off: Int): Int = ((b[off].toInt() shl 8) or (b[off + 1].toInt() and 0xFF)).toShort().toInt()
}
