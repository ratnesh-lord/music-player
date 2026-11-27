package app.unpaused.playback

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.ParserException
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.Util
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SeekPoint
import androidx.media3.extractor.TrackOutput
import app.unpaused.core.aiff.AiffFormatException
import app.unpaused.core.aiff.AiffHeader
import app.unpaused.core.aiff.AiffParser
import app.unpaused.core.aiff.AiffSampleFormat
import app.unpaused.core.aiff.ByteSource
import java.io.EOFException
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Media3 extractor for AIFF / AIFF-C PCM, which neither Android nor Media3 support out of the box.
 * Header parsing lives in `:core` ([AiffParser]); this class streams the PCM to Media3 as raw audio
 * in its native byte order (Media3's processors convert big-endian PCM, see ToInt16PcmAudioProcessor).
 */
class AiffExtractor : Extractor {

    private lateinit var output: ExtractorOutput
    private lateinit var track: TrackOutput
    private var header: AiffHeader? = null
    private var targetSampleSize = 0
    private var pendingBytes = 0
    private var startTimeUs = 0L
    private var outputFrames = 0L
    private var flipBuffer: ByteArray? = null

    override fun sniff(input: ExtractorInput): Boolean {
        val buf = ByteArray(12)
        return try {
            input.peekFully(buf, 0, 12)
            AiffParser.sniff(buf)
        } catch (e: EOFException) {
            false
        }
    }

    override fun init(output: ExtractorOutput) {
        this.output = output
        track = output.track(0, C.TRACK_TYPE_AUDIO)
        output.endTracks()
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        val h = header ?: return readHeader(input, seekPosition)
        val dataEnd = h.dataOffset + h.dataSize
        if (input.position < h.dataOffset) {
            seekPosition.position = h.dataOffset
            return Extractor.RESULT_SEEK
        }
        val bytesLeft = dataEnd - input.position
        if (bytesLeft <= 0) return Extractor.RESULT_END_OF_INPUT
        val finished = writeSamples(input, h, bytesLeft)
        return if (finished) Extractor.RESULT_END_OF_INPUT else Extractor.RESULT_CONTINUE
    }

    private fun readHeader(input: ExtractorInput, seekPosition: PositionHolder): Int {
        val h = try {
            AiffParser.parseHeader(InputByteSource(input))
        } catch (e: AiffFormatException) {
            throw ParserException.createForMalformedContainer(e.message, e)
        }
        header = h
        val bytesPerFrame = h.bytesPerFrame
        val rate = h.sampleRate.roundToInt()
        targetSampleSize = max(bytesPerFrame, rate * bytesPerFrame / TARGET_SAMPLES_PER_SECOND / bytesPerFrame * bytesPerFrame)
        val bitrate = (rate.toLong() * bytesPerFrame * 8).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val format = Format.Builder()
            .setContainerMimeType(CONTAINER_MIME)
            .setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setAverageBitrate(bitrate)
            .setPeakBitrate(bitrate)
            .setMaxInputSize(targetSampleSize)
            .setChannelCount(h.channels)
            .setSampleRate(rate)
            .setPcmEncoding(pcmEncoding(h.format))
            .build()
        track.format(format)
        val seekMap = AiffSeekMap(h)
        track.durationUs(seekMap.durationUs)
        output.seekMap(seekMap)
        if (h.format == AiffSampleFormat.PCM_S8) flipBuffer = ByteArray(targetSampleSize)
        if (input.position != h.dataOffset) {
            seekPosition.position = h.dataOffset
            return Extractor.RESULT_SEEK
        }
        return Extractor.RESULT_CONTINUE
    }

    /** Writes up to one target-sized sample. Returns true at end of data. */
    private fun writeSamples(input: ExtractorInput, h: AiffHeader, bytesLeftIn: Long): Boolean {
        var bytesLeft = bytesLeftIn
        val flip = flipBuffer
        while (bytesLeft > 0 && pendingBytes < targetSampleSize) {
            val toRead = min((targetSampleSize - pendingBytes).toLong(), bytesLeft).toInt()
            val appended = if (flip != null) {
                // Plain AIFF 8-bit PCM is signed; Media3's 8-bit PCM is unsigned.
                val n = input.read(flip, 0, toRead)
                if (n == C.RESULT_END_OF_INPUT) {
                    C.RESULT_END_OF_INPUT
                } else {
                    for (i in 0 until n) flip[i] = (flip[i].toInt() xor 0x80).toByte()
                    track.sampleData(ParsableByteArray(flip, n), n)
                    n
                }
            } else {
                track.sampleData(input, toRead, true)
            }
            if (appended == C.RESULT_END_OF_INPUT) {
                bytesLeft = 0
            } else {
                pendingBytes += appended
                bytesLeft -= appended
            }
        }
        val bytesPerFrame = h.bytesPerFrame
        val pendingFrames = pendingBytes / bytesPerFrame
        if (pendingFrames > 0) {
            val timeUs = startTimeUs + Util.scaleLargeTimestamp(outputFrames, C.MICROS_PER_SECOND, h.sampleRate.roundToInt().toLong())
            val size = pendingFrames * bytesPerFrame
            val offset = pendingBytes - size
            track.sampleMetadata(timeUs, C.BUFFER_FLAG_KEY_FRAME, size, offset, null)
            outputFrames += pendingFrames
            pendingBytes = offset
        }
        return bytesLeft <= 0
    }

    override fun seek(position: Long, timeUs: Long) {
        if (position == 0L) header = null
        startTimeUs = timeUs
        pendingBytes = 0
        outputFrames = 0
    }

    override fun release() = Unit

    private class InputByteSource(private val input: ExtractorInput) : ByteSource {
        override val position: Long get() = input.position

        override fun readFully(target: ByteArray, offset: Int, length: Int) {
            try {
                input.readFully(target, offset, length)
            } catch (e: EOFException) {
                throw AiffFormatException("Unexpected end of file")
            }
        }

        override fun skip(count: Long) {
            var left = count
            try {
                while (left > 0) {
                    val n = min(left, Int.MAX_VALUE.toLong()).toInt()
                    input.skipFully(n)
                    left -= n
                }
            } catch (e: EOFException) {
                throw AiffFormatException("Unexpected end of file")
            }
        }
    }

    private class AiffSeekMap(private val h: AiffHeader) : SeekMap {
        private val frames = h.dataSize / h.bytesPerFrame
        private val rate = h.sampleRate

        override fun isSeekable() = frames > 0

        override fun getDurationUs() = h.durationUs

        override fun getSeekPoints(timeUs: Long): SeekMap.SeekPoints {
            if (frames <= 0) return SeekMap.SeekPoints(SeekPoint(0, h.dataOffset))
            val frame = (timeUs * rate / C.MICROS_PER_SECOND).toLong().coerceIn(0, frames - 1)
            val first = point(frame)
            if (first.timeUs >= timeUs || frame == frames - 1) return SeekMap.SeekPoints(first)
            return SeekMap.SeekPoints(first, point(frame + 1))
        }

        private fun point(frame: Long) =
            SeekPoint((frame * C.MICROS_PER_SECOND / rate).toLong(), h.dataOffset + frame * h.bytesPerFrame)
    }

    companion object {
        const val CONTAINER_MIME = "audio/x-aiff"
        private const val TARGET_SAMPLES_PER_SECOND = 10

        fun pcmEncoding(format: AiffSampleFormat): Int = when (format) {
            AiffSampleFormat.PCM_S8, AiffSampleFormat.PCM_U8 -> C.ENCODING_PCM_8BIT
            AiffSampleFormat.PCM_S16_BE -> C.ENCODING_PCM_16BIT_BIG_ENDIAN
            AiffSampleFormat.PCM_S16_LE -> C.ENCODING_PCM_16BIT
            AiffSampleFormat.PCM_S24_BE -> C.ENCODING_PCM_24BIT_BIG_ENDIAN
            AiffSampleFormat.PCM_S24_LE -> C.ENCODING_PCM_24BIT
            AiffSampleFormat.PCM_S32_BE -> C.ENCODING_PCM_32BIT_BIG_ENDIAN
            AiffSampleFormat.PCM_S32_LE -> C.ENCODING_PCM_32BIT
            AiffSampleFormat.FLOAT32_BE -> C.ENCODING_PCM_FLOAT_BIG_ENDIAN
            AiffSampleFormat.FLOAT64_BE -> C.ENCODING_PCM_DOUBLE_BIG_ENDIAN
        }
    }
}
