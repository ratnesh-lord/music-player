package app.unpaused.ui

import android.net.Uri
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import app.unpaused.ui.components.Artwork
import app.unpaused.ui.components.MonoLabel
import app.unpaused.ui.screens.AboutScreen
import app.unpaused.ui.screens.AlbumScreen
import app.unpaused.ui.screens.ArtistScreen
import app.unpaused.ui.screens.AudioSettingsScreen
import app.unpaused.ui.screens.DiagnosticsScreen
import app.unpaused.ui.screens.EqualizerScreen
import app.unpaused.ui.screens.FoldersScreen
import app.unpaused.ui.screens.GroupScreen
import app.unpaused.ui.screens.HomeScreen
import app.unpaused.ui.screens.InterruptionSettingsScreen
import app.unpaused.ui.screens.LibraryScreen
import app.unpaused.ui.screens.NowPlayingScreen
import app.unpaused.ui.screens.OnboardingScreen
import app.unpaused.ui.screens.PlaylistScreen
import app.unpaused.ui.screens.RadioScreen
import app.unpaused.ui.screens.SearchScreen
import app.unpaused.ui.screens.SettingsScreen
import app.unpaused.ui.screens.SyncScreen
import app.unpaused.ui.theme.LocalExtra
import app.unpaused.ui.theme.UnpausedTheme

object Routes {
    const val HOME = "home"
    const val LIBRARY = "library"
    const val SEARCH = "search"
    const val RADIO = "radio"
    const val SETTINGS = "settings"
    const val NOW = "now"
    const val EQ = "eq"
    const val DIAGNOSTICS = "diagnostics"
    const val SYNC = "sync"
    const val FOLDERS = "folders"
    const val ABOUT = "about"
    const val INTERRUPTIONS = "interruptions"
    const val AUDIO = "audio"

    fun album(id: String) = "album/" + Uri.encode(id)
    fun artist(key: String) = "artist/" + Uri.encode(key)
    fun genre(name: String) = "genre/" + Uri.encode(name)
    fun folder(path: String) = "folder/" + Uri.encode(path)
    fun playlist(id: Long) = "playlist/$id"
    const val FAVORITES = "playlist/-1"
}

private val tabs = listOf(Routes.HOME to "Home", Routes.LIBRARY to "Library", Routes.SEARCH to "Search", Routes.RADIO to "Radio", Routes.SETTINGS to "Settings")

@Composable
fun UnpausedRoot(vm: MainViewModel) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    UnpausedTheme(settings.themeMode) {
        val onboarded by vm.onboarded.collectAsStateWithLifecycle()
        when (onboarded) {
            null -> Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
            false -> OnboardingScreen(vm)
            true -> MainScaffold(vm)
        }
    }
}

@Composable
private fun MainScaffold(vm: MainViewModel) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm.message) {
        vm.message?.let { snackbar.showSnackbar(it); vm.message = null }
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (route != Routes.NOW) {
                Column(Modifier.background(MaterialTheme.colorScheme.background).navigationBarsPadding()) {
                    MiniPlayer(vm) { nav.navigate(Routes.NOW) { launchSingleTop = true } }
                    BottomTabs(route) { target ->
                        nav.navigate(target) {
                            popUpTo(Routes.HOME) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = Routes.HOME, modifier = Modifier.padding(padding)) {
            composable(Routes.HOME) { HomeScreen(vm, nav) }
            composable(Routes.LIBRARY) { LibraryScreen(vm, nav) }
            composable(Routes.SEARCH) { SearchScreen(vm, nav) }
            composable(Routes.RADIO) { RadioScreen(vm) }
            composable(Routes.SETTINGS) { SettingsScreen(vm, nav) }
            composable(Routes.INTERRUPTIONS) { InterruptionSettingsScreen(vm, nav) }
            composable(Routes.AUDIO) { AudioSettingsScreen(vm, nav) }
            composable(Routes.EQ) { EqualizerScreen(vm, nav) }
            composable(Routes.DIAGNOSTICS) { DiagnosticsScreen(vm, nav) }
            composable(Routes.SYNC) { SyncScreen(vm, nav) }
            composable(Routes.FOLDERS) { FoldersScreen(vm, nav) }
            composable(Routes.ABOUT) { AboutScreen(nav) }
            composable(
                Routes.NOW,
                enterTransition = { slideInVertically { it } },
                exitTransition = { slideOutVertically { it } },
                popExitTransition = { slideOutVertically { it } },
            ) { NowPlayingScreen(vm, nav) }
            composable("album/{id}") { e -> AlbumScreen(vm, nav, Uri.decode(e.arguments?.getString("id").orEmpty())) }
            composable("artist/{key}") { e -> ArtistScreen(vm, nav, Uri.decode(e.arguments?.getString("key").orEmpty())) }
            composable("genre/{name}") { e -> GroupScreen(vm, nav, genre = Uri.decode(e.arguments?.getString("name").orEmpty()), folder = null) }
            composable("folder/{path}") { e -> GroupScreen(vm, nav, genre = null, folder = Uri.decode(e.arguments?.getString("path").orEmpty())) }
            composable("playlist/{id}") { e -> PlaylistScreen(vm, nav, e.arguments?.getString("id")?.toLongOrNull() ?: -1L) }
        }
    }
    vm.addToPlaylist?.let { tracks -> AddToPlaylistDialog(vm, tracks) { vm.addToPlaylist = null } }
}

@Composable
private fun BottomTabs(route: String?, onSelect: (String) -> Unit) {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
        tabs.forEach { (target, label) ->
            val selected = route == target
            Column(
                Modifier.weight(1f).clickable { onSelect(target) }.padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.size(5.dp).clip(CircleShape).background(if (selected) LocalExtra.current.accent else MaterialTheme.colorScheme.background))
                Spacer(Modifier.height(6.dp))
                MonoLabel(label, color = if (selected) MaterialTheme.colorScheme.onBackground else LocalExtra.current.dim)
            }
        }
    }
}

@Composable
private fun MiniPlayer(vm: MainViewModel, open: () -> Unit) {
    val state by vm.player.state.collectAsStateWithLifecycle()
    val progress by vm.player.progress.collectAsStateWithLifecycle()
    val item = state.item ?: return
    val icy by vm.c.playbackStatus.icyTitle.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxWidth().clickable(onClick = open)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        if (!item.isRadio && progress.durationMs > 0) {
            Box(Modifier.fillMaxWidth().height(2.dp).background(MaterialTheme.colorScheme.outlineVariant)) {
                Box(Modifier.fillMaxWidth((progress.positionMs.toFloat() / progress.durationMs).coerceIn(0f, 1f)).height(2.dp).background(LocalExtra.current.accent))
            }
        }
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Artwork(item.artworkUri?.toString()?.let { if (it.startsWith("file://")) it.removePrefix("file://") else it }, Modifier.size(44.dp), corner = 8.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                Text(
                    if (item.isRadio) icy ?: item.artist else item.artist, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.dim,
                )
            }
            IconButton(onClick = { vm.player.togglePlay() }) {
                Icon(if (state.playWhenReady && !state.ended) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, contentDescription = if (state.playWhenReady) "Pause" else "Play")
            }
            if (!item.isRadio) IconButton(onClick = { vm.player.next() }) { Icon(Icons.Rounded.SkipNext, contentDescription = "Next") }
        }
    }
}

@Composable
private fun AddToPlaylistDialog(vm: MainViewModel, tracks: List<app.unpaused.data.db.TrackEntity>, dismiss: () -> Unit) {
    val playlists by vm.c.playlists.playlists().collectAsStateWithLifecycle(emptyList())
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Add ${tracks.size} to playlist") },
        text = {
            Column {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("New playlist name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.height(240.dp)) {
                    items(playlists, key = { it.id }) { p ->
                        Text(
                            p.name, style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.fillMaxWidth().clickable { vm.addTracksToPlaylist(p.id, null, tracks); dismiss() }.padding(vertical = 12.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { vm.addTracksToPlaylist(null, name, tracks); dismiss() }) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } },
    )
}

fun NavHostController.back() {
    if (!popBackStack()) navigate(Routes.HOME)
}
