package app.unpaused.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import app.unpaused.core.library.TrackKey
import app.unpaused.data.db.RadioStationEntity
import app.unpaused.data.db.TrackEntity
import app.unpaused.ui.MainViewModel
import app.unpaused.ui.Routes
import app.unpaused.ui.components.Chip
import app.unpaused.ui.components.EmptyState
import app.unpaused.ui.components.MenuAction
import app.unpaused.ui.components.SectionHeader
import app.unpaused.ui.components.TrackRow
import app.unpaused.ui.theme.LocalExtra
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun SearchScreen(vm: MainViewModel, nav: NavHostController) {
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<TrackEntity>>(emptyList()) }
    val albums by remember { vm.c.library.albums() }.collectAsStateWithLifecycle(emptyList())
    val artists by remember { vm.c.library.artists() }.collectAsStateWithLifecycle(emptyList())
    val favs by vm.favoriteKeys.collectAsStateWithLifecycle()
    val state by vm.player.state.collectAsStateWithLifecycle()
    LaunchedEffect(query) {
        delay(200)
        results = if (query.isBlank()) emptyList() else vm.c.library.search(query)
    }
    val needle = TrackKey.normalize(query)
    val albumHits = if (needle.isEmpty()) emptyList() else albums.filter { TrackKey.normalize(it.album).contains(needle) }.take(8)
    val artistHits = if (needle.isEmpty()) emptyList() else artists.filter { TrackKey.normalize(it.artist).contains(needle) }.take(8)
    Column(Modifier.fillMaxSize()) {
        Text("SEARCH", style = MaterialTheme.typography.displaySmall, modifier = Modifier.padding(start = 20.dp, top = 20.dp, bottom = 8.dp))
        OutlinedTextField(
            value = query, onValueChange = { query = it }, singleLine = true,
            placeholder = { Text("Songs, albums, artists") },
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, contentDescription = "Clear") } },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
        LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
            if (artistHits.isNotEmpty()) {
                item { SectionHeader("Artists") }
                items(artistHits, key = { "a" + it.artistKey }) { a -> TrackRow(a.artist, "${a.trackCount} songs", a.artworkPath, onClick = { nav.navigate(Routes.artist(a.artistKey)) }) }
            }
            if (albumHits.isNotEmpty()) {
                item { SectionHeader("Albums") }
                items(albumHits, key = { "l" + it.albumId }) { a -> TrackRow(a.album, a.artist, a.artworkPath, onClick = { nav.navigate(Routes.album(a.albumId)) }) }
            }
            if (results.isNotEmpty()) {
                item { SectionHeader("Songs") }
                trackItems(vm, nav, results, favs, state.item?.mediaId)
            }
            if (query.isNotBlank() && results.isEmpty() && albumHits.isEmpty() && artistHits.isEmpty()) {
                item { EmptyState("Nothing found", "Search looks at titles, artists, albums, genres and file names on this device.") }
            }
        }
    }
}

private val radioTags = listOf("lofi", "jazz", "classical", "bollywood", "hindi", "rock", "electronic", "ambient", "hip hop", "news", "talk", "indie")

@Composable
fun RadioScreen(vm: MainViewModel) {
    var query by rememberSaveable { mutableStateOf("") }
    var tag by rememberSaveable { mutableStateOf<String?>(null) }
    var country by rememberSaveable { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<RadioStationEntity>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val favorites by remember { vm.c.radio.favorites() }.collectAsStateWithLifecycle(emptyList())
    val recent by remember { vm.c.radio.recent(10) }.collectAsStateWithLifecycle(emptyList())
    val settings by vm.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val countryCode = androidx.compose.ui.text.intl.Locale.current.region.takeIf { it.length == 2 }

    LaunchedEffect(query, tag, country) {
        delay(350)
        loading = true
        error = null
        results = runCatching {
            if (query.isBlank() && tag == null && !country) vm.c.radio.top()
            else vm.c.radio.search(query, tag, if (country) countryCode else null)
        }.getOrElse { error = "Couldn't reach the radio directory. Check your connection."; emptyList() }
        loading = false
    }

    if (!settings.radioEnabled) {
        EmptyState("Radio is off", "Turn it on in Settings → Sources.")
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        item { Text("RADIO", style = MaterialTheme.typography.displaySmall, modifier = Modifier.padding(start = 20.dp, top = 20.dp, bottom = 8.dp)) }
        item {
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true, placeholder = { Text("Station name") },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
        }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (countryCode != null) Chip("Near me ($countryCode)", country, { country = !country })
                radioTags.forEach { t -> Chip(t, tag == t, { tag = if (tag == t) null else t }) }
            }
        }
        if (query.isBlank() && tag == null && !country) {
            if (favorites.isNotEmpty()) {
                item { SectionHeader("Favourite stations") }
                items(favorites, key = { "f" + it.stationUuid }) { s -> StationRow(vm, s, true) { scope.launch { vm.c.radio.toggleFavorite(s) } } }
            }
            if (recent.isNotEmpty()) {
                item { SectionHeader("Recently played") }
                items(recent, key = { "r" + it.stationUuid }) { s -> StationRow(vm, s, s.favoritedAt > 0) { scope.launch { vm.c.radio.toggleFavorite(s) } } }
            }
            item { SectionHeader("Popular") }
        } else {
            item { SectionHeader(if (loading) "Searching…" else "${results.size} stations") }
        }
        error?.let { e -> item { Text(e, style = MaterialTheme.typography.bodyMedium, color = LocalExtra.current.accent, modifier = Modifier.padding(20.dp)) } }
        items(results, key = { it.stationUuid }) { s ->
            val fav = favorites.any { it.stationUuid == s.stationUuid }
            StationRow(vm, s, fav) { scope.launch { vm.c.radio.toggleFavorite(s) } }
        }
        item {
            Text(
                "Stations come from the community-run Radio Browser directory and stream from each station's own server. " +
                    "Quality is what the station reports.",
                style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.dim, modifier = Modifier.padding(20.dp),
            )
        }
    }
}

@Composable
private fun StationRow(vm: MainViewModel, s: RadioStationEntity, favorite: Boolean, toggleFavorite: () -> Unit) {
    val quality = listOfNotNull(s.codec?.uppercase(), s.bitrateKbps?.let { "$it kbps" }).joinToString(" ")
    val insecure = s.streamUrl.startsWith("http://")
    TrackRow(
        title = s.name,
        subtitle = listOfNotNull(quality.ifEmpty { null }, s.countryCode, s.tags?.split(',')?.take(2)?.joinToString(", ")).joinToString(" · "),
        artwork = s.faviconUrl?.takeIf { it.startsWith("https://") },
        badge = if (insecure) "HTTP" else null,
        actions = listOf(MenuAction(if (favorite) "Remove from favourites" else "Add to favourites", toggleFavorite)),
        onClick = { vm.playRadio(s) },
    )
}
