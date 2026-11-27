package app.unpaused.ui

import android.app.Application
import android.net.Uri
import android.os.Bundle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.unpaused.container
import app.unpaused.core.diagnostics.Verdict
import app.unpaused.core.focus.FocusMode
import app.unpaused.data.db.DiagnosticEventEntity
import app.unpaused.data.db.RadioStationEntity
import app.unpaused.data.db.TrackEntity
import app.unpaused.data.diagnostics.DiagnosticsLog
import app.unpaused.data.settings.AppSettings
import app.unpaused.playback.SessionCommands
import app.unpaused.ui.player.PlayerConnection
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {
    val c = application.container
    val player = PlayerConnection(application, viewModelScope)

    val settings: StateFlow<AppSettings> = c.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    val onboarded: StateFlow<Boolean?> = c.settings.onboarded.map<Boolean, Boolean?> { it }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val favoriteKeys: StateFlow<Set<String>> = c.favorites.favorites().map { list -> list.map { it.trackKey }.toSet() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    /** The most recent "Android stopped playback" event from the last day, shown as a banner on Home. */
    val lastKill: StateFlow<DiagnosticEventEntity?> = c.db.diagnostics().observeRecent(50).map { list ->
        list.firstOrNull {
            it.type == DiagnosticsLog.PROCESS_RESTARTED && it.verdict == Verdict.UNEXPECTED.name &&
                System.currentTimeMillis() - it.atMs < 24 * 60 * 60 * 1000L
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Tracks waiting for the "add to playlist" dialog. */
    var addToPlaylist by mutableStateOf<List<TrackEntity>?>(null)
    var dismissedKillAt by mutableStateOf(0L)
    var message by mutableStateOf<String?>(null)

    init {
        player.connect()
        player.onReady { controller ->
            if (controller.mediaItemCount == 0) viewModelScope.launch {
                if (c.settings.current().restoreQueueOnLaunch) player.command(SessionCommands.RESTORE_QUEUE)
            }
        }
        viewModelScope.launch {
            if (c.settings.current().let { it.useMediaStore } && c.library.hasAudioPermission() || c.db.folders().all().isNotEmpty()) {
                c.library.scan()
            }
        }
    }

    fun rescan() = viewModelScope.launch {
        val r = c.library.scan()
        message = "Library: ${r.tracks} tracks" + if (r.added > 0) " (${r.added} new)" else ""
    }

    fun update(transform: (AppSettings) -> AppSettings) = viewModelScope.launch { c.settings.update(transform) }

    fun finishOnboarding() = viewModelScope.launch {
        c.settings.setOnboarded()
        c.library.scan()
    }

    fun addFolder(uri: Uri) = viewModelScope.launch {
        if (c.library.addFolder(uri)) rescan() else message = "Couldn't keep access to that folder"
    }

    fun setFocusMode(mode: FocusMode) = update { s ->
        if (mode == FocusMode.MIX && s.focusMode != FocusMode.MIX) s.copy(focusMode = mode, lastNonMixMode = s.focusMode) else s.copy(focusMode = mode)
    }

    fun toggleFavorite(trackKey: String, title: String, artist: String) = viewModelScope.launch {
        c.favorites.toggle(trackKey, title, artist)
    }

    fun playTracks(tracks: List<TrackEntity>, start: Int = 0, shuffle: Boolean = false) = player.playTracks(tracks, start, shuffle)

    fun playKeys(keys: List<String>, start: Int = 0, shuffle: Boolean = false) = viewModelScope.launch {
        val tracks = c.library.resolveKeys(keys)
        if (tracks.isEmpty()) message = "None of these tracks are on this device" else player.playTracks(tracks, start.coerceAtMost(tracks.size - 1), shuffle)
    }

    fun shuffleAll() = viewModelScope.launch {
        val tracks = c.library.random(2_000)
        if (tracks.isEmpty()) message = "Your library is empty" else player.playTracks(tracks, 0, shuffle = false)
    }

    fun playRadio(station: RadioStationEntity) = viewModelScope.launch {
        c.radio.remember(station)
        player.playRadio(station)
    }

    fun sleepTimer(minutes: Int) = player.commandAsync(SessionCommands.SLEEP_TIMER, Bundle().apply { putInt(SessionCommands.ARG_MINUTES, minutes) })
    fun setOutput(deviceId: Int) = player.commandAsync(SessionCommands.SET_OUTPUT, Bundle().apply { putInt(SessionCommands.ARG_DEVICE_ID, deviceId) })

    fun addTracksToPlaylist(playlistId: Long?, newName: String?, tracks: List<TrackEntity>) = viewModelScope.launch {
        if (playlistId != null) c.playlists.add(playlistId, tracks) else c.playlists.create(newName ?: "New playlist", tracks)
        message = "Added ${tracks.size} to playlist"
    }

    override fun onCleared() {
        player.release()
    }
}
