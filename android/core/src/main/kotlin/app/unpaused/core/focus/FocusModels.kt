package app.unpaused.core.focus

/** How playback reacts when other apps play audio. See docs/research/01-android-interruptions.md §4. */
enum class FocusMode {
    /** Android's guidance: pause on permanent loss and wait for the user. */
    STANDARD,

    /** Hold focus, but resume automatically once the interrupting app goes quiet (within a window). */
    SMART,

    /** Never hold audio focus, so other apps cannot pause or fade us. Calls still pause. */
    MIX,
}

data class FocusSettings(
    val mode: FocusMode = FocusMode.SMART,
    /** Smart mode: how long after a permanent loss we keep waiting for silence. Clamped to [MAX_AUTO_RESUME_WINDOW_MS]. */
    val autoResumeWindowMs: Long = DEFAULT_AUTO_RESUME_WINDOW_MS,
    /** Smart mode: other media must have been silent for this long before we resume. */
    val quietPeriodMs: Long = DEFAULT_QUIET_PERIOD_MS,
    /** Lower the volume instead of pausing on transient losses (never during calls). */
    val duckOnTransientLoss: Boolean = false,
    /** Volume multiplier used while ducked. */
    val duckVolume: Float = DEFAULT_DUCK_VOLUME,
    /** Pause while audio mode is IN_COMMUNICATION (internet calls, some games' voice chat). Phone calls always pause. */
    val pauseForVoiceChat: Boolean = true,
    /** Resume after a call or transient interruption ends. */
    val resumeAfterInterruption: Boolean = true,
) {
    /** Returns a copy with every value inside its allowed range. */
    fun sanitized(): FocusSettings = copy(
        autoResumeWindowMs = autoResumeWindowMs.coerceIn(0L, MAX_AUTO_RESUME_WINDOW_MS),
        quietPeriodMs = quietPeriodMs.coerceIn(MIN_QUIET_PERIOD_MS, MAX_QUIET_PERIOD_MS),
        duckVolume = duckVolume.coerceIn(0.05f, 1f),
    )

    companion object {
        /** Media3 keeps the foreground service for at most 10 minutes after a pause. */
        const val MAX_AUTO_RESUME_WINDOW_MS: Long = 600_000L
        const val DEFAULT_AUTO_RESUME_WINDOW_MS: Long = 300_000L
        const val DEFAULT_QUIET_PERIOD_MS: Long = 3_000L
        const val MIN_QUIET_PERIOD_MS: Long = 1_000L
        const val MAX_QUIET_PERIOD_MS: Long = 30_000L
        const val DEFAULT_DUCK_VOLUME: Float = 0.2f

        /** Polling interval while watching for other audio to stop. */
        const val CHECK_INTERVAL_MS: Long = 1_000L

        /** After a call ends, how long we wait for Android to hand focus back before asking for it. */
        const val AFTER_CALL_FALLBACK_MS: Long = 3_000L
    }
}

/** Platform-neutral mirror of AudioManager.AUDIOFOCUS_* changes. */
enum class FocusChange { GAIN, LOSS, LOSS_TRANSIENT, LOSS_TRANSIENT_CAN_DUCK }

/** Platform-neutral mirror of the focus gain types we request. */
enum class FocusRequestType { GAIN, TRANSIENT_MAY_DUCK }

enum class FocusRequestResult { GRANTED, DELAYED, FAILED }

/** Platform-neutral mirror of AudioManager.MODE_*. */
enum class AudioMode { NORMAL, RINGTONE, IN_CALL, IN_COMMUNICATION, CALL_SCREENING, OTHER }

sealed interface FocusInput {
    val atMs: Long

    data class UserPlay(override val atMs: Long) : FocusInput
    data class UserPause(override val atMs: Long) : FocusInput

    /** Player stopped, ended or went idle: release focus. */
    data class Stopped(override val atMs: Long) : FocusInput
    data class FocusChanged(val change: FocusChange, override val atMs: Long) : FocusInput
    data class FocusRequestCompleted(
        val type: FocusRequestType,
        val result: FocusRequestResult,
        override val atMs: Long,
    ) : FocusInput

    data class AudioModeChanged(val mode: AudioMode, override val atMs: Long) : FocusInput

    /** Answer to a [FocusCommand.ScheduleCheck]: is any *other* media currently audible? */
    data class OtherAudioCheck(val otherAudioActive: Boolean, override val atMs: Long) : FocusInput

    /** Headphones unplugged / Bluetooth audio disconnected. */
    data class Noisy(override val atMs: Long) : FocusInput
    data class SettingsChanged(val settings: FocusSettings, override val atMs: Long) : FocusInput
}

enum class PauseReason { USER, FOCUS_LOSS, TRANSIENT_FOCUS_LOSS, CALL, NOISY, FOCUS_DENIED, STOPPED }

enum class PlayReason { USER, FOCUS_REGAINED, AUTO_RESUME, CALL_ENDED, MODE_CHANGED }

sealed interface FocusCommand {
    data class RequestFocus(val type: FocusRequestType) : FocusCommand
    data object AbandonFocus : FocusCommand
    data class Play(val reason: PlayReason) : FocusCommand
    data class Pause(val reason: PauseReason) : FocusCommand

    /** Volume multiplier from focus handling only; 1f means "not ducked". */
    data class SetDuck(val volume: Float) : FocusCommand

    /** Ask the adapter to send a [FocusInput.OtherAudioCheck] after [delayMs]. Replaces any pending check. */
    data class ScheduleCheck(val delayMs: Long) : FocusCommand
    data object CancelCheck : FocusCommand
    data class Log(val event: FocusEvent) : FocusCommand
}

/** Who caused an event. Used by diagnostics to separate Android behaviour from app decisions and bugs. */
enum class EventOrigin { USER, ANDROID, APP }

enum class FocusEventType {
    USER_PLAY,
    USER_PAUSE,
    STOPPED,
    FOCUS_REQUESTED,
    FOCUS_GRANTED,
    FOCUS_DELAYED,
    FOCUS_DENIED,
    FOCUS_GAINED,
    FOCUS_LOST,
    FOCUS_LOST_TRANSIENT,
    FOCUS_DUCKED,
    PAUSED,
    RESUMED,
    DUCKED_INSTEAD_OF_PAUSE,
    AUTO_RESUME_WATCHING,
    AUTO_RESUMED,
    AUTO_RESUME_GAVE_UP,
    CALL_STARTED,
    CALL_ENDED,
    NOISY,
    MODE_SWITCHED,
    IGNORED,
}

data class FocusEvent(
    val type: FocusEventType,
    val atMs: Long,
    val mode: FocusMode,
    val origin: EventOrigin,
    val detail: String = "",
)
