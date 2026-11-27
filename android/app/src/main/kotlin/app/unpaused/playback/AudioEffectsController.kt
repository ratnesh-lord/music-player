package app.unpaused.playback

import android.content.Context
import android.content.Intent
import android.media.audiofx.AudioEffect
import android.media.audiofx.BassBoost
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Equalizer
import android.os.Build
import android.util.Log
import app.unpaused.data.settings.AppSettings
import app.unpaused.data.settings.EqEngine
import kotlin.math.sqrt

/** Settings the effect chain needs, derived from [AppSettings] plus the current track's ReplayGain boost. */
data class EffectsConfig(
    val eqEnabled: Boolean,
    val engine: EqEngine,
    val bandsDb: List<Float>,
    val bassDb: Float,
    val trebleDb: Float,
    val normalize: Boolean,
    /** Positive ReplayGain/pre-amp gain that player volume (max 1.0) cannot deliver. */
    val boostDb: Float,
) {
    /** True when nothing needs processing: leave the audio untouched ("pure" path). */
    val isNeutral: Boolean
        get() = (!eqEnabled || (bandsDb.all { it == 0f } && bassDb == 0f && trebleDb == 0f)) && !normalize && boostDb <= 0f

    companion object {
        fun from(s: AppSettings, boostDb: Float) = EffectsConfig(
            eqEnabled = s.eqEnabled,
            engine = s.eqEngine,
            bandsDb = s.eqBandsDb,
            bassDb = s.bassDb,
            trebleDb = s.trebleDb,
            normalize = s.normalize,
            boostDb = boostDb,
        )
    }
}

/**
 * Equalizer, bass/treble, ReplayGain boost and limiter on the player's audio session.
 * Prefers [DynamicsProcessing] (API 28+, multi-band EQ + limiter); falls back to [Equalizer] + [BassBoost].
 * Session effects run in Android's mixer, so they also apply to the hi-res float output path.
 */
class AudioEffectsController(
    private val context: Context,
    private val audioSessionId: Int,
    private val status: PlaybackStatus,
) {
    private var dp: DynamicsProcessing? = null
    private var eq: Equalizer? = null
    private var bass: BassBoost? = null
    private var dpUnavailable = false
    private var externalOpen = false
    private var last: EffectsConfig? = null

    fun apply(config: EffectsConfig) {
        if (config == last) return
        last = config
        if (config.engine == EqEngine.EXTERNAL) {
            releaseInternal()
            openExternal()
            status.setEffects(EffectsUi(engine = "External app", note = "Audio session handed to your equalizer app"))
            return
        }
        closeExternal()
        if (config.isNeutral) {
            dp?.setEnabled(false)
            eq?.setEnabled(false)
            bass?.setEnabled(false)
            status.setEffects(EffectsUi(engine = "Off", note = "No processing (pure path)"))
            return
        }
        if (!dpUnavailable && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                applyDynamics(config)
                status.setEffects(EffectsUi(engine = "DynamicsProcessing", note = note(config)))
                return
            } catch (e: Throwable) {
                Log.w(TAG, "DynamicsProcessing unavailable, falling back to Equalizer", e)
                dpUnavailable = true
                runCatching { dp?.release() }
                dp = null
            }
        }
        try {
            applyLegacy(config)
            val boostNote = if (config.boostDb > 0f) "ReplayGain boost unavailable on this engine; only attenuation is applied" else null
            status.setEffects(EffectsUi(engine = "Equalizer", note = boostNote))
        } catch (e: Throwable) {
            Log.w(TAG, "No audio effects available", e)
            status.setEffects(EffectsUi(engine = "Unavailable", note = "This device offers no audio effects for apps"))
        }
    }

    private fun note(c: EffectsConfig): String = buildList {
        if (c.eqEnabled) add("EQ")
        if (c.boostDb > 0f) add("+%.1f dB ReplayGain".format(c.boostDb))
        if (c.normalize) add("normalisation")
        add("limiter")
    }.joinToString(" · ")

    private fun applyDynamics(c: EffectsConfig) {
        val gains = bandGains(c)
        val processor = dp ?: createDynamics().also { dp = it }
        for (i in 0 until BANDS) {
            processor.setPreEqBandAllChannelsTo(i, DynamicsProcessing.EqBand(true, CUTOFFS[i], gains[i]))
        }
        // Keep headroom for EQ boosts so the limiter rarely has to work.
        val maxBoost = gains.maxOrNull()?.coerceAtLeast(0f) ?: 0f
        processor.setInputGainAllChannelsTo(c.boostDb - maxBoost * 0.5f)
        processor.setMbcBandAllChannelsTo(
            0,
            DynamicsProcessing.MbcBand(
                c.normalize, 20_000f, 10f, 250f, 3f, -24f, 6f, -90f, 1f, 0f, if (c.normalize) 6f else 0f,
            ),
        )
        processor.setLimiterAllChannelsTo(DynamicsProcessing.Limiter(true, true, 0, 1f, 60f, 10f, -1f, 0f))
        processor.setEnabled(true)
    }

    private fun createDynamics(): DynamicsProcessing {
        val config = DynamicsProcessing.Config.Builder(
            DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
            CHANNELS,
            true, BANDS,
            true, 1,
            false, 0,
            true,
        )
            .setPreferredFrameDuration(10f)
            .build()
        return DynamicsProcessing(0, audioSessionId, config)
    }

    private fun applyLegacy(c: EffectsConfig) {
        val e = eq ?: Equalizer(0, audioSessionId).also { eq = it }
        val gains = bandGains(c)
        val range = e.bandLevelRange // millibels [min, max]
        for (b in 0 until e.numberOfBands) {
            val centerHz = e.getCenterFreq(b.toShort()) / 1000f
            val gainDb = interpolate(gains, centerHz)
            val mb = (gainDb * 100).toInt().coerceIn(range[0].toInt(), range[1].toInt())
            e.setBandLevel(b.toShort(), mb.toShort())
        }
        e.setEnabled(c.eqEnabled)
        if (c.bassDb > 0f) {
            val bb = bass ?: BassBoost(0, audioSessionId).also { bass = it }
            if (bb.strengthSupported) bb.setStrength((c.bassDb / AppSettings.EQ_MAX_DB * 1000).toInt().coerceIn(0, 1000).toShort())
            bb.setEnabled(true)
        } else {
            bass?.setEnabled(false)
        }
    }

    /** Ten band gains: the user's EQ plus bass (low shelf) and treble (high shelf) contributions. */
    private fun bandGains(c: EffectsConfig): FloatArray {
        val out = FloatArray(BANDS)
        for (i in 0 until BANDS) {
            val user = if (c.eqEnabled) c.bandsDb.getOrElse(i) { 0f } else 0f
            out[i] = (user + c.bassDb * BASS_WEIGHTS[i] + c.trebleDb * TREBLE_WEIGHTS[i]).coerceIn(-15f, 15f)
        }
        return out
    }

    private fun interpolate(gains: FloatArray, hz: Float): Float {
        val centers = AppSettings.EQ_CENTERS_HZ
        if (hz <= centers.first()) return gains.first()
        if (hz >= centers.last()) return gains.last()
        for (i in 0 until centers.size - 1) {
            if (hz >= centers[i] && hz <= centers[i + 1]) {
                val t = (hz - centers[i]) / (centers[i + 1] - centers[i])
                return gains[i] + (gains[i + 1] - gains[i]) * t
            }
        }
        return 0f
    }

    private fun openExternal() {
        if (externalOpen) return
        externalOpen = true
        context.sendBroadcast(
            Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
                .putExtra(AudioEffect.EXTRA_AUDIO_SESSION, audioSessionId)
                .putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
                .putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC),
        )
    }

    private fun closeExternal() {
        if (!externalOpen) return
        externalOpen = false
        context.sendBroadcast(
            Intent(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
                .putExtra(AudioEffect.EXTRA_AUDIO_SESSION, audioSessionId)
                .putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName),
        )
    }

    private fun releaseInternal() {
        runCatching { dp?.release() }
        runCatching { eq?.release() }
        runCatching { bass?.release() }
        dp = null; eq = null; bass = null
    }

    fun release() {
        releaseInternal()
        closeExternal()
        last = null
    }

    companion object {
        private const val TAG = "UnpausedFx"
        private const val BANDS = AppSettings.EQ_BANDS
        private const val CHANNELS = 2

        /** Upper edge of each band: geometric midpoints between the band centres. */
        private val CUTOFFS: FloatArray = FloatArray(BANDS) { i ->
            val c = AppSettings.EQ_CENTERS_HZ
            if (i == BANDS - 1) 20_000f else sqrt(c[i].toFloat() * c[i + 1])
        }

        private val BASS_WEIGHTS = floatArrayOf(1f, 0.85f, 0.5f, 0.2f, 0f, 0f, 0f, 0f, 0f, 0f)
        private val TREBLE_WEIGHTS = floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0.2f, 0.5f, 0.85f, 1f)
    }
}
