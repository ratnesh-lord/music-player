package app.unpaused.playback

import androidx.media3.common.Player

/**
 * Single owner of the player volume. The final volume is the product of ReplayGain attenuation,
 * focus ducking and the sleep-timer fade. Call from the player's thread (main).
 */
class VolumeMixer(private val player: Player) {
    private var replayGain = 1f
    private var duck = 1f
    private var sleepFade = 1f

    fun setReplayGain(linear: Float) { replayGain = linear.coerceIn(0f, 1f); apply() }
    fun setDuck(linear: Float) { duck = linear.coerceIn(0f, 1f); apply() }
    fun setSleepFade(linear: Float) { sleepFade = linear.coerceIn(0f, 1f); apply() }

    val current: Float get() = replayGain * duck * sleepFade

    private fun apply() {
        player.volume = current.coerceIn(0f, 1f)
    }
}
