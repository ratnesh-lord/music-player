package app.unpaused

import android.app.Application
import kotlinx.coroutines.launch

class UnpausedApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.scope.launch { runCatching { container.diagnostics.prune() } }
    }
}
