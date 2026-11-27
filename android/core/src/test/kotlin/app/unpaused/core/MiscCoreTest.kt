package app.unpaused.core

import app.unpaused.core.diagnostics.InterruptionClassifier
import app.unpaused.core.diagnostics.Verdict
import app.unpaused.core.focus.EventOrigin
import app.unpaused.core.focus.FocusEvent
import app.unpaused.core.focus.FocusEventType
import app.unpaused.core.focus.FocusMode
import app.unpaused.core.playback.SleepTimerMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MiscCoreTest {

    @Test fun sleepTimerFadesLinearlyThenStops() {
        val t = SleepTimerMath.start(nowMs = 0, durationMs = 60_000, fadeMs = 10_000)
        assertEquals(1f, SleepTimerMath.volumeAt(t, 0), 0f)
        assertEquals(1f, SleepTimerMath.volumeAt(t, 50_000), 0f)
        assertEquals(0.5f, SleepTimerMath.volumeAt(t, 55_000), 0.001f)
        assertEquals(0f, SleepTimerMath.volumeAt(t, 60_000), 0f)
        assertFalse(SleepTimerMath.isDue(t, 59_999))
        assertTrue(SleepTimerMath.isDue(t, 60_000))
        assertEquals(1_000L, SleepTimerMath.remainingMs(t, 59_000))
    }

    @Test fun shortTimersFadeForTheirWholeLength() {
        val t = SleepTimerMath.start(nowMs = 0, durationMs = 5_000, fadeMs = 15_000)
        assertEquals(5_000L, t.fadeMs)
    }

    private fun ev(type: FocusEventType, at: Long, origin: EventOrigin = EventOrigin.ANDROID) =
        FocusEvent(type, at, FocusMode.SMART, origin)

    @Test fun verdicts() {
        assertEquals(Verdict.EXPECTED_ANDROID, InterruptionClassifier.verdict(ev(FocusEventType.FOCUS_LOST, 0)))
        assertEquals(Verdict.USER, InterruptionClassifier.verdict(ev(FocusEventType.USER_PAUSE, 0, EventOrigin.USER)))
        assertEquals(Verdict.APP_DECISION, InterruptionClassifier.verdict(ev(FocusEventType.AUTO_RESUMED, 0, EventOrigin.APP)))
        assertEquals(Verdict.EXPECTED_ANDROID, InterruptionClassifier.verdict(ev(FocusEventType.AUTO_RESUME_GAVE_UP, 0, EventOrigin.ANDROID)))
        for (t in FocusEventType.entries) InterruptionClassifier.explain(ev(t, 0)) // every type has text
    }

    @Test fun unexplainedPauseDetection() {
        val recent = listOf(ev(FocusEventType.FOCUS_LOST, 10_000))
        assertTrue(InterruptionClassifier.isPauseExplained(10_500, recent))
        assertFalse(InterruptionClassifier.isPauseExplained(20_000, recent))
        assertFalse(InterruptionClassifier.isPauseExplained(10_500, listOf(ev(FocusEventType.FOCUS_GAINED, 10_000))))
    }
}
