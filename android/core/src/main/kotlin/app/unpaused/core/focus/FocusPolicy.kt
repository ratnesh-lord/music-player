package app.unpaused.core.focus

/** Our view of the platform audio focus. */
enum class FocusHold {
    /** Not requested, abandoned, or permanently lost. */
    NONE,

    /** We currently own focus. */
    HELD,

    /** Request returned DELAYED (e.g. during a call); Android will send GAIN later. */
    PENDING,

    /** Another app holds focus transiently; Android will send GAIN back when it is done. */
    LOST_TRANSIENT,
}

/** Why playback is paused although the user still wants music. */
sealed interface Suspension {
    val sinceMs: Long

    data class Transient(override val sinceMs: Long, val afterCallFallback: Boolean = false) : Suspension

    /** Smart mode only: watching for the other app to go quiet until [deadlineMs]. */
    data class Permanent(
        override val sinceMs: Long,
        val deadlineMs: Long,
        val quietSinceMs: Long? = null,
    ) : Suspension

    data class Call(override val sinceMs: Long) : Suspension
    data class AwaitingFocus(override val sinceMs: Long) : Suspension
}

enum class RequestPurpose { USER_PLAY, AUTO_RESUME, AFTER_CALL, UNMUTE_PING, MODE_SWITCH }

data class FocusState(
    val settings: FocusSettings = FocusSettings(),
    val focus: FocusHold = FocusHold.NONE,
    /** The user's intent: music should be playing. */
    val wantsToPlay: Boolean = false,
    /** What we last told the player. */
    val playing: Boolean = false,
    val suspension: Suspension? = null,
    val ducked: Boolean = false,
    val audioMode: AudioMode = AudioMode.NORMAL,
    val requestPurpose: RequestPurpose? = null,
)

data class Transition(val state: FocusState, val commands: List<FocusCommand>)

/**
 * Pure audio-focus state machine. The Android adapter feeds platform events in as [FocusInput]s and
 * executes the returned [FocusCommand]s. No Android types are used so every rule is unit-testable.
 */
object FocusPolicy {

    fun initial(settings: FocusSettings = FocusSettings()): FocusState = FocusState(settings = settings.sanitized())

    fun reduce(state: FocusState, input: FocusInput): Transition {
        val b = Builder(state)
        when (input) {
            is FocusInput.UserPlay -> b.onUserPlay(input.atMs)
            is FocusInput.UserPause -> b.onUserPause(input.atMs)
            is FocusInput.Stopped -> b.onStopped(input.atMs)
            is FocusInput.FocusChanged -> b.onFocusChanged(input.change, input.atMs)
            is FocusInput.FocusRequestCompleted -> b.onRequestCompleted(input.type, input.result, input.atMs)
            is FocusInput.AudioModeChanged -> b.onAudioMode(input.mode, input.atMs)
            is FocusInput.OtherAudioCheck -> b.onCheck(input.otherAudioActive, input.atMs)
            is FocusInput.Noisy -> b.onNoisy(input.atMs)
            is FocusInput.SettingsChanged -> b.onSettings(input.settings.sanitized(), input.atMs)
        }
        return b.build()
    }

    /** Whether [mode] should be treated as an ongoing call for the given settings. */
    fun isCallLike(mode: AudioMode, settings: FocusSettings): Boolean = when (mode) {
        AudioMode.RINGTONE, AudioMode.IN_CALL, AudioMode.CALL_SCREENING -> true
        AudioMode.IN_COMMUNICATION -> settings.pauseForVoiceChat
        AudioMode.NORMAL, AudioMode.OTHER -> false
    }

    private class Builder(initial: FocusState) {
        var s: FocusState = initial
        val out = mutableListOf<FocusCommand>()

        private val mode get() = s.settings.mode

        fun build() = Transition(s, out.toList())

        private fun log(type: FocusEventType, at: Long, origin: EventOrigin, detail: String = "") {
            out += FocusCommand.Log(FocusEvent(type, at, mode, origin, detail))
        }

        private fun play(reason: PlayReason) {
            out += FocusCommand.Play(reason)
            s = s.copy(playing = true, suspension = null)
        }

        private fun pause(reason: PauseReason) {
            out += FocusCommand.Pause(reason)
            s = s.copy(playing = false)
        }

        private fun unduck() {
            if (s.ducked) {
                out += FocusCommand.SetDuck(1f)
                s = s.copy(ducked = false)
            }
        }

        private fun duck() {
            if (!s.ducked) {
                out += FocusCommand.SetDuck(s.settings.duckVolume)
                s = s.copy(ducked = true)
            }
        }

        private fun request(type: FocusRequestType, purpose: RequestPurpose, at: Long) {
            out += FocusCommand.RequestFocus(type)
            s = s.copy(requestPurpose = purpose)
            log(FocusEventType.FOCUS_REQUESTED, at, EventOrigin.APP, "${type.name} for ${purpose.name}")
        }

        private fun abandon() {
            if (s.focus != FocusHold.NONE) {
                out += FocusCommand.AbandonFocus
                s = s.copy(focus = FocusHold.NONE)
            }
        }

        private fun cancelWatching() {
            if (s.suspension is Suspension.Permanent || (s.suspension as? Suspension.Transient)?.afterCallFallback == true) {
                out += FocusCommand.CancelCheck
            }
        }

        fun onUserPlay(at: Long) {
            log(FocusEventType.USER_PLAY, at, EventOrigin.USER)
            cancelWatching()
            s = s.copy(wantsToPlay = true, suspension = null)
            if (mode == FocusMode.MIX) {
                abandon()
                unduck()
                play(PlayReason.USER)
                // "Unmute ping": a brief duckable request makes Android restore our players if a previous
                // focus loss left them faded; we abandon it as soon as it is granted.
                request(FocusRequestType.TRANSIENT_MAY_DUCK, RequestPurpose.UNMUTE_PING, at)
                return
            }
            if (s.focus == FocusHold.HELD) {
                unduck()
                play(PlayReason.USER)
            } else {
                request(FocusRequestType.GAIN, RequestPurpose.USER_PLAY, at)
            }
        }

        fun onUserPause(at: Long) {
            log(FocusEventType.USER_PAUSE, at, EventOrigin.USER)
            cancelWatching()
            s = s.copy(wantsToPlay = false, suspension = null)
            pause(PauseReason.USER)
        }

        fun onStopped(at: Long) {
            log(FocusEventType.STOPPED, at, EventOrigin.USER)
            cancelWatching()
            s = s.copy(wantsToPlay = false, playing = false, suspension = null, requestPurpose = null)
            abandon()
            unduck()
        }

        fun onRequestCompleted(type: FocusRequestType, result: FocusRequestResult, at: Long) {
            val purpose = s.requestPurpose
            s = s.copy(requestPurpose = null)

            if (purpose == RequestPurpose.UNMUTE_PING || type == FocusRequestType.TRANSIENT_MAY_DUCK) {
                // The ping has done its job (or was refused); never keep transient focus.
                if (result != FocusRequestResult.FAILED) out += FocusCommand.AbandonFocus
                s = s.copy(focus = FocusHold.NONE)
                return
            }

            when (result) {
                FocusRequestResult.GRANTED -> {
                    log(FocusEventType.FOCUS_GRANTED, at, EventOrigin.ANDROID)
                    s = s.copy(focus = FocusHold.HELD)
                    if (s.wantsToPlay) {
                        unduck()
                        val reason = when (purpose) {
                            RequestPurpose.AUTO_RESUME -> PlayReason.AUTO_RESUME
                            RequestPurpose.AFTER_CALL -> PlayReason.CALL_ENDED
                            RequestPurpose.MODE_SWITCH -> PlayReason.MODE_CHANGED
                            RequestPurpose.USER_PLAY, RequestPurpose.UNMUTE_PING, null -> PlayReason.USER
                        }
                        if (purpose == RequestPurpose.AUTO_RESUME) {
                            log(FocusEventType.AUTO_RESUMED, at, EventOrigin.APP, "Other audio stayed quiet")
                        }
                        play(reason)
                    }
                }

                FocusRequestResult.DELAYED -> {
                    log(FocusEventType.FOCUS_DELAYED, at, EventOrigin.ANDROID, "Focus locked (usually a call)")
                    s = s.copy(focus = FocusHold.PENDING)
                    if (s.wantsToPlay) {
                        if (s.playing && purpose != RequestPurpose.MODE_SWITCH) pause(PauseReason.FOCUS_DENIED)
                        if (purpose != RequestPurpose.MODE_SWITCH) {
                            s = s.copy(suspension = Suspension.AwaitingFocus(at))
                        }
                    }
                }

                FocusRequestResult.FAILED -> {
                    s = s.copy(focus = FocusHold.NONE)
                    when (purpose) {
                        RequestPurpose.MODE_SWITCH -> {
                            // Already playing without focus (was Mix); keep playing, just log.
                            log(FocusEventType.FOCUS_DENIED, at, EventOrigin.ANDROID, "Keeping playback without focus")
                        }

                        RequestPurpose.AUTO_RESUME -> {
                            log(FocusEventType.AUTO_RESUME_GAVE_UP, at, EventOrigin.ANDROID, "Android refused focus")
                            s = s.copy(wantsToPlay = false, suspension = null)
                        }

                        else -> {
                            log(FocusEventType.FOCUS_DENIED, at, EventOrigin.ANDROID)
                            s = s.copy(wantsToPlay = false, suspension = null)
                            pause(PauseReason.FOCUS_DENIED)
                        }
                    }
                }
            }
        }

        fun onFocusChanged(change: FocusChange, at: Long) {
            when (change) {
                FocusChange.GAIN -> {
                    log(FocusEventType.FOCUS_GAINED, at, EventOrigin.ANDROID)
                    val sus = s.suspension
                    s = s.copy(focus = FocusHold.HELD)
                    unduck()
                    if (!s.wantsToPlay) return
                    when (sus) {
                        is Suspension.AwaitingFocus -> play(PlayReason.FOCUS_REGAINED)
                        is Suspension.Transient -> {
                            if (sus.afterCallFallback) out += FocusCommand.CancelCheck
                            if (s.settings.resumeAfterInterruption) {
                                play(PlayReason.FOCUS_REGAINED)
                            } else {
                                s = s.copy(wantsToPlay = false, suspension = null)
                            }
                        }

                        else -> Unit
                    }
                }

                FocusChange.LOSS_TRANSIENT_CAN_DUCK -> {
                    log(FocusEventType.FOCUS_DUCKED, at, EventOrigin.ANDROID)
                    s = s.copy(focus = FocusHold.LOST_TRANSIENT)
                    duck()
                }

                FocusChange.LOSS_TRANSIENT -> {
                    log(FocusEventType.FOCUS_LOST_TRANSIENT, at, EventOrigin.ANDROID)
                    s = s.copy(focus = FocusHold.LOST_TRANSIENT)
                    if (!s.wantsToPlay) return
                    val inCall = isCallLike(s.audioMode, s.settings)
                    if (s.settings.duckOnTransientLoss && !inCall && s.playing) {
                        duck()
                        log(FocusEventType.DUCKED_INSTEAD_OF_PAUSE, at, EventOrigin.APP)
                    } else {
                        cancelWatching()
                        if (s.playing) pause(PauseReason.TRANSIENT_FOCUS_LOSS)
                        s = s.copy(suspension = Suspension.Transient(at))
                    }
                }

                FocusChange.LOSS -> {
                    log(FocusEventType.FOCUS_LOST, at, EventOrigin.ANDROID, "Another app took audio focus")
                    if (s.focus != FocusHold.NONE) out += FocusCommand.AbandonFocus
                    s = s.copy(focus = FocusHold.NONE)
                    unduck()
                    if (!s.wantsToPlay) return
                    when (mode) {
                        FocusMode.MIX -> log(FocusEventType.IGNORED, at, EventOrigin.APP, "Mix mode keeps playing")
                        FocusMode.STANDARD -> {
                            cancelWatching()
                            pause(PauseReason.FOCUS_LOSS)
                            s = s.copy(wantsToPlay = false, suspension = null)
                        }

                        FocusMode.SMART -> {
                            cancelWatching()
                            pause(PauseReason.FOCUS_LOSS)
                            val window = s.settings.autoResumeWindowMs
                            if (window <= 0L) {
                                s = s.copy(wantsToPlay = false, suspension = null)
                            } else {
                                s = s.copy(suspension = Suspension.Permanent(sinceMs = at, deadlineMs = at + window))
                                out += FocusCommand.ScheduleCheck(FocusSettings.CHECK_INTERVAL_MS)
                                log(
                                    FocusEventType.AUTO_RESUME_WATCHING,
                                    at,
                                    EventOrigin.APP,
                                    "Will resume when other audio is quiet for ${s.settings.quietPeriodMs / 1000}s (up to ${window / 60_000} min)",
                                )
                            }
                        }
                    }
                }
            }
        }

        fun onCheck(otherAudioActive: Boolean, at: Long) {
            when (val sus = s.suspension) {
                is Suspension.Permanent -> {
                    if (!s.wantsToPlay) {
                        s = s.copy(suspension = null)
                        return
                    }
                    if (at >= sus.deadlineMs) {
                        log(FocusEventType.AUTO_RESUME_GAVE_UP, at, EventOrigin.APP, "Other audio kept playing")
                        s = s.copy(wantsToPlay = false, suspension = null)
                        return
                    }
                    if (otherAudioActive) {
                        s = s.copy(suspension = sus.copy(quietSinceMs = null))
                        out += FocusCommand.ScheduleCheck(FocusSettings.CHECK_INTERVAL_MS)
                        return
                    }
                    val quietSince = sus.quietSinceMs ?: at
                    s = s.copy(suspension = sus.copy(quietSinceMs = quietSince))
                    if (at - quietSince >= s.settings.quietPeriodMs) {
                        if (isCallLike(s.audioMode, s.settings)) {
                            // Never resume into a call; keep watching.
                            out += FocusCommand.ScheduleCheck(FocusSettings.CHECK_INTERVAL_MS)
                        } else {
                            request(FocusRequestType.GAIN, RequestPurpose.AUTO_RESUME, at)
                        }
                    } else {
                        out += FocusCommand.ScheduleCheck(FocusSettings.CHECK_INTERVAL_MS)
                    }
                }

                is Suspension.Transient -> {
                    if (!sus.afterCallFallback || !s.wantsToPlay) return
                    s = s.copy(suspension = sus.copy(afterCallFallback = false))
                    if (!otherAudioActive && !isCallLike(s.audioMode, s.settings) && s.focus != FocusHold.HELD) {
                        request(FocusRequestType.GAIN, RequestPurpose.AFTER_CALL, at)
                    }
                }

                else -> Unit
            }
        }

        fun onAudioMode(newMode: AudioMode, at: Long) {
            val wasCall = isCallLike(s.audioMode, s.settings)
            val isCall = isCallLike(newMode, s.settings)
            s = s.copy(audioMode = newMode)
            if (!wasCall && isCall) {
                log(FocusEventType.CALL_STARTED, at, EventOrigin.ANDROID, newMode.name)
                if (s.wantsToPlay && s.playing) {
                    cancelWatching()
                    pause(PauseReason.CALL)
                    s = s.copy(suspension = Suspension.Call(at))
                }
            } else if (wasCall && !isCall) {
                log(FocusEventType.CALL_ENDED, at, EventOrigin.ANDROID, newMode.name)
                val sus = s.suspension
                if (!s.wantsToPlay) return
                when {
                    sus is Suspension.Call && s.settings.resumeAfterInterruption -> when {
                        mode == FocusMode.MIX -> play(PlayReason.CALL_ENDED)
                        s.focus == FocusHold.HELD -> play(PlayReason.CALL_ENDED)
                        else -> request(FocusRequestType.GAIN, RequestPurpose.AFTER_CALL, at)
                    }

                    sus is Suspension.Call -> s = s.copy(wantsToPlay = false, suspension = null)
                    sus is Suspension.Transient && s.settings.resumeAfterInterruption && mode != FocusMode.MIX -> {
                        // Normally Android sends GAIN when the dialer abandons focus. If it doesn't, ask for it.
                        s = s.copy(suspension = sus.copy(afterCallFallback = true))
                        out += FocusCommand.ScheduleCheck(FocusSettings.AFTER_CALL_FALLBACK_MS)
                    }
                }
            }
        }

        fun onNoisy(at: Long) {
            log(FocusEventType.NOISY, at, EventOrigin.ANDROID, "Headphones disconnected")
            cancelWatching()
            if (s.playing || s.wantsToPlay) pause(PauseReason.NOISY)
            s = s.copy(wantsToPlay = false, suspension = null)
        }

        fun onSettings(new: FocusSettings, at: Long) {
            val old = s.settings
            s = s.copy(settings = new)
            val sus = s.suspension
            if (sus is Suspension.Permanent && new.autoResumeWindowMs != old.autoResumeWindowMs) {
                s = s.copy(suspension = sus.copy(deadlineMs = sus.sinceMs + new.autoResumeWindowMs))
            }
            if (old.mode == new.mode) return
            log(FocusEventType.MODE_SWITCHED, at, EventOrigin.USER, "${old.mode} → ${new.mode}")
            when (new.mode) {
                FocusMode.MIX -> {
                    abandon()
                    unduck()
                    val resumable = sus is Suspension.Transient || sus is Suspension.Permanent || sus is Suspension.AwaitingFocus
                    if (s.wantsToPlay && resumable) {
                        cancelWatching()
                        play(PlayReason.MODE_CHANGED)
                        request(FocusRequestType.TRANSIENT_MAY_DUCK, RequestPurpose.UNMUTE_PING, at)
                    }
                }

                FocusMode.STANDARD, FocusMode.SMART -> {
                    if (new.mode == FocusMode.STANDARD && sus is Suspension.Permanent) {
                        out += FocusCommand.CancelCheck
                        s = s.copy(wantsToPlay = false, suspension = null)
                    }
                    if (old.mode == FocusMode.MIX && s.playing) {
                        request(FocusRequestType.GAIN, RequestPurpose.MODE_SWITCH, at)
                    }
                }
            }
        }
    }
}
