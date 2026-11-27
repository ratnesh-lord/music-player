package app.unpaused.ui.screens

import android.media.AudioManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.navigation.NavHostController
import app.unpaused.core.audio.AudioQuality
import app.unpaused.core.audio.ReplayGain
import app.unpaused.core.audio.ReplayGainInfo
import app.unpaused.core.audio.ReplayGainMode
import app.unpaused.core.lyrics.LrcParser
import app.unpaused.core.lyrics.Lyrics
import app.unpaused.data.db.TrackEntity
import app.unpaused.playback.MediaIds
import app.unpaused.playback.OutputInfoReader
import app.unpaused.ui.MainViewModel
import app.unpaused.ui.Routes
import app.unpaused.ui.back
import app.unpaused.ui.components.Artwork
import app.unpaused.ui.components.Chip
import app.unpaused.ui.components.DotSeekBar
import app.unpaused.ui.components.InfoCard
import app.unpaused.ui.components.KeyValue
import app.unpaused.ui.components.MenuAction
import app.unpaused.ui.components.MonoLabel
import app.unpaused.ui.components.OverflowMenu
import app.unpaused.ui.components.TrackRow
import app.unpaused.ui.components.formatTime
import app.unpaused.ui.theme.LocalExtra
import kotlin.math.abs

private enum class Panel { LYRICS, QUEUE, AUDIO }

@Composable
fun NowPlayingScreen(vm: MainViewModel, nav: NavHostController) {
    val state by vm.player.state.collectAsStateWithLifecycle()
    val progress by vm.player.progress.collectAsStateWithLifecycle()
    val favs by vm.favoriteKeys.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val icy by vm.c.playbackStatus.icyTitle.collectAsStateWithLifecycle()
    val source by vm.c.playbackStatus.source.collectAsStateWithLifecycle()
    val sleep by vm.c.playbackStatus.sleep.collectAsStateWithLifecycle()
    val focus by vm.c.playbackStatus.focus.collectAsStateWithLifecycle()
    var panel by rememberSaveable { mutableStateOf(Panel.QUEUE) }
    var sleepDialog by remember { mutableStateOf(false) }
    var outputDialog by remember { mutableStateOf(false) }

    val item = state.item
    if (item == null) {
        Column(Modifier.fillMaxSize().padding(24.dp)) {
            IconButton(onClick = { nav.back() }) { Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "Close") }
            Text("Nothing playing", style = MaterialTheme.typography.headlineSmall)
        }
        return
    }
    val trackId = MediaIds.trackId(item.mediaId)
    val track by produceState<TrackEntity?>(null, trackId) { value = trackId?.let { vm.c.library.track(it) } }
    val lyrics by produceState<Lyrics?>(null, track?.uri) { value = track?.let { vm.c.lyrics.load(it) } }
    val favorite = item.trackKey != null && item.trackKey in favs
    val listState = rememberLazyListState()

    LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { nav.back() }) { Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "Close player") }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    MonoLabel(if (item.isRadio) "Live radio" else "Now playing", color = MaterialTheme.colorScheme.onBackground)
                    val s = sleep
                    if (s != null) MonoLabel(if (s.endOfTrack) "Sleep: end of track" else "Sleep at " + java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(s.endsAtMs ?: 0)), color = LocalExtra.current.accent)
                }
                OverflowMenu(
                    buildList {
                        add(MenuAction("Sleep timer…") { sleepDialog = true })
                        add(MenuAction("Audio output…") { outputDialog = true })
                        track?.let { t ->
                            add(MenuAction("Add to playlist…") { vm.addToPlaylist = listOf(t) })
                            add(MenuAction("Go to album") { nav.navigate(Routes.album(t.albumId)) })
                            add(MenuAction("Go to artist") { nav.navigate(Routes.artist(t.artistKey)) })
                        }
                    },
                )
            }
        }
        item {
            Artwork(
                item.artworkUri?.toString()?.let { if (it.startsWith("file://")) it.removePrefix("file://") else it },
                Modifier.padding(horizontal = 28.dp, vertical = 8.dp).fillMaxWidth().aspectRatio(1f), corner = 20.dp,
            )
        }
        item {
            Row(Modifier.padding(horizontal = 24.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(item.title, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val sub = if (item.isRadio) icy ?: item.artist else listOf(item.artist, item.album).filter { it.isNotBlank() }.joinToString(" · ")
                    Text(sub, style = MaterialTheme.typography.bodyMedium, color = LocalExtra.current.dim, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    source?.let { q ->
                        Spacer(Modifier.height(6.dp))
                        Text(AudioQuality.describe(q), style = MaterialTheme.typography.labelMedium, color = LocalExtra.current.accent)
                    }
                }
                if (item.trackKey != null) IconButton(onClick = { vm.toggleFavorite(item.trackKey, item.title, item.artist) }) {
                    Icon(
                        if (favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                        contentDescription = if (favorite) "Remove from favourites" else "Add to favourites",
                        tint = if (favorite) LocalExtra.current.accent else MaterialTheme.colorScheme.onBackground,
                    )
                }
            }
        }
        item {
            Column(Modifier.padding(horizontal = 20.dp)) {
                DotSeekBar(progress.positionMs, progress.bufferedMs, progress.durationMs, { vm.player.seekTo(it) }, enabled = !item.isRadio)
                Row {
                    MonoLabel(formatTime(progress.positionMs), Modifier.weight(1f))
                    MonoLabel(if (item.isRadio) "Live" else formatTime(progress.durationMs))
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { vm.player.setShuffle(!state.shuffle) }, enabled = !item.isRadio) {
                    Icon(Icons.Rounded.Shuffle, contentDescription = "Shuffle", tint = if (state.shuffle) LocalExtra.current.accent else MaterialTheme.colorScheme.onBackground)
                }
                IconButton(onClick = { vm.player.previous() }, enabled = !item.isRadio) { Icon(Icons.Rounded.SkipPrevious, contentDescription = "Previous", modifier = Modifier.size(32.dp)) }
                Box(
                    Modifier.size(72.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onBackground).clickable { vm.player.togglePlay() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (state.playWhenReady && !state.ended) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (state.playWhenReady) "Pause" else "Play",
                        tint = MaterialTheme.colorScheme.background, modifier = Modifier.size(36.dp),
                    )
                }
                IconButton(onClick = { vm.player.next() }, enabled = !item.isRadio) { Icon(Icons.Rounded.SkipNext, contentDescription = "Next", modifier = Modifier.size(32.dp)) }
                IconButton(onClick = { vm.player.cycleRepeat() }, enabled = !item.isRadio) {
                    Icon(
                        if (state.repeatMode == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                        contentDescription = "Repeat",
                        tint = if (state.repeatMode != Player.REPEAT_MODE_OFF) LocalExtra.current.accent else MaterialTheme.colorScheme.onBackground,
                    )
                }
            }
        }
        if (state.buffering || state.error != null || focus.waitingFor != null) item {
            Text(
                state.error?.let { "Playback error: $it" } ?: focus.waitingFor ?: "Buffering…",
                style = MaterialTheme.typography.labelMedium, color = LocalExtra.current.accent,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp), textAlign = TextAlign.Center,
            )
        }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                app.unpaused.core.focus.FocusMode.entries.forEach { m -> Chip(modeName(m), m == settings.focusMode, { vm.setFocusMode(m) }) }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Panel.entries.forEach { p -> Chip(p.name, p == panel, { panel = p }) }
            }
        }
        when (panel) {
            Panel.LYRICS -> {
                val l = lyrics
                if (l == null || l.lines.isEmpty()) {
                    item {
                        Text(
                            if (item.isRadio) "Radio streams don't carry lyrics." else "No lyrics in this file. Unpaused reads lyrics embedded in tags and .lrc files next to the song (in folders you added).",
                            style = MaterialTheme.typography.bodyMedium, color = LocalExtra.current.dim, modifier = Modifier.padding(24.dp),
                        )
                    }
                } else {
                    val current = if (l.synced) LrcParser.lineIndexAt(l, progress.positionMs) else -1
                    itemsIndexed(l.lines) { i, line ->
                        val active = i == current
                        Text(
                            line.text.ifBlank { "♪" },
                            style = if (active) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
                            color = when {
                                !l.synced -> MaterialTheme.colorScheme.onBackground
                                active -> MaterialTheme.colorScheme.onBackground
                                else -> LocalExtra.current.dim.copy(alpha = if (abs(i - current) < 4) 0.9f else 0.5f)
                            },
                            modifier = Modifier.fillMaxWidth()
                                .clickable(enabled = line.timeMs != null) { line.timeMs?.let { vm.player.seekTo(it) } }
                                .padding(horizontal = 24.dp, vertical = 6.dp),
                        )
                    }
                }
            }
            Panel.QUEUE -> {
                if (state.queue.isEmpty()) item { Text("The queue is empty.", modifier = Modifier.padding(24.dp)) }
                itemsIndexed(state.queue, key = { i, q -> "q$i-${q.mediaId}" }) { i, q ->
                    TrackRow(
                        title = q.title, subtitle = q.artist, artwork = null, playing = i == state.currentIndex,
                        actions = buildList {
                            add(MenuAction("Remove from queue") { vm.player.remove(i) })
                            if (i > 0) add(MenuAction("Move up") { vm.player.move(i, i - 1) })
                            if (i < state.queue.size - 1) add(MenuAction("Move down") { vm.player.move(i, i + 1) })
                            if (i != state.currentIndex && state.currentIndex >= 0) add(MenuAction("Play next") { vm.player.move(i, if (i < state.currentIndex) state.currentIndex else state.currentIndex + 1) })
                        },
                        onClick = { vm.player.skipTo(i) },
                    )
                }
            }
            Panel.AUDIO -> item { AudioPanel(vm, track) }
        }
    }

    LaunchedEffect(panel, lyrics) {
        // Keep the player controls in view when switching panels.
        if (listState.firstVisibleItemIndex > 6) listState.scrollToItem(6)
    }

    if (sleepDialog) SleepDialog(sleep != null, { sleepDialog = false }) { vm.sleepTimer(it) }
    if (outputDialog) OutputDialog(vm) { outputDialog = false }
}

@Composable
private fun AudioPanel(vm: MainViewModel, track: TrackEntity?) {
    val source by vm.c.playbackStatus.source.collectAsStateWithLifecycle()
    val out by vm.c.playbackStatus.audioTrack.collectAsStateWithLifecycle()
    val device by vm.c.playbackStatus.output.collectAsStateWithLifecycle()
    val effects by vm.c.playbackStatus.effects.collectAsStateWithLifecycle()
    val focus by vm.c.playbackStatus.focus.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val mixerRate = remember { context.getSystemService(AudioManager::class.java)?.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE) }

    InfoCard {
        Column {
            MonoLabel("Source file", color = MaterialTheme.colorScheme.onBackground)
            Spacer(Modifier.height(6.dp))
            val q = source
            if (q == null) Text("Not playing yet", style = MaterialTheme.typography.bodySmall) else {
                KeyValue("Codec", q.codec.label + if (q.codec.lossless) " (lossless)" else " (lossy)")
                q.bitDepth?.let { KeyValue("Bit depth", "$it-bit") }
                q.sampleRateHz?.let { KeyValue("Sample rate", AudioQuality.formatSampleRate(it)) }
                q.channels?.let { KeyValue("Channels", if (it == 1) "Mono" else if (it == 2) "Stereo" else "$it ch") }
                q.bitrateBps?.let { KeyValue("Bitrate", AudioQuality.formatBitrate(it)) }
                KeyValue("Tier", AudioQuality.tierLabel(AudioQuality.tier(q)), highlight = true)
            }
            track?.let { t -> KeyValue("File", "${t.fileName} · ${"%.1f".format(t.sizeBytes / 1_048_576.0)} MB") }
        }
    }
    InfoCard {
        Column {
            MonoLabel("Output path", color = MaterialTheme.colorScheme.onBackground)
            Spacer(Modifier.height(6.dp))
            val o = out
            if (o != null) {
                KeyValue("Decoded to", "${o.encoding} · ${AudioQuality.formatSampleRate(o.sampleRateHz)} · ${o.channels} ch" + if (o.offload) " · offload" else "")
            }
            mixerRate?.toIntOrNull()?.let { KeyValue("Android mixer", AudioQuality.formatSampleRate(it) + " (reported)") }
            val d = device
            if (d != null) {
                KeyValue("Device", listOfNotNull(d.type, d.productName).joinToString(" · "))
                KeyValue("Rates", if (d.sampleRates.isEmpty()) "Any (not listed)" else d.sampleRates.joinToString { AudioQuality.formatSampleRate(it) })
                KeyValue(
                    "Bit-perfect",
                    when {
                        d.bitPerfectFormats == null -> "Can't check before Android 14"
                        d.bitPerfectFormats.isEmpty() -> "Not offered by this output"
                        else -> "Offered: " + d.bitPerfectFormats.joinToString() + " (not used by Unpaused v1)"
                    },
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                buildString {
                    append("Audio goes through Android's mixer, which converts it to the output's rate and applies system volume. ")
                    if (settings.hiResOutput) append("24-bit and higher files are passed to Android as 32-bit float, so no bits are cut before the mixer. ")
                    if (d?.isBluetooth == true) append("Bluetooth re-encodes the audio with the codec your headphones negotiated (SBC, AAC, LDAC…), which is lossy.")
                },
                style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.dim,
            )
        }
    }
    InfoCard {
        Column {
            MonoLabel("Processing", color = MaterialTheme.colorScheme.onBackground)
            Spacer(Modifier.height(6.dp))
            KeyValue("Effects", effects.engine + (effects.note?.let { " · $it" } ?: ""))
            val rg = track?.let { t ->
                if (settings.replayGainMode == ReplayGainMode.OFF) null
                else ReplayGain.decide(ReplayGainInfo(t.rgTrackGain, t.rgTrackPeak, t.rgAlbumGain, t.rgAlbumPeak), settings.replayGainSettings())
            }
            KeyValue("ReplayGain", rg?.let { "%+.1f dB (%s)".format(it.totalDb, it.source.name.lowercase()) } ?: "Off")
            KeyValue("Audio focus", modeName(focus.mode) + if (focus.holdsFocus) " · holding focus" else " · not holding focus")
            if (focus.ducked) KeyValue("Ducked", "Volume lowered for another app", highlight = true)
        }
    }
}

@Composable
private fun SleepDialog(active: Boolean, dismiss: () -> Unit, set: (Int) -> Unit) {
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Sleep timer") },
        text = {
            Column {
                listOf(15, 30, 45, 60, 90).forEach { m ->
                    Text("$m minutes", Modifier.fillMaxWidth().clickable { set(m); dismiss() }.padding(vertical = 12.dp))
                }
                Text("End of this track", Modifier.fillMaxWidth().clickable { set(-1); dismiss() }.padding(vertical = 12.dp))
                Text("Music fades out over the last 15 seconds.", style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.dim)
            }
        },
        confirmButton = { if (active) TextButton(onClick = { set(0); dismiss() }) { Text("Turn off") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("Close") } },
    )
}

@Composable
private fun OutputDialog(vm: MainViewModel, dismiss: () -> Unit) {
    val context = LocalContext.current
    val devices = remember { context.getSystemService(AudioManager::class.java)?.let { OutputInfoReader.selectable(it) } ?: emptyList() }
    val current by vm.c.playbackStatus.output.collectAsStateWithLifecycle()
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Audio output") },
        text = {
            Column {
                Text("Automatic (Android decides)", Modifier.fillMaxWidth().clickable { vm.setOutput(-1); dismiss() }.padding(vertical = 12.dp))
                devices.forEach { d ->
                    Row(Modifier.fillMaxWidth().clickable { vm.setOutput(d.id); dismiss() }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(d.label, Modifier.weight(1f))
                        if (current?.deviceId == d.id) Box(Modifier.size(8.dp).clip(CircleShape).background(LocalExtra.current.accent))
                    }
                }
                Text(
                    "Choosing an output is a preference: Android can still route audio elsewhere (for example during a call).",
                    style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.dim,
                )
            }
        },
        confirmButton = { TextButton(onClick = dismiss) { Text("Close") } },
    )
}
