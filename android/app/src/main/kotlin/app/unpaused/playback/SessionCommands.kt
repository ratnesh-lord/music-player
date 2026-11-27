package app.unpaused.playback

import android.os.Bundle
import androidx.media3.session.SessionCommand

/** App-specific commands sent from the UI and the notification to [PlaybackService]. */
object SessionCommands {
    const val TOGGLE_FAVORITE = "app.unpaused.TOGGLE_FAVORITE"
    const val TOGGLE_MIX = "app.unpaused.TOGGLE_MIX"

    /** Args: [ARG_MINUTES] (0 = cancel, -1 = end of current track). */
    const val SLEEP_TIMER = "app.unpaused.SLEEP_TIMER"

    /** Args: [ARG_DEVICE_ID] (-1 = automatic). */
    const val SET_OUTPUT = "app.unpaused.SET_OUTPUT"

    /** Load the saved queue (paused) if the player is empty. Sent by the UI on launch. */
    const val RESTORE_QUEUE = "app.unpaused.RESTORE_QUEUE"

    const val ARG_MINUTES = "minutes"
    const val ARG_DEVICE_ID = "deviceId"

    val toggleFavorite = SessionCommand(TOGGLE_FAVORITE, Bundle.EMPTY)
    val toggleMix = SessionCommand(TOGGLE_MIX, Bundle.EMPTY)
    val sleepTimer = SessionCommand(SLEEP_TIMER, Bundle.EMPTY)
    val setOutput = SessionCommand(SET_OUTPUT, Bundle.EMPTY)
    val restoreQueue = SessionCommand(RESTORE_QUEUE, Bundle.EMPTY)

    val all = listOf(toggleFavorite, toggleMix, sleepTimer, setOutput, restoreQueue)
}
