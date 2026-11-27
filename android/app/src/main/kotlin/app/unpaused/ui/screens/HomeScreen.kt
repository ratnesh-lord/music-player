package app.unpaused.ui.screens

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import app.unpaused.core.focus.FocusMode
import app.unpaused.data.library.LibraryRepository
import app.unpaused.data.library.ScanState
import app.unpaused.ui.MainViewModel
import app.unpaused.ui.Routes
import app.unpaused.ui.components.Artwork
import app.unpaused.ui.components.Chip
import app.unpaused.ui.components.DotHeading
import app.unpaused.ui.components.EmptyState
import app.unpaused.ui.components.InfoCard
import app.unpaused.ui.components.MonoLabel
import app.unpaused.ui.components.PillButton
import app.unpaused.ui.components.SectionHeader
import app.unpaused.ui.components.TrackRow
import app.unpaused.ui.theme.LocalExtra
import java.text.DateFormat
import java.util.Date

@Composable
fun HomeScreen(vm: MainViewModel, nav: NavHostController) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val count by vm.c.library.count.collectAsStateWithLifecycle(0)
    val scan by vm.c.library.scanState.collectAsStateWithLifecycle()
    val albums by remember { vm.c.library.recentAlbums(16) }.collectAsStateWithLifecycle(emptyList())
    val recent by remember { vm.c.history.recent(8) }.collectAsStateWithLifecycle(emptyList())
    val kill by vm.lastKill.collectAsStateWithLifecycle()
    val focus by vm.c.playbackStatus.focus.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                DotHeading("Unpaused", Modifier.weight(1f))
                MonoLabel("$count tracks")
            }
        }
        val k = kill
        if (k != null && k.atMs > vm.dismissedKillAt) item {
            InfoCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    MonoLabel("Playback was stopped", color = LocalExtra.current.accent)
                    Text(
                        "At ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(k.atMs))} Android closed Unpaused while it was playing. " +
                            "If this keeps happening, set Unpaused's battery usage to “Unrestricted”.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PillButton("Battery settings", {
                            runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                        })
                        PillButton("Dismiss", { vm.dismissedKillAt = k.atMs }, filled = false)
                    }
                }
            }
        }
        item { ModeCard(settings.focusMode, focus.waitingFor, onMode = vm::setFocusMode, onMore = { nav.navigate(Routes.INTERRUPTIONS) }) }
        val s = scan
        if (s is ScanState.Running) item {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                MonoLabel(
                    when (s.phase) {
                        ScanState.Phase.MEDIA_STORE -> "Reading Android's music index"
                        ScanState.Phase.FOLDERS -> "Scanning your folders"
                        ScanState.Phase.DETAILS -> "Reading audio details ${s.done}/${s.total}"
                        ScanState.Phase.ARTWORK -> "Extracting artwork"
                    },
                )
                Spacer(Modifier.height(6.dp))
                if (s.total > 0) LinearProgressIndicator(progress = { s.done.toFloat() / s.total }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
        if (s is ScanState.Finished && s.notes.isNotEmpty()) item {
            InfoCard { Column { s.notes.forEach { Text(it, style = MaterialTheme.typography.bodySmall) } } }
        }
        if (count == 0 && s !is ScanState.Running) {
            item {
                EmptyState(
                    "No music yet",
                    "Allow access to your music, or add a folder in Settings → Library folders. Files never leave your phone.",
                    action = "Library folders",
                    onAction = { nav.navigate(Routes.FOLDERS) },
                )
            }
        } else {
            item {
                Row(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PillButton("Shuffle all", { vm.shuffleAll() })
                    PillButton("Rescan", { vm.rescan() }, filled = false)
                }
            }
            if (albums.isNotEmpty()) {
                item { SectionHeader("Recently added", action = "All", onAction = { nav.navigate(Routes.LIBRARY) }) }
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(albums, key = { it.albumId }) { a ->
                            Column(Modifier.width(140.dp).clickable { nav.navigate(Routes.album(a.albumId)) }) {
                                Artwork(a.artworkPath, Modifier.size(140.dp))
                                Spacer(Modifier.height(6.dp))
                                Text(a.album, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                                Text(a.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.dim)
                            }
                        }
                    }
                }
            }
            if (recent.isNotEmpty()) {
                item { SectionHeader("Recently played") }
                items(recent, key = { "r" + it.trackKey }) { r ->
                    TrackRow(r.title, r.artist, null, onClick = { vm.playKeys(recent.map { it.trackKey }, recent.indexOf(r)) })
                }
            }
        }
    }
}

@Composable
fun ModeCard(mode: FocusMode, waitingFor: String?, onMode: (FocusMode) -> Unit, onMore: () -> Unit) {
    InfoCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MonoLabel("When other apps play sound", Modifier.weight(1f))
                MonoLabel("More", Modifier.clickable(onClick = onMore))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FocusMode.entries.forEach { m -> Chip(modeName(m), m == mode, { onMode(m) }) }
            }
            Text(modeSummary(mode), style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.dim)
            if (waitingFor != null) Text(waitingFor, style = MaterialTheme.typography.labelMedium, color = LocalExtra.current.accent)
        }
    }
}

fun modeName(m: FocusMode) = when (m) {
    FocusMode.STANDARD -> "Standard"
    FocusMode.SMART -> "Smart"
    FocusMode.MIX -> "Mix"
}

fun modeSummary(m: FocusMode) = when (m) {
    FocusMode.STANDARD -> "Pauses when another app takes over the audio and waits for you, as Android recommends."
    FocusMode.SMART -> "Pauses when another app takes over the audio, then resumes on its own once that app has gone quiet (for up to the window you set)."
    FocusMode.MIX -> "Doesn't take part in audio focus, so games and other apps can't pause or fade Unpaused: both play together. Calls still pause it."
}

@Composable
fun OnboardingScreen(vm: MainViewModel) {
    val permission = LibraryRepository.audioPermission()
    val requestPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.finishOnboarding() }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.addFolder(uri)
        vm.finishOnboarding()
    }
    Box(Modifier.fillMaxSize().statusBarsPadding()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Spacer(Modifier.height(24.dp))
            Text("UNPAUSED", style = MaterialTheme.typography.displayLarge)
            Text("A music player that keeps playing.", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Your music stays on your phone. Unpaused plays the files you already have, plus internet radio. " +
                    "No ads, no account, nothing uploaded.",
                style = MaterialTheme.typography.bodyLarge, color = LocalExtra.current.dim,
            )
            InfoCard {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    MonoLabel("Why music stops on Android", color = LocalExtra.current.accent)
                    Text(
                        "When a game, a video or a payment app plays a sound, Android asks the music app to pause (“audio focus”). " +
                            "Unpaused lets you choose: Standard pauses, Smart resumes by itself once the other app is quiet, " +
                            "and Mix keeps playing alongside it. No app can override Android itself, and calls always pause music.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            PillButton(
                if (Build.VERSION.SDK_INT >= 33) "Allow access to music" else "Allow access to storage",
                { requestPermission.launch(permission) }, modifier = Modifier.fillMaxWidth(),
            )
            PillButton("Pick a music folder instead", { pickFolder.launch(null) }, modifier = Modifier.fillMaxWidth(), filled = false)
            PillButton("Skip for now", { vm.finishOnboarding() }, modifier = Modifier.fillMaxWidth(), filled = false)
        }
    }
}
