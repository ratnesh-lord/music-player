package app.unpaused.data.settings

import app.unpaused.core.audio.ReplayGainMode
import app.unpaused.core.audio.ReplayGainSettings
import app.unpaused.core.focus.FocusMode
import app.unpaused.core.focus.FocusSettings

enum class ThemeMode { DARK, LIGHT, SYSTEM }

enum class EqEngine {
    /** This app's DynamicsProcessing/Equalizer on its own audio session. */
    BUILT_IN,

    /** Hand the session to a system or third-party equalizer app. */
    EXTERNAL,
}

/** Every user-facing setting, persisted with DataStore. Defaults are the recommended values. */
data class AppSettings(
    // Playback & interruptions
    val focusMode: FocusMode = FocusMode.SMART,
    val autoResumeWindowMin: Int = 5,
    val quietPeriodSec: Int = 3,
    val duckOnTransientLoss: Boolean = false,
    val pauseForVoiceChat: Boolean = true,
    val resumeAfterInterruption: Boolean = true,
    val resumeOnHeadsetConnect: Boolean = false,
    /** Minutes the foreground service stays alive after a pause (Media3 caps this at 10). */
    val keepAliveAfterPauseMin: Int = 10,
    val restoreQueueOnLaunch: Boolean = true,

    // Audio
    val hiResOutput: Boolean = true,
    val replayGainMode: ReplayGainMode = ReplayGainMode.TRACK,
    val replayGainPreampDb: Float = 0f,
    val replayGainUntaggedDb: Float = 0f,
    val replayGainPreventClipping: Boolean = true,
    val eqEnabled: Boolean = false,
    val eqEngine: EqEngine = EqEngine.BUILT_IN,
    val eqBandsDb: List<Float> = List(EQ_BANDS) { 0f },
    val bassDb: Float = 0f,
    val trebleDb: Float = 0f,
    val normalize: Boolean = false,
    val skipSilence: Boolean = false,

    // Library
    val useMediaStore: Boolean = true,
    val minTrackSeconds: Int = 30,

    // Appearance
    val themeMode: ThemeMode = ThemeMode.DARK,

    // Sources
    val radioEnabled: Boolean = true,

    // Sync (optional, user-supplied project). The publishable key is designed to be public.
    val syncEnabled: Boolean = false,
    val supabaseUrl: String = "",
    val supabasePublishableKey: String = "",
    val syncEmail: String = "",
    /** Also sync library metadata (title/artist/album/duration/codec — never files or paths). Off by default. */
    val syncLibrary: Boolean = false,
    /** Mode the Mix toggle returns to. */
    val lastNonMixMode: FocusMode = FocusMode.SMART,
) {
    fun focusSettings() = FocusSettings(
        mode = focusMode,
        autoResumeWindowMs = autoResumeWindowMin.coerceIn(0, 10) * 60_000L,
        quietPeriodMs = quietPeriodSec.coerceIn(1, 30) * 1_000L,
        duckOnTransientLoss = duckOnTransientLoss,
        pauseForVoiceChat = pauseForVoiceChat,
        resumeAfterInterruption = resumeAfterInterruption,
    ).sanitized()

    fun replayGainSettings() = ReplayGainSettings(
        mode = replayGainMode,
        preampDb = replayGainPreampDb,
        untaggedPreampDb = replayGainUntaggedDb,
        preventClipping = replayGainPreventClipping,
    )

    companion object {
        const val EQ_BANDS = 10
        val EQ_CENTERS_HZ = intArrayOf(31, 62, 125, 250, 500, 1_000, 2_000, 4_000, 8_000, 16_000)
        const val EQ_MIN_DB = -12f
        const val EQ_MAX_DB = 12f
    }
}
