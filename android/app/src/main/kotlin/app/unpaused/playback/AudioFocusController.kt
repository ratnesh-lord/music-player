package app.unpaused.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.media3.common.Player
import app.unpaused.core.focus.AudioMode
import app.unpaused.core.focus.FocusChange
import app.unpaused.core.focus.FocusCommand
import app.unpaused.core.focus.FocusHold
import app.unpaused.core.focus.FocusInput
import app.unpaused.core.focus.FocusMode
import app.unpaused.core.focus.FocusPolicy
import app.unpaused.core.focus.FocusRequestResult
import app.unpaused.core.focus.FocusRequestType
import app.unpaused.core.focus.FocusSettings
import app.unpaused.core.focus.FocusState
import app.unpaused.core.focus.Suspension
import app.unpaused.data.diagnostics.DiagnosticsLog
import java.util.concurrent.Executor

/**
 * Android adapter around the pure [FocusPolicy]: translates AudioManager callbacks into policy
 * inputs and executes the policy's commands on [player] (the raw ExoPlayer, not the forwarding
 * wrapper). Everything runs on the main thread.
 */
class AudioFocusController(
    context: Context,
    private val player: Player,
    private val volume: VolumeMixer,
    private val diagnostics: DiagnosticsLog,
    private val status: PlaybackStatus,
    settings: FocusSettings,
    /** Wall-clock source; injectable so tests can drive time. */
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val mainExecutor = Executor { handler.post(it) }
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    private var state: FocusState = FocusPolicy.initial(settings)
    private var activeRequest: AudioFocusRequest? = null
    private var released = false

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        val mapped = when (change) {
            AudioManager.AUDIOFOCUS_GAIN, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE,
            -> FocusChange.GAIN
            AudioManager.AUDIOFOCUS_LOSS -> FocusChange.LOSS
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> FocusChange.LOSS_TRANSIENT
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> FocusChange.LOSS_TRANSIENT_CAN_DUCK
            else -> null
        }
        if (mapped != null) dispatch(FocusInput.FocusChanged(mapped, now()))
    }

    private val checkRunnable = Runnable {
        dispatch(FocusInput.OtherAudioCheck(otherAudioActive(), now()))
    }

    private var modeListener: Any? = null
    private val modePoll = object : Runnable {
        override fun run() {
            onAudioModeRaw(audioManager.mode)
            if (!released) handler.postDelayed(this, MODE_POLL_MS)
        }
    }

    val currentState: FocusState get() = state

    fun start() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val l = AudioManager.OnModeChangedListener { mode -> onAudioModeRaw(mode) }
            audioManager.addOnModeChangedListener(mainExecutor, l)
            modeListener = l
        } else {
            handler.postDelayed(modePoll, MODE_POLL_MS)
        }
        onAudioModeRaw(audioManager.mode)
        publish()
    }

    fun release() {
        released = true
        handler.removeCallbacksAndMessages(null)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (modeListener as? AudioManager.OnModeChangedListener)?.let { audioManager.removeOnModeChangedListener(it) }
        }
        abandon()
    }

    fun onUserPlay() = dispatch(FocusInput.UserPlay(now()))
    fun onUserPause() = dispatch(FocusInput.UserPause(now()))
    fun onStopped() = dispatch(FocusInput.Stopped(now()))
    fun onNoisy() = dispatch(FocusInput.Noisy(now()))
    fun updateSettings(settings: FocusSettings) = dispatch(FocusInput.SettingsChanged(settings, now()))

    private fun onAudioModeRaw(mode: Int) {
        val mapped = when (mode) {
            AudioManager.MODE_NORMAL -> AudioMode.NORMAL
            AudioManager.MODE_RINGTONE -> AudioMode.RINGTONE
            AudioManager.MODE_IN_CALL -> AudioMode.IN_CALL
            AudioManager.MODE_IN_COMMUNICATION -> AudioMode.IN_COMMUNICATION
            MODE_CALL_SCREENING -> AudioMode.CALL_SCREENING
            MODE_CALL_REDIRECT -> AudioMode.IN_CALL
            MODE_COMMUNICATION_REDIRECT -> AudioMode.IN_COMMUNICATION
            else -> AudioMode.OTHER
        }
        if (mapped != state.audioMode) dispatch(FocusInput.AudioModeChanged(mapped, now()))
    }

    private fun dispatch(input: FocusInput) {
        if (released) return
        val t = FocusPolicy.reduce(state, input)
        state = t.state
        // Log first, so the diagnostics read in cause-then-effect order even when a command
        // (like a focus request) immediately produces further events.
        val (logs, actions) = t.commands.partition { it is FocusCommand.Log }
        for (command in logs) execute(command)
        for (command in actions) execute(command)
        publish()
    }

    private fun execute(c: FocusCommand) {
        when (c) {
            is FocusCommand.RequestFocus -> {
                val result = request(c.type)
                dispatch(FocusInput.FocusRequestCompleted(c.type, result, now()))
            }
            FocusCommand.AbandonFocus -> abandon()
            is FocusCommand.Play -> {
                if (player.playbackState == Player.STATE_IDLE) player.prepare()
                if (player.playbackState == Player.STATE_ENDED) player.seekToDefaultPosition()
                player.play()
            }
            is FocusCommand.Pause -> player.pause()
            is FocusCommand.SetDuck -> volume.setDuck(c.volume)
            is FocusCommand.ScheduleCheck -> {
                handler.removeCallbacks(checkRunnable)
                handler.postDelayed(checkRunnable, c.delayMs)
            }
            FocusCommand.CancelCheck -> handler.removeCallbacks(checkRunnable)
            is FocusCommand.Log -> diagnostics.log(c.event, activePlaybackSummary())
        }
    }

    private fun request(type: FocusRequestType): FocusRequestResult {
        abandon()
        val gain = when (type) {
            FocusRequestType.GAIN -> AudioManager.AUDIOFOCUS_GAIN
            FocusRequestType.TRANSIENT_MAY_DUCK -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
        }
        val req = AudioFocusRequest.Builder(gain)
            .setAudioAttributes(attributes)
            .setAcceptsDelayedFocusGain(type == FocusRequestType.GAIN)
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener(focusListener, handler)
            .build()
        val result = try {
            audioManager.requestAudioFocus(req)
        } catch (e: RuntimeException) {
            // Android 17 hardening in "throw" test mode raises instead of returning FAILED.
            AudioManager.AUDIOFOCUS_REQUEST_FAILED
        }
        return when (result) {
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> { activeRequest = req; FocusRequestResult.GRANTED }
            AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> { activeRequest = req; FocusRequestResult.DELAYED }
            else -> FocusRequestResult.FAILED
        }
    }

    private fun abandon() {
        activeRequest?.let { runCatching { audioManager.abandonAudioFocusRequest(it) } }
        activeRequest = null
    }

    /** True if some *other* app's media/game audio is playing; our player is paused when this is asked. */
    private fun otherAudioActive(): Boolean = audioManager.isMusicActive

    /** e.g. "active: GAME, MEDIA" — anonymised by Android, so no app names. */
    private fun activePlaybackSummary(): String {
        val configs = runCatching { audioManager.activePlaybackConfigurations }.getOrNull() ?: return ""
        if (configs.isEmpty()) return "no other audio"
        val usages = configs.map { usageName(it.audioAttributes.usage) }.groupingBy { it }.eachCount()
        return "active: " + usages.entries.joinToString { (u, n) -> if (n > 1) "$u×$n" else u }
    }

    private fun publish() {
        val s = state
        val waiting = when (val sus = s.suspension) {
            is Suspension.Permanent -> "Waiting for the other app to go quiet"
            is Suspension.Transient -> if (s.settings.mode == FocusMode.MIX) null else "Paused while another app speaks"
            is Suspension.Call -> "Paused for a call"
            is Suspension.AwaitingFocus -> "Waiting for the call to end"
            null -> null
        }
        status.setFocus(FocusUi(mode = s.settings.mode, holdsFocus = s.focus == FocusHold.HELD, waitingFor = waiting, ducked = s.ducked))
    }

    private fun now() = clock()

    companion object {
        private const val MODE_POLL_MS = 2_000L
        private const val MODE_CALL_SCREENING = 4
        private const val MODE_CALL_REDIRECT = 5
        private const val MODE_COMMUNICATION_REDIRECT = 6

        fun usageName(usage: Int): String = when (usage) {
            AudioAttributes.USAGE_MEDIA -> "MEDIA"
            AudioAttributes.USAGE_GAME -> "GAME"
            AudioAttributes.USAGE_VOICE_COMMUNICATION -> "CALL"
            AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING -> "CALL_SIGNAL"
            AudioAttributes.USAGE_ALARM -> "ALARM"
            AudioAttributes.USAGE_NOTIFICATION -> "NOTIFICATION"
            AudioAttributes.USAGE_NOTIFICATION_RINGTONE -> "RINGTONE"
            AudioAttributes.USAGE_NOTIFICATION_EVENT -> "NOTIFICATION_EVENT"
            AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY -> "ACCESSIBILITY"
            AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE -> "NAVIGATION"
            AudioAttributes.USAGE_ASSISTANCE_SONIFICATION -> "UI_SOUND"
            AudioAttributes.USAGE_ASSISTANT -> "ASSISTANT"
            else -> "USAGE_$usage"
        }
    }
}
