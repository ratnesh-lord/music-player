package app.unpaused.playback

import app.unpaused.core.audio.SourceQuality
import app.unpaused.core.focus.FocusMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the focus policy is doing right now, for the player screen. */
data class FocusUi(
    val mode: FocusMode = FocusMode.SMART,
    val holdsFocus: Boolean = false,
    /** Human-readable reason playback is waiting, or null. */
    val waitingFor: String? = null,
    val ducked: Boolean = false,
)

/** The PCM format our AudioTrack actually opened with (Media3 onAudioTrackInitialized). */
data class AudioTrackUi(
    val encoding: String,
    val sampleRateHz: Int,
    val channels: Int,
    val offload: Boolean,
)

data class EffectsUi(
    val engine: String = "Off",
    val note: String? = null,
)

data class SleepTimerUi(
    val endsAtMs: Long?,
    val endOfTrack: Boolean,
)

/**
 * In-process state shared by the playback service and the UI (they run in the same process).
 * Standard playback state goes through the MediaSession; this carries the extras the session doesn't.
 */
class PlaybackStatus {
    private val _focus = MutableStateFlow(FocusUi())
    val focus: StateFlow<FocusUi> = _focus.asStateFlow()

    private val _audioTrack = MutableStateFlow<AudioTrackUi?>(null)
    val audioTrack: StateFlow<AudioTrackUi?> = _audioTrack.asStateFlow()

    private val _source = MutableStateFlow<SourceQuality?>(null)
    val source: StateFlow<SourceQuality?> = _source.asStateFlow()

    private val _output = MutableStateFlow<OutputInfo?>(null)
    val output: StateFlow<OutputInfo?> = _output.asStateFlow()

    private val _effects = MutableStateFlow(EffectsUi())
    val effects: StateFlow<EffectsUi> = _effects.asStateFlow()

    private val _sleep = MutableStateFlow<SleepTimerUi?>(null)
    val sleep: StateFlow<SleepTimerUi?> = _sleep.asStateFlow()

    private val _icyTitle = MutableStateFlow<String?>(null)
    val icyTitle: StateFlow<String?> = _icyTitle.asStateFlow()

    fun setFocus(v: FocusUi) { _focus.value = v }
    fun setAudioTrack(v: AudioTrackUi?) { _audioTrack.value = v }
    fun setSource(v: SourceQuality?) { _source.value = v }
    fun setOutput(v: OutputInfo?) { _output.value = v }
    fun setEffects(v: EffectsUi) { _effects.value = v }
    fun setSleep(v: SleepTimerUi?) { _sleep.value = v }
    fun setIcyTitle(v: String?) { _icyTitle.value = v }
}
