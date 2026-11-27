package app.unpaused.core.aiff

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream

class AiffParserTest {

    /** Builds an AIFF/AIFF-C file in memory. */
    private fun aiff(
        channels: Int = 2,
        bits: Int = 16,
        rate: Double = 44_100.0,
        frames: Int = 10,
        aifc: String? = null,
        ssndFirst: Boolean = false,
        extraChunk: Boolean = true,
        ssndOffset: Int = 0,
    ): ByteArray {
        val bytesPerSample = when (aifc) {
            "fl32", "FL32" -> 4
            "fl64" -> 8
            else -> (bits + 7) / 8
        }
        val data = ByteArray(frames * channels * bytesPerSample) { (it % 251).toByte() }

        val comm = ByteArrayOutputStream().apply {
            write16(channels); write32(frames.toLong()); write16(bits); write(extended(rate))
            if (aifc != null) { write(aifc.toByteArray(Charsets.US_ASCII)); write(byteArrayOf(0, 0)) } // empty pstring + pad
        }.toByteArray()
        val ssnd = ByteArrayOutputStream().apply {
            write32(ssndOffset.toLong()); write32(0); write(ByteArray(ssndOffset)); write(data)
        }.toByteArray()

        val body = ByteArrayOutputStream()
        body.write((if (aifc != null) "AIFC" else "AIFF").toByteArray(Charsets.US_ASCII))
        if (aifc != null) body.chunk("FVER", byteArrayOf(0xA2.toByte(), 0x80.toByte(), 0x51, 0x40))
        if (extraChunk) body.chunk("NAME", "odd".toByteArray()) // odd size → pad byte
        if (ssndFirst) { body.chunk("SSND", ssnd); body.chunk("COMM", comm) } else { body.chunk("COMM", comm); body.chunk("SSND", ssnd) }
        val b = body.toByteArray()
        return ByteArrayOutputStream().apply { write("FORM".toByteArray()); write32(b.size.toLong()); write(b) }.toByteArray()
    }

    @Test fun parses16BitStereo() {
        val bytes = aiff()
        val h = AiffParser.parseHeader(ByteArraySource(bytes))
        assertFalse(h.isAifc)
        assertEquals(2, h.channels)
        assertEquals(16, h.bitsPerSample)
        assertEquals(44_100.0, h.sampleRate, 0.0)
        assertEquals(AiffSampleFormat.PCM_S16_BE, h.format)
        assertEquals(40L, h.dataSize)
        assertEquals(bytes.size - 40L, h.dataOffset)
    }

    @Test fun parsesHiResRatesExactly() {
        for (rate in listOf(8_000.0, 22_050.0, 44_100.0, 48_000.0, 88_200.0, 96_000.0, 176_400.0, 192_000.0, 384_000.0)) {
            val h = AiffParser.parseHeader(ByteArraySource(aiff(bits = 24, rate = rate)))
            assertEquals(rate, h.sampleRate, 0.0)
            assertEquals(AiffSampleFormat.PCM_S24_BE, h.format)
        }
    }

    @Test fun handlesAifcVariants() {
        val cases = mapOf(
            "NONE" to AiffSampleFormat.PCM_S16_BE,
            "twos" to AiffSampleFormat.PCM_S16_BE,
            "sowt" to AiffSampleFormat.PCM_S16_LE,
            "fl32" to AiffSampleFormat.FLOAT32_BE,
            "FL32" to AiffSampleFormat.FLOAT32_BE,
            "fl64" to AiffSampleFormat.FLOAT64_BE,
        )
        for ((compression, expected) in cases) {
            val bits = when (compression) { "fl32", "FL32" -> 32; "fl64" -> 64; else -> 16 }
            val h = AiffParser.parseHeader(ByteArraySource(aiff(bits = bits, aifc = compression)))
            assertTrue(h.isAifc)
            assertEquals(compression, expected, h.format)
        }
    }

    @Test fun rejectsCompressedAifc() {
        try {
            AiffParser.parseHeader(ByteArraySource(aiff(aifc = "ima4")))
            fail("expected exception")
        } catch (e: AiffFormatException) {
            assertTrue(e.message!!.contains("ima4"))
        }
    }

    @Test fun findsCommAfterSsnd() {
        val bytes = aiff(ssndFirst = true)
        val h = AiffParser.parseHeader(ByteArraySource(bytes))
        assertEquals(40L, h.dataSize)
        // Data starts right after FORM(12) + NAME(8+3+1) + SSND header(8) + offset/blocksize(8)
        assertEquals(12L + 12 + 8 + 8, h.dataOffset)
    }

    @Test fun honoursSsndOffset() {
        val h = AiffParser.parseHeader(ByteArraySource(aiff(ssndOffset = 6, extraChunk = false)))
        assertEquals(12L + 8 + 18 + 8 + 8 + 6, h.dataOffset)
        assertEquals(40L, h.dataSize)
    }

    @Test fun oddBitDepthsRoundUpToWholeBytes() {
        assertEquals(AiffSampleFormat.PCM_S24_BE, AiffParser.parseHeader(ByteArraySource(aiff(bits = 20))).format)
        assertEquals(AiffSampleFormat.PCM_S8, AiffParser.parseHeader(ByteArraySource(aiff(bits = 8))).format)
    }

    @Test fun sniffing() {
        assertTrue(AiffParser.sniff(aiff()))
        assertFalse(AiffParser.sniff("RIFF\u0000\u0000\u0000\u0000WAVE".toByteArray()))
    }

    @Test fun extendedFloatRoundTrip() {
        for (v in listOf(1.0, 0.5, 44_100.0, 96_000.0, 12_345.678)) {
            assertEquals(v, AiffParser.extendedToDouble(extended(v), 0), 1e-9)
        }
        assertEquals(0.0, AiffParser.extendedToDouble(ByteArray(10), 0), 0.0)
    }

    @Test fun durationFromFrames() {
        val h = AiffParser.parseHeader(ByteArraySource(aiff(frames = 44_100, channels = 1)))
        assertEquals(1_000_000L, h.durationUs)
    }

    companion object {
        /** Encodes a positive double as an 80-bit IEEE extended (test helper). */
        fun extended(value: Double): ByteArray {
            val out = ByteArray(10)
            if (value == 0.0) return out
            val exp = Math.getExponent(value) // value = m * 2^exp with m in [1, 2)
            val m = value / Math.scalb(1.0, exp)
            val mantissa = java.math.BigDecimal(m).multiply(java.math.BigDecimal(2).pow(63)).toBigInteger()
            val biased = exp + 16383
            out[0] = ((biased shr 8) and 0x7F).toByte()
            out[1] = biased.toByte()
            val mb = mantissa.toByteArray() // big-endian, may carry a leading sign byte
            val last8 = if (mb.size > 8) mb.copyOfRange(mb.size - 8, mb.size) else mb
            System.arraycopy(last8, 0, out, 2 + (8 - last8.size), last8.size)
            return out
        }

        private fun ByteArrayOutputStream.write16(v: Int) { write(v shr 8 and 0xFF); write(v and 0xFF) }
        private fun ByteArrayOutputStream.write32(v: Long) { write16((v shr 16).toInt() and 0xFFFF); write16(v.toInt() and 0xFFFF) }
        private fun ByteArrayOutputStream.chunk(id: String, data: ByteArray) {
            write(id.toByteArray(Charsets.US_ASCII)); write32(data.size.toLong()); write(data)
            if (data.size % 2 == 1) write(0)
        }
    }
}
