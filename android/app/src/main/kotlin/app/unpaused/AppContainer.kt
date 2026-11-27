package app.unpaused

import android.app.Application
import android.content.Context
import app.unpaused.data.db.AppDatabase
import app.unpaused.data.diagnostics.DiagnosticsLog
import app.unpaused.data.library.ArtworkCache
import app.unpaused.data.library.LibraryRepository
import app.unpaused.data.library.MetadataEnricher
import app.unpaused.data.lyrics.LyricsRepository
import app.unpaused.data.radio.RadioBrowserClient
import app.unpaused.data.radio.RadioRepository
import app.unpaused.data.settings.SettingsRepository
import app.unpaused.data.sync.SyncRepository
import app.unpaused.data.user.FavoritesRepository
import app.unpaused.data.user.HistoryRepository
import app.unpaused.data.user.PlaylistRepository
import app.unpaused.playback.PlaybackStateStore
import app.unpaused.playback.PlaybackStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Manual dependency container (the app is small enough not to need a DI framework). */
class AppContainer(private val app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val userAgent = "Unpaused/${BuildConfig.VERSION_NAME} (Android; https://github.com/ratnesh-lord/music-player)"

    val db: AppDatabase by lazy { AppDatabase.create(app) }
    val settings by lazy { SettingsRepository(app) }
    val diagnostics by lazy { DiagnosticsLog(db.diagnostics(), scope) }
    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
    }
    val enricher by lazy { MetadataEnricher(app) }
    val artwork by lazy { ArtworkCache(app) }
    val library by lazy { LibraryRepository(app, db, settings, enricher, artwork) }
    val playlists by lazy { PlaylistRepository(db, settings) }
    val favorites by lazy { FavoritesRepository(db, settings) }
    val history by lazy { HistoryRepository(db, settings) }
    val radio by lazy { RadioRepository(db, RadioBrowserClient(http, userAgent)) }
    val lyrics by lazy { LyricsRepository(app, db, enricher) }
    val sync by lazy { SyncRepository(db, settings, http) }
    val playbackStatus = PlaybackStatus()
    val playbackState by lazy { PlaybackStateStore(app) }
}

val Context.container: AppContainer get() = (applicationContext as UnpausedApp).container
