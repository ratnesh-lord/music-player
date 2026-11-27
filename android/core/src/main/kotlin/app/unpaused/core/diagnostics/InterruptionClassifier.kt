package app.unpaused.core.diagnostics

import app.unpaused.core.focus.EventOrigin
import app.unpaused.core.focus.FocusEvent
import app.unpaused.core.focus.FocusEventType

/** What a diagnostics entry means for the person reading the log. */
enum class Verdict {
    /** Android (or another app) asked us to stop; pausing was correct. */
    EXPECTED_ANDROID,

    /** You did it (button, headset, notification). */
    USER,

    /** This app decided (auto-resume, Mix mode, etc.). */
    APP_DECISION,

    /** Playback stopped and nothing explains it: likely an app bug or the system killing us. */
    UNEXPECTED,

    /** Informational only. */
    INFO,
}

object InterruptionClassifier {

    fun verdict(event: FocusEvent): Verdict = when (event.type) {
        FocusEventType.FOCUS_LOST,
        FocusEventType.FOCUS_LOST_TRANSIENT,
        FocusEventType.FOCUS_DUCKED,
        FocusEventType.CALL_STARTED,
        FocusEventType.NOISY,
        FocusEventType.FOCUS_DENIED,
        FocusEventType.FOCUS_DELAYED,
        -> Verdict.EXPECTED_ANDROID

        FocusEventType.USER_PLAY, FocusEventType.USER_PAUSE, FocusEventType.STOPPED, FocusEventType.MODE_SWITCHED -> Verdict.USER

        FocusEventType.AUTO_RESUMED,
        FocusEventType.AUTO_RESUME_WATCHING,
        FocusEventType.AUTO_RESUME_GAVE_UP,
        FocusEventType.DUCKED_INSTEAD_OF_PAUSE,
        FocusEventType.IGNORED,
        -> if (event.origin == EventOrigin.ANDROID) Verdict.EXPECTED_ANDROID else Verdict.APP_DECISION

        FocusEventType.FOCUS_GAINED,
        FocusEventType.FOCUS_GRANTED,
        FocusEventType.FOCUS_REQUESTED,
        FocusEventType.CALL_ENDED,
        FocusEventType.PAUSED,
        FocusEventType.RESUMED,
        -> Verdict.INFO
    }

    /**
     * A pause the player reported is "explained" if a cause was logged within [windowMs] before it:
     * a user pause, a focus loss, a call, headphones unplugging, a sleep timer or the end of the queue.
     */
    fun isPauseExplained(pauseAtMs: Long, recent: List<FocusEvent>, windowMs: Long = 2_000): Boolean =
        recent.any { e ->
            e.atMs in (pauseAtMs - windowMs)..(pauseAtMs + 250) && e.type in PAUSE_CAUSES
        }

    /** Short explanation shown in the diagnostics list. */
    fun explain(event: FocusEvent): String = when (event.type) {
        FocusEventType.FOCUS_LOST -> "Another app took audio focus permanently (a game, video or app sound). Pausing is standard Android behaviour."
        FocusEventType.FOCUS_LOST_TRANSIENT -> "Another app needed audio briefly (assistant, voice note, call). Music resumes when it's done."
        FocusEventType.FOCUS_DUCKED -> "Another app asked us to lower the volume (e.g. navigation)."
        FocusEventType.CALL_STARTED -> "A call or voice chat started (${event.detail})."
        FocusEventType.CALL_ENDED -> "The call ended."
        FocusEventType.NOISY -> "Headphones disconnected. Pausing prevents music blasting from the speaker."
        FocusEventType.FOCUS_DENIED -> "Android refused audio focus (usually during a call)."
        FocusEventType.FOCUS_DELAYED -> "Android will give us audio focus when the current call ends."
        FocusEventType.AUTO_RESUME_WATCHING -> "Smart mode is waiting for the other app to go quiet. ${event.detail}"
        FocusEventType.AUTO_RESUMED -> "Smart mode resumed playback."
        FocusEventType.AUTO_RESUME_GAVE_UP -> "Smart mode stopped waiting: ${event.detail}."
        FocusEventType.DUCKED_INSTEAD_OF_PAUSE -> "Lowered the volume instead of pausing (your setting)."
        FocusEventType.IGNORED -> event.detail
        FocusEventType.USER_PLAY -> "Play pressed."
        FocusEventType.USER_PAUSE -> "Pause pressed."
        FocusEventType.STOPPED -> "Playback stopped."
        FocusEventType.MODE_SWITCHED -> "Focus mode changed: ${event.detail}."
        FocusEventType.FOCUS_GAINED -> "Audio focus returned."
        FocusEventType.FOCUS_GRANTED -> "Audio focus granted."
        FocusEventType.FOCUS_REQUESTED -> "Requested audio focus (${event.detail})."
        FocusEventType.PAUSED -> "Paused. ${event.detail}"
        FocusEventType.RESUMED -> "Resumed. ${event.detail}"
    }

    private val PAUSE_CAUSES = setOf(
        FocusEventType.USER_PAUSE,
        FocusEventType.STOPPED,
        FocusEventType.FOCUS_LOST,
        FocusEventType.FOCUS_LOST_TRANSIENT,
        FocusEventType.CALL_STARTED,
        FocusEventType.NOISY,
        FocusEventType.FOCUS_DENIED,
        FocusEventType.FOCUS_DELAYED,
        FocusEventType.PAUSED,
    )
}
