package app.unpaused.core.playback

/** A sleep timer that fades the music out over [fadeMs] before [endAtMs], or stops at the end of the current track. */
sealed interface SleepTimer {
    data class At(val startedAtMs: Long, val endAtMs: Long, val fadeMs: Long = DEFAULT_FADE_MS) : SleepTimer
    data object EndOfTrack : SleepTimer

    companion object {
        const val DEFAULT_FADE_MS: Long = 15_000L
    }
}

object SleepTimerMath {

    /** Volume multiplier (1 → 0) for a timed sleep timer at [nowMs]. */
    fun volumeAt(timer: SleepTimer.At, nowMs: Long): Float {
        val fadeStart = timer.endAtMs - timer.fadeMs
        return when {
            nowMs <= fadeStart -> 1f
            nowMs >= timer.endAtMs -> 0f
            else -> ((timer.endAtMs - nowMs).toFloat() / timer.fadeMs).coerceIn(0f, 1f)
        }
    }

    fun isDue(timer: SleepTimer.At, nowMs: Long): Boolean = nowMs >= timer.endAtMs

    fun remainingMs(timer: SleepTimer.At, nowMs: Long): Long = (timer.endAtMs - nowMs).coerceAtLeast(0)

    fun start(nowMs: Long, durationMs: Long, fadeMs: Long = SleepTimer.DEFAULT_FADE_MS): SleepTimer.At =
        SleepTimer.At(startedAtMs = nowMs, endAtMs = nowMs + durationMs, fadeMs = fadeMs.coerceAtMost(durationMs))
}
