package app.unpaused.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import app.unpaused.core.audio.AudioCodec
import app.unpaused.data.db.TrackEntity
import app.unpaused.ui.MainViewModel
import app.unpaused.ui.Routes
import app.unpaused.ui.back
import app.unpaused.ui.components.Artwork
import app.unpaused.ui.components.Chip
import app.unpaused.ui.components.ClickRow
import app.unpaused.ui.components.EmptyState
import app.unpaused.ui.components.MenuAction
import app.unpaused.ui.components.MonoLabel
import app.unpaused.ui.components.OverflowMenu
import app.unpaused.ui.components.PillButton
import app.unpaused.ui.components.TrackRow
import app.unpaused.ui.components.formatTime
import app.unpaused.ui.theme.LocalExtra
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@Composable
fun ScreenTopBar(title: String, nav: NavHostController, actions: List<MenuAction> = emptyList()) {
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { nav.back() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
        Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (actions.isNotEmpty()) OverflowMenu(actions)
    }
}

/** Short codec badge; only lossless codecs are labelled as such, never inferred from file size. */
fun TrackEntity.badge(): String? {
    val c = codec?.let { runCatching { AudioCodec.valueOf(it) }.getOrNull() } ?: return null
    return when {
        c.lossless && ((bitDepth ?: 0) > 16 || (sampleRate ?: 0) > 48_000) -> c.label + " HI-RES"
        c.lossless -> c.label
        else -> null
    }
}

fun trackActions(vm: MainViewModel, nav: NavHostController, t: TrackEntity, favorite: Boolean): List<MenuAction> = listOf(
    MenuAction("Play next") { vm.player.playNext(listOf(t)) },
    MenuAction("Add to queue") { vm.player.addToQueue(listOf(t)) },
    MenuAction("Add to playlist…") { vm.addToPlaylist = listOf(t) },
    MenuAction(if (favorite) "Remove from favourites" else "Add to favourites") { vm.toggleFavorite(t.trackKey, t.title, t.artist) },
    MenuAction("Go to album") { nav.navigate(Routes.album(t.albumId)) },
    MenuAction("Go to artist") { nav.navigate(Routes.artist(t.artistKey)) },
)

fun LazyListScope.trackItems(vm: MainViewModel, nav: NavHostController, tracks: List<TrackEntity>, favorites: Set<String>, currentId: String?, showArt: Boolean = true) {
    itemsIndexed(tracks, key = { _, t -> t.id }) { i, t ->
        TrackRow(
            title = t.title,
            subtitle = listOf(t.artist, formatTime(t.durationMs)).joinToString(" · "),
            artwork = if (showArt) t.artworkPath else null,
            playing = currentId == "track:${t.id}",
            badge = t.badge(),
            actions = trackActions(vm, nav, t, t.trackKey in favorites),
            onClick = { vm.playTracks(tracks, i) },
        )
    }
}

private val libraryTabs = listOf("Songs", "Albums", "Artists", "Genres", "Folders", "Playlists", "Favourites")

@Composable
fun LibraryScreen(vm: MainViewModel, nav: NavHostController) {
    var tab by rememberSaveable { mutableStateOf(1) }
    Column(Modifier.fillMaxSize()) {
        Text("LIBRARY", style = MaterialTheme.typography.displaySmall, modifier = Modifier.padding(start = 20.dp, top = 20.dp, bottom = 8.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            libraryTabs.forEachIndexed { i, name -> Chip(name, i == tab, { tab = i }) }
        }
        when (tab) {
            0 -> SongsTab(vm, nav)
            1 -> AlbumsTab(vm, nav)
            2 -> ArtistsTab(vm, nav)
            3 -> GroupsTab(vm, nav, genres = true)
            4 -> GroupsTab(vm, nav, genres = false)
            5 -> PlaylistsTab(vm, nav)
            else -> PlaylistScreenContent(vm, nav, -1L, showHeader = false)
        }
    }
}

@Composable
private fun SongsTab(vm: MainViewModel, nav: NavHostController) {
    val tracks by remember { vm.c.library.tracks() }.collectAsStateWithLifecycle(emptyList())
    val favs by vm.favoriteKeys.collectAsStateWithLifecycle()
    val state by vm.player.state.collectAsStateWithLifecycle()
    if (tracks.isEmpty()) {
        EmptyState("No songs", "Scan your music from Home or add a folder in Settings.")
        return
    }
    LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
        item {
            Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                PillButton("Play", { vm.playTracks(tracks) })
                PillButton("Shuffle", { vm.playTracks(tracks, shuffle = true) }, filled = false)
                Spacer(Modifier.weight(1f))
                MonoLabel("${tracks.size} songs")
            }
        }
        trackItems(vm, nav, tracks, favs, state.item?.mediaId)
    }
}

@Composable
private fun AlbumsTab(vm: MainViewModel, nav: NavHostController) {
    val albums by remember { vm.c.library.albums() }.collectAsStateWithLifecycle(emptyList())
    if (albums.isEmpty()) {
        EmptyState("No albums", "Albums appear here after a scan.")
        return
    }
    LazyVerticalGrid(GridCells.Adaptive(150.dp), contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        items(albums, key = { it.albumId }) { a ->
            Column(Modifier.clickable { nav.navigate(Routes.album(a.albumId)) }) {
                Artwork(a.artworkPath, Modifier.fillMaxWidth().aspectRatio(1f))
                Spacer(Modifier.height(6.dp))
                Text(a.album, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                Text(a.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.dim)
            }
        }
    }
}

@Composable
private fun ArtistsTab(vm: MainViewModel, nav: NavHostController) {
    val artists by remember { vm.c.library.artists() }.collectAsStateWithLifecycle(emptyList())
    LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
        items(artists, key = { it.artistKey }) { a ->
            TrackRow(a.artist, "${a.albumCount} albums · ${a.trackCount} songs", a.artworkPath, onClick = { nav.navigate(Routes.artist(a.artistKey)) })
        }
    }
}

@Composable
private fun GroupsTab(vm: MainViewModel, nav: NavHostController, genres: Boolean) {
    val groups by remember(genres) { if (genres) vm.c.library.genres() else vm.c.library.folders() }.collectAsStateWithLifecycle(emptyList())
    LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
        items(groups, key = { it.name }) { g ->
            TrackRow(g.name, "${g.trackCount} songs", g.artworkPath, onClick = { nav.navigate(if (genres) Routes.genre(g.name) else Routes.folder(g.name)) })
        }
    }
}

@Composable
private fun PlaylistsTab(vm: MainViewModel, nav: NavHostController) {
    val playlists by remember { vm.c.playlists.playlists() }.collectAsStateWithLifecycle(emptyList())
    var creating by remember { mutableStateOf(false) }
    LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
        item { Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) { PillButton("New playlist", { creating = true }) } }
        item { ClickRow("Favourites", "Songs you've hearted", onClick = { nav.navigate(Routes.FAVORITES) }) }
        items(playlists, key = { it.id }) { p -> ClickRow(p.name, onClick = { nav.navigate(Routes.playlist(p.id)) }) }
    }
    if (creating) NameDialog("New playlist", "", { creating = false }) { name -> vm.addTracksToPlaylist(null, name, emptyList()) }
}

@Composable
fun NameDialog(title: String, initial: String, dismiss: () -> Unit, confirm: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(title) },
        text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Name") }) },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { confirm(name.trim()); dismiss() }) { Text("Save") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } },
    )
}

@Composable
private fun CollectionHeader(title: String, subtitle: String, artwork: Any?, onPlay: () -> Unit, onShuffle: () -> Unit) {
    Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
        Artwork(artwork, Modifier.size(120.dp))
        Spacer(Modifier.width(16.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.dim)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton("Play", onPlay)
                PillButton("Shuffle", onShuffle, filled = false)
            }
        }
    }
}

@Composable
fun AlbumScreen(vm: MainViewModel, nav: NavHostController, albumId: String) {
    val tracks by remember(albumId) { vm.c.library.albumTracks(albumId) }.collectAsStateWithLifecycle(emptyList())
    val favs by vm.favoriteKeys.collectAsStateWithLifecycle()
    val state by vm.player.state.collectAsStateWithLifecycle()
    val first = tracks.firstOrNull()
    LazyColumn(Modifier.fillMaxSize()) {
        item { ScreenTopBar(first?.album ?: "Album", nav, listOf(MenuAction("Add all to playlist…") { vm.addToPlaylist = tracks })) }
        if (first != null) item {
            val year = tracks.mapNotNull { it.year }.maxOrNull()
            CollectionHeader(
                first.album,
                listOfNotNull(first.albumArtist ?: first.artist, year?.toString(), "${tracks.size} songs", formatTime(tracks.sumOf { it.durationMs })).joinToString(" · "),
                first.artworkPath, { vm.playTracks(tracks) }, { vm.playTracks(tracks, shuffle = true) },
            )
        }
        trackItems(vm, nav, tracks, favs, state.item?.mediaId, showArt = false)
    }
}

@Composable
fun ArtistScreen(vm: MainViewModel, nav: NavHostController, artistKey: String) {
    val tracks by remember(artistKey) { vm.c.library.artistTracks(artistKey) }.collectAsStateWithLifecycle(emptyList())
    val favs by vm.favoriteKeys.collectAsStateWithLifecycle()
    val state by vm.player.state.collectAsStateWithLifecycle()
    val first = tracks.firstOrNull()
    LazyColumn(Modifier.fillMaxSize()) {
        item { ScreenTopBar(first?.artist ?: "Artist", nav) }
        if (first != null) item {
            CollectionHeader(first.artist, "${tracks.map { it.albumId }.distinct().size} albums · ${tracks.size} songs", first.artworkPath, { vm.playTracks(tracks) }, { vm.playTracks(tracks, shuffle = true) })
        }
        trackItems(vm, nav, tracks, favs, state.item?.mediaId)
    }
}

@Composable
fun GroupScreen(vm: MainViewModel, nav: NavHostController, genre: String?, folder: String?) {
    val tracks by remember(genre, folder) {
        when {
            genre != null -> vm.c.library.genreTracks(genre)
            folder != null -> vm.c.library.folderTracks(folder)
            else -> flowOf(emptyList())
        }
    }.collectAsStateWithLifecycle(emptyList())
    val favs by vm.favoriteKeys.collectAsStateWithLifecycle()
    val state by vm.player.state.collectAsStateWithLifecycle()
    val title = genre ?: folder?.substringAfterLast('/') ?: ""
    LazyColumn(Modifier.fillMaxSize()) {
        item { ScreenTopBar(title, nav) }
        item { CollectionHeader(title, "${tracks.size} songs", tracks.firstOrNull()?.artworkPath, { vm.playTracks(tracks) }, { vm.playTracks(tracks, shuffle = true) }) }
        trackItems(vm, nav, tracks, favs, state.item?.mediaId)
    }
}

@Composable
fun PlaylistScreen(vm: MainViewModel, nav: NavHostController, id: Long) {
    Column(Modifier.fillMaxSize()) { PlaylistScreenContent(vm, nav, id, showHeader = true) }
}

/** id = -1 shows Favourites. Items are matched to local files by track key; missing ones are listed greyed out. */
@Composable
fun PlaylistScreenContent(vm: MainViewModel, nav: NavHostController, id: Long, showHeader: Boolean) {
    val favorites = id == -1L
    val playlist by remember(id) { if (favorites) flowOf(null) else vm.c.playlists.playlist(id) }.collectAsStateWithLifecycle(null)
    val entries by remember(id) {
        if (favorites) vm.c.favorites.favorites().map { list -> list.map { Triple(it.trackKey, it.title, it.artist) } }
        else vm.c.playlists.items(id).map { list -> list.map { Triple(it.trackKey, it.title, it.artist) } }
    }.collectAsStateWithLifecycle(emptyList())
    val local by remember(entries) { vm.c.library.byTrackKeys(entries.map { it.first }) }.collectAsStateWithLifecycle(emptyList())
    val favs by vm.favoriteKeys.collectAsStateWithLifecycle()
    var renaming by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val byKey = local.groupBy { it.trackKey }
    val title = if (favorites) "Favourites" else playlist?.name ?: "Playlist"
    LazyColumn(Modifier.fillMaxSize()) {
        if (showHeader) item {
            ScreenTopBar(
                title, nav,
                if (favorites) emptyList() else listOf(
                    MenuAction("Rename") { renaming = true },
                    MenuAction("Delete playlist") { scope.launch { vm.c.playlists.delete(id); nav.back() } },
                ),
            )
        }
        item {
            Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                PillButton("Play", { vm.playKeys(entries.map { it.first }) })
                PillButton("Shuffle", { vm.playKeys(entries.map { it.first }, shuffle = true) }, filled = false)
                Spacer(Modifier.weight(1f))
                MonoLabel("${entries.size} songs")
            }
        }
        if (entries.isEmpty()) item {
            EmptyState(if (favorites) "No favourites yet" else "Empty playlist", if (favorites) "Tap the heart on the player to add songs." else "Add songs from any song's ⋮ menu.")
        }
        itemsIndexed(entries, key = { i, e -> "$i-${e.first}" }) { i, (key, t, a) ->
            val track = byKey[key]?.firstOrNull()
            TrackRow(
                title = t,
                subtitle = if (track == null) "$a · not on this device" else a,
                artwork = track?.artworkPath,
                badge = track?.badge(),
                actions = buildList {
                    if (track != null) addAll(trackActions(vm, nav, track, key in favs))
                    if (!favorites) add(MenuAction("Remove from playlist") { scope.launch { vm.c.playlists.removeAt(id, i) } })
                    if (!favorites && i > 0) add(MenuAction("Move up") { scope.launch { vm.c.playlists.move(id, i, i - 1) } })
                    if (!favorites && i < entries.size - 1) add(MenuAction("Move down") { scope.launch { vm.c.playlists.move(id, i, i + 1) } })
                },
                onClick = { if (track != null) vm.playKeys(entries.map { it.first }, entries.take(i).count { byKey.containsKey(it.first) }) },
            )
        }
    }
    if (renaming) NameDialog("Rename playlist", playlist?.name.orEmpty(), { renaming = false }) { name -> scope.launch { vm.c.playlists.rename(id, name) } }
}
