package app.unpaused.playback

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player

/**
 * The player the MediaSession exposes. Play/pause requests from the UI, notification, lock screen,
 * headset and Bluetooth go through the focus policy instead of straight to ExoPlayer, so the policy
 * always knows the user's intent and decides whether (and how) to take audio focus.
 */
class FocusAwarePlayer(player: Player) : ForwardingPlayer(player) {

    var focus: AudioFocusController? = null

    override fun play() {
        val f = focus
        if (f == null) super.play() else f.onUserPlay()
    }

    override fun pause() {
        val f = focus
        if (f == null) super.pause() else f.onUserPause()
    }

    override fun setPlayWhenReady(playWhenReady: Boolean) {
        if (playWhenReady) play() else pause()
    }

    override fun stop() {
        super.stop()
        focus?.onStopped()
    }
}
