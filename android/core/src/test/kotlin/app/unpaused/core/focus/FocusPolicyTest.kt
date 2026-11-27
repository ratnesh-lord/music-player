package app.unpaused.core.focus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drives [FocusPolicy] the way the Android adapter does: every RequestFocus command is answered
 * immediately with [nextResult], and the commands are recorded for assertions.
 */
private class Harness(settings: FocusSettings) {
    var state = FocusPolicy.initial(settings)
    val commands = mutableListOf<FocusCommand>()
    var nextResult = FocusRequestResult.GRANTED
    var now = 0L

    fun send(input: FocusInput): Harness {
        val t = FocusPolicy.reduce(state, input)
        state = t.state
        for (c in t.commands) {
            commands += c
            if (c is FocusCommand.RequestFocus) {
                send(FocusInput.FocusRequestCompleted(c.type, nextResult, now))
            }
        }
        return this
    }

    fun at(ms: Long): Harness = apply { now = ms }
    fun play() = send(FocusInput.UserPlay(now))
    fun pause() = send(FocusInput.UserPause(now))
    fun focus(change: FocusChange) = send(FocusInput.FocusChanged(change, now))
    fun mode(m: AudioMode) = send(FocusInput.AudioModeChanged(m, now))
    fun check(active: Boolean) = send(FocusInput.OtherAudioCheck(active, now))

    fun clear() = apply { commands.clear() }
    inline fun <reified T : FocusCommand> of(): List<T> = commands.filterIsInstance<T>()
    fun plays() = of<FocusCommand.Play>().map { it.reason }
    fun pauses() = of<FocusCommand.Pause>().map { it.reason }
    fun requests() = of<FocusCommand.RequestFocus>().map { it.type }
    fun events() = of<FocusCommand.Log>().map { it.event.type }
}

class FocusPolicyTest {

    private fun standard() = Harness(FocusSettings(mode = FocusMode.STANDARD))
    private fun smart(window: Long = 60_000) = Harness(FocusSettings(mode = FocusMode.SMART, autoResumeWindowMs = window))
    private fun mix() = Harness(FocusSettings(mode = FocusMode.MIX))

    @Test fun `play requests focus and plays when granted`() {
        val h = standard().play()
        assertEquals(listOf(FocusRequestType.GAIN), h.requests())
        assertEquals(listOf(PlayReason.USER), h.plays())
        assertEquals(FocusHold.HELD, h.state.focus)
        assertTrue(h.state.playing)
    }

    @Test fun `play does not start when focus is denied`() {
        val h = standard()
        h.nextResult = FocusRequestResult.FAILED
        h.play()
        assertTrue(h.plays().isEmpty())
        assertEquals(listOf(PauseReason.FOCUS_DENIED), h.pauses())
        assertFalse(h.state.wantsToPlay)
        assertTrue(FocusEventType.FOCUS_DENIED in h.events())
    }

    @Test fun `delayed focus waits for gain then plays`() {
        val h = standard()
        h.nextResult = FocusRequestResult.DELAYED
        h.play()
        assertTrue(h.plays().isEmpty())
        assertTrue(h.state.suspension is Suspension.AwaitingFocus)
        h.clear().focus(FocusChange.GAIN)
        assertEquals(listOf(PlayReason.FOCUS_REGAINED), h.plays())
    }

    @Test fun `standard - permanent loss pauses and never auto resumes`() {
        val h = standard().play().clear()
        h.at(1_000).focus(FocusChange.LOSS)
        assertEquals(listOf(PauseReason.FOCUS_LOSS), h.pauses())
        assertTrue(h.of<FocusCommand.AbandonFocus>().isNotEmpty())
        assertFalse(h.state.wantsToPlay)
        assertTrue(h.of<FocusCommand.ScheduleCheck>().isEmpty())
        h.clear().at(100_000).check(active = false)
        assertTrue(h.plays().isEmpty())
    }

    @Test fun `transient loss pauses and resumes on gain`() {
        val h = smart().play().clear()
        h.focus(FocusChange.LOSS_TRANSIENT)
        assertEquals(listOf(PauseReason.TRANSIENT_FOCUS_LOSS), h.pauses())
        h.clear().focus(FocusChange.GAIN)
        assertEquals(listOf(PlayReason.FOCUS_REGAINED), h.plays())
        assertNull(h.state.suspension)
    }

    @Test fun `transient loss can duck instead of pausing`() {
        val h = Harness(FocusSettings(mode = FocusMode.SMART, duckOnTransientLoss = true)).play().clear()
        h.focus(FocusChange.LOSS_TRANSIENT)
        assertTrue(h.pauses().isEmpty())
        assertEquals(listOf(FocusSettings.DEFAULT_DUCK_VOLUME), h.of<FocusCommand.SetDuck>().map { it.volume })
        h.clear().focus(FocusChange.GAIN)
        assertEquals(listOf(1f), h.of<FocusCommand.SetDuck>().map { it.volume })
    }

    @Test fun `duck-instead-of-pause never applies during a call`() {
        val h = Harness(FocusSettings(mode = FocusMode.SMART, duckOnTransientLoss = true)).play()
        h.mode(AudioMode.RINGTONE).clear()
        h.focus(FocusChange.LOSS_TRANSIENT)
        assertTrue(h.of<FocusCommand.SetDuck>().isEmpty())
    }

    @Test fun `duckable loss lowers volume and restores it on gain`() {
        val h = smart().play().clear()
        h.focus(FocusChange.LOSS_TRANSIENT_CAN_DUCK)
        assertTrue(h.pauses().isEmpty())
        assertTrue(h.state.ducked)
        h.clear().focus(FocusChange.GAIN)
        assertFalse(h.state.ducked)
        assertEquals(listOf(1f), h.of<FocusCommand.SetDuck>().map { it.volume })
    }

    @Test fun `smart - resumes after other audio stays quiet for the quiet period`() {
        val h = smart().play().clear()
        h.at(0).focus(FocusChange.LOSS)
        assertEquals(listOf(PauseReason.FOCUS_LOSS), h.pauses())
        assertTrue(h.state.suspension is Suspension.Permanent)
        assertTrue(h.of<FocusCommand.ScheduleCheck>().isNotEmpty())
        h.clear()
        h.at(1_000).check(active = true) // game still playing
        h.at(2_000).check(active = false) // quiet starts
        h.at(3_000).check(active = false)
        assertTrue("must not resume before quiet period", h.plays().isEmpty())
        h.at(5_000).check(active = false) // 3 s of quiet
        assertEquals(listOf(FocusRequestType.GAIN), h.requests())
        assertEquals(listOf(PlayReason.AUTO_RESUME), h.plays())
        assertTrue(FocusEventType.AUTO_RESUMED in h.events())
        assertNull(h.state.suspension)
    }

    @Test fun `smart - noise in the middle restarts the quiet period`() {
        val h = smart().play().at(0).focus(FocusChange.LOSS).clear()
        h.at(1_000).check(false)
        h.at(3_000).check(true)
        h.at(4_000).check(false)
        h.at(6_000).check(false)
        assertTrue(h.plays().isEmpty())
        h.at(7_000).check(false)
        assertEquals(listOf(PlayReason.AUTO_RESUME), h.plays())
    }

    @Test fun `smart - gives up after the window`() {
        val h = smart(window = 10_000).play().at(0).focus(FocusChange.LOSS).clear()
        h.at(5_000).check(true)
        h.clear().at(10_000).check(false)
        assertTrue(h.plays().isEmpty())
        assertTrue(FocusEventType.AUTO_RESUME_GAVE_UP in h.events())
        assertFalse(h.state.wantsToPlay)
        assertTrue(h.of<FocusCommand.ScheduleCheck>().isEmpty())
    }

    @Test fun `smart - user pause cancels auto resume`() {
        val h = smart().play().at(0).focus(FocusChange.LOSS).clear()
        h.at(500).pause()
        assertTrue(h.of<FocusCommand.CancelCheck>().isNotEmpty())
        h.clear().at(10_000).check(false)
        assertTrue(h.plays().isEmpty())
    }

    @Test fun `smart - no auto resume if music was already paused`() {
        val h = smart().play().pause().clear()
        h.focus(FocusChange.LOSS)
        assertTrue(h.of<FocusCommand.ScheduleCheck>().isEmpty())
        assertNull(h.state.suspension)
    }

    @Test fun `smart - auto resume refused by Android gives up`() {
        val h = smart().play().at(0).focus(FocusChange.LOSS).clear()
        h.nextResult = FocusRequestResult.FAILED
        h.at(1_000).check(false)
        h.at(4_000).check(false)
        assertTrue(h.plays().isEmpty())
        assertTrue(FocusEventType.AUTO_RESUME_GAVE_UP in h.events())
        assertFalse(h.state.wantsToPlay)
    }

    @Test fun `smart - never resumes into a call`() {
        val h = smart().play().at(0).focus(FocusChange.LOSS)
        h.at(500).mode(AudioMode.IN_CALL).clear()
        h.at(1_000).check(false)
        h.at(5_000).check(false)
        assertTrue(h.requests().isEmpty())
        assertTrue(h.of<FocusCommand.ScheduleCheck>().isNotEmpty())
    }

    @Test fun `mix - never requests gain focus and ignores losses`() {
        val h = mix().play()
        assertEquals(listOf(PlayReason.USER), h.plays())
        assertTrue(FocusRequestType.GAIN !in h.requests())
        // The unmute ping is abandoned straight away.
        assertEquals(listOf(FocusRequestType.TRANSIENT_MAY_DUCK), h.requests())
        assertTrue(h.of<FocusCommand.AbandonFocus>().isNotEmpty())
        assertEquals(FocusHold.NONE, h.state.focus)
        h.clear().focus(FocusChange.LOSS)
        assertTrue(h.pauses().isEmpty())
        assertTrue(h.state.playing)
    }

    @Test fun `mix - pauses for a call and resumes when it ends`() {
        val h = mix().play().clear()
        h.mode(AudioMode.RINGTONE)
        assertEquals(listOf(PauseReason.CALL), h.pauses())
        h.mode(AudioMode.IN_CALL)
        assertEquals(1, h.pauses().size)
        h.clear().mode(AudioMode.NORMAL)
        assertEquals(listOf(PlayReason.CALL_ENDED), h.plays())
    }

    @Test fun `mix - voice chat pause can be turned off`() {
        val h = Harness(FocusSettings(mode = FocusMode.MIX, pauseForVoiceChat = false)).play().clear()
        h.mode(AudioMode.IN_COMMUNICATION)
        assertTrue(h.pauses().isEmpty())
        h.mode(AudioMode.RINGTONE)
        assertEquals(listOf(PauseReason.CALL), h.pauses())
    }

    @Test fun `standard - call via focus resumes on gain, not on mode change`() {
        val h = standard().play().clear()
        h.mode(AudioMode.RINGTONE)
        h.focus(FocusChange.LOSS_TRANSIENT)
        h.mode(AudioMode.IN_CALL)
        h.clear().mode(AudioMode.NORMAL)
        assertTrue(h.plays().isEmpty())
        h.focus(FocusChange.GAIN)
        assertEquals(listOf(PlayReason.FOCUS_REGAINED), h.plays())
    }

    @Test fun `after a call ends without gain we ask for focus ourselves`() {
        val h = standard().play().clear()
        h.mode(AudioMode.RINGTONE).focus(FocusChange.LOSS_TRANSIENT).mode(AudioMode.IN_CALL)
        h.clear().at(10_000).mode(AudioMode.NORMAL)
        assertEquals(listOf(FocusSettings.AFTER_CALL_FALLBACK_MS), h.of<FocusCommand.ScheduleCheck>().map { it.delayMs })
        h.at(13_000).check(false)
        assertEquals(listOf(FocusRequestType.GAIN), h.requests())
        assertEquals(listOf(PlayReason.CALL_ENDED), h.plays())
    }

    @Test fun `noisy pauses and clears intent in every mode`() {
        for (m in FocusMode.entries) {
            val h = Harness(FocusSettings(mode = m)).play().clear()
            h.send(FocusInput.Noisy(0))
            assertEquals("mode $m", listOf(PauseReason.NOISY), h.pauses())
            assertFalse(h.state.wantsToPlay)
        }
    }

    @Test fun `switching to mix while waiting to auto resume resumes immediately`() {
        val h = smart().play().at(0).focus(FocusChange.LOSS).clear()
        h.send(FocusInput.SettingsChanged(FocusSettings(mode = FocusMode.MIX), 100))
        assertEquals(listOf(PlayReason.MODE_CHANGED), h.plays())
        assertEquals(listOf(FocusRequestType.TRANSIENT_MAY_DUCK), h.requests())
        assertEquals(FocusHold.NONE, h.state.focus)
    }

    @Test fun `switching from mix to smart while playing takes focus`() {
        val h = mix().play().clear()
        h.send(FocusInput.SettingsChanged(FocusSettings(mode = FocusMode.SMART), 100))
        assertEquals(listOf(FocusRequestType.GAIN), h.requests())
        assertEquals(FocusHold.HELD, h.state.focus)
    }

    @Test fun `switching smart to standard cancels pending auto resume`() {
        val h = smart().play().at(0).focus(FocusChange.LOSS).clear()
        h.send(FocusInput.SettingsChanged(FocusSettings(mode = FocusMode.STANDARD), 100))
        assertTrue(h.of<FocusCommand.CancelCheck>().isNotEmpty())
        assertFalse(h.state.wantsToPlay)
    }

    @Test fun `stop abandons focus`() {
        val h = smart().play().clear()
        h.send(FocusInput.Stopped(0))
        assertTrue(h.of<FocusCommand.AbandonFocus>().isNotEmpty())
        assertEquals(FocusHold.NONE, h.state.focus)
    }

    @Test fun `settings are clamped`() {
        val s = FocusSettings(autoResumeWindowMs = 99_999_999, quietPeriodMs = 1, duckVolume = 7f).sanitized()
        assertEquals(FocusSettings.MAX_AUTO_RESUME_WINDOW_MS, s.autoResumeWindowMs)
        assertEquals(FocusSettings.MIN_QUIET_PERIOD_MS, s.quietPeriodMs)
        assertEquals(1f, s.duckVolume)
    }

    @Test fun `user play during transient loss takes focus back`() {
        val h = smart().play().focus(FocusChange.LOSS_TRANSIENT).clear()
        h.play()
        assertEquals(listOf(FocusRequestType.GAIN), h.requests())
        assertEquals(listOf(PlayReason.USER), h.plays())
    }
}
