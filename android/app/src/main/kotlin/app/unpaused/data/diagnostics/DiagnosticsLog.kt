package app.unpaused.data.diagnostics

import android.util.Log
import app.unpaused.core.diagnostics.InterruptionClassifier
import app.unpaused.core.diagnostics.Verdict
import app.unpaused.core.focus.FocusEvent
import app.unpaused.core.focus.FocusMode
import app.unpaused.data.db.DiagnosticEventEntity
import app.unpaused.data.db.DiagnosticsDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * On-device log of everything that starts or stops playback, so a pause can be traced to its cause
 * (see docs/TESTING.md). Kept for 14 days; never uploaded.
 */
class DiagnosticsLog(private val dao: DiagnosticsDao, private val scope: CoroutineScope) {

    private val recent = ArrayDeque<FocusEvent>()

    /** Records a focus-policy event. Call on the main thread. */
    fun log(event: FocusEvent, context: String = "") {
        recent.addLast(event)
        while (recent.size > 64) recent.removeFirst()
        val verdict = InterruptionClassifier.verdict(event)
        write(
            DiagnosticEventEntity(
                atMs = event.atMs,
                type = event.type.name,
                mode = event.mode.name,
                origin = event.origin.name,
                verdict = verdict.name,
                message = InterruptionClassifier.explain(event),
                context = context,
            ),
        )
    }

    /** Records an event that doesn't come from the focus policy (errors, process restarts, timers). */
    fun logOther(type: String, verdict: Verdict, message: String, mode: FocusMode?, context: String = "", atMs: Long = System.currentTimeMillis()) {
        write(
            DiagnosticEventEntity(
                atMs = atMs,
                type = type,
                mode = mode?.name ?: "-",
                origin = if (verdict == Verdict.USER) "USER" else "APP",
                verdict = verdict.name,
                message = message,
                context = context,
            ),
        )
    }

    fun recentFocusEvents(): List<FocusEvent> = recent.toList()

    fun prune(maxAgeMs: Long = 14L * 24 * 60 * 60 * 1000) {
        scope.launch(Dispatchers.IO) { runCatching { dao.prune(System.currentTimeMillis() - maxAgeMs) } }
    }

    private fun write(e: DiagnosticEventEntity) {
        if (e.verdict == Verdict.UNEXPECTED.name) Log.w(TAG, "${e.type}: ${e.message} ${e.context}") else Log.i(TAG, "${e.type}: ${e.message} ${e.context}")
        scope.launch(Dispatchers.IO) { runCatching { dao.insert(e) } }
    }

    companion object {
        const val TAG = "Unpaused"

        const val PROCESS_RESTARTED = "PROCESS_RESTARTED_WHILE_PLAYING"
        const val PLAYER_ERROR = "PLAYER_ERROR"
        const val UNEXPLAINED_PAUSE = "UNEXPLAINED_PAUSE"
        const val SERVICE_STARTED = "SERVICE_STARTED"
        const val SERVICE_STOPPED = "SERVICE_STOPPED"
        const val OUTPUT_CHANGED = "OUTPUT_CHANGED"
        const val SLEEP_TIMER = "SLEEP_TIMER"
        const val HEADSET_RESUME = "HEADSET_RESUME"
        const val QUEUE_RESTORED = "QUEUE_RESTORED"
        const val EFFECTS = "EFFECTS"
    }
}
