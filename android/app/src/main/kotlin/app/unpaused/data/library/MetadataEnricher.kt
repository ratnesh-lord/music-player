package app.unpaused.data.library

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.inspector.MetadataRetriever
import app.unpaused.core.audio.AudioQuality
import app.unpaused.core.audio.ReplayGain
import app.unpaused.core.audio.ReplayGainInfo
import app.unpaused.playback.UnpausedExtractorsFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

data class Enrichment(
    val codec: String,
    val sampleRate: Int?,
    val bitDepth: Int?,
    val channels: Int?,
    val bitrate: Long?,
    val replayGain: ReplayGainInfo,
    val lyrics: String?,
    val durationMs: Long,
)

/**
 * Reads technical facts (codec, sample rate, bit depth, bitrate) and tags Android's MediaStore doesn't
 * expose (ReplayGain, embedded lyrics) using Media3's own extractors, the same ones used for playback.
 */
class MetadataEnricher(private val context: Context) {

    private val sourceFactory by lazy { DefaultMediaSourceFactory(context, UnpausedExtractorsFactory()) }

    suspend fun read(uri: String, fileName: String, sizeBytes: Long): Enrichment? = withContext(Dispatchers.IO) {
        runCatching {
            MetadataRetriever.Builder(context, MediaItem.fromUri(uri))
                .setMediaSourceFactory(sourceFactory)
                .build()
                .use { retriever ->
                    val groups = withTimeout(TIMEOUT_MS) { retriever.retrieveTrackGroups().await() }
                    val durationUs = runCatching { withTimeout(TIMEOUT_MS) { retriever.retrieveDurationUs().await() } }.getOrNull()
                    var audio: Format? = null
                    for (i in 0 until groups.length) {
                        val f = groups.get(i).getFormat(0)
                        if (MimeTypes.isAudio(f.sampleMimeType)) { audio = f; break }
                    }
                    val format = audio ?: return@use null
                    val tags = TagReader.read(format.metadata)
                    val durationMs = durationUs?.takeIf { it != C.TIME_UNSET && it > 0 }?.div(1000) ?: 0L
                    val codec = AudioQuality.codecFor(format.sampleMimeType, format.containerMimeType, fileName)
                    val headerBitrate = listOf(format.averageBitrate, format.bitrate, format.peakBitrate).firstOrNull { it > 0 }?.toLong()
                    Enrichment(
                        codec = codec.name,
                        sampleRate = format.sampleRate.takeIf { it > 0 },
                        bitDepth = if (codec.lossless) bitDepthOf(format.pcmEncoding) else null,
                        channels = format.channelCount.takeIf { it > 0 },
                        bitrate = headerBitrate ?: AudioQuality.averageBitrate(sizeBytes, durationMs),
                        replayGain = ReplayGain.fromTags(tags.values),
                        lyrics = tags.lyrics,
                        durationMs = durationMs,
                    )
                }
        }.getOrNull()
    }

    companion object {
        private const val TIMEOUT_MS = 15_000L

        fun bitDepthOf(pcmEncoding: Int): Int? = when (pcmEncoding) {
            C.ENCODING_PCM_8BIT -> 8
            C.ENCODING_PCM_16BIT, C.ENCODING_PCM_16BIT_BIG_ENDIAN -> 16
            C.ENCODING_PCM_24BIT, C.ENCODING_PCM_24BIT_BIG_ENDIAN -> 24
            C.ENCODING_PCM_32BIT, C.ENCODING_PCM_32BIT_BIG_ENDIAN, C.ENCODING_PCM_FLOAT, C.ENCODING_PCM_FLOAT_BIG_ENDIAN -> 32
            C.ENCODING_PCM_DOUBLE, C.ENCODING_PCM_DOUBLE_BIG_ENDIAN -> 64
            else -> null
        }
    }
}
