package app.unpaused.ui.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import app.unpaused.BuildConfig
import app.unpaused.core.audio.ReplayGainMode
import app.unpaused.core.diagnostics.Verdict
import app.unpaused.core.focus.FocusMode
import app.unpaused.data.settings.AppSettings
import app.unpaused.data.settings.EqEngine
import app.unpaused.data.settings.ThemeMode
import app.unpaused.data.sync.SyncStatus
import app.unpaused.ui.MainViewModel
import app.unpaused.ui.Routes
import app.unpaused.ui.components.Chip
import app.unpaused.ui.components.ClickRow
import app.unpaused.ui.components.InfoCard
import app.unpaused.ui.components.MonoLabel
import app.unpaused.ui.components.PillButton
import app.unpaused.ui.components.SectionHeader
import app.unpaused.ui.components.SliderRow
import app.unpaused.ui.components.SwitchRow
import app.unpaused.ui.theme.LocalExtra
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(vm: MainViewModel, nav: NavHostController) {
    val s by vm.settings.collectAsStateWithLifecycle()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { Text("SETTINGS", style = MaterialTheme.typography.displaySmall, modifier = Modifier.padding(start = 20.dp, top = 20.dp, bottom = 8.dp)) }
        item { ClickRow("Interruptions", "${modeName(s.focusMode)} · what happens when other apps play sound", onClick = { nav.navigate(Routes.INTERRUPTIONS) }) }
        item { ClickRow("Audio", "ReplayGain, hi-res output, skip silence", onClick = { nav.navigate(Routes.AUDIO) }) }
        item { ClickRow("Equaliser", if (s.eqEnabled) "On" else "Off", onClick = { nav.navigate(Routes.EQ) }) }
        item { ClickRow("Library folders", "Music access, folders, rescan", onClick = { nav.navigate(Routes.FOLDERS) }) }
        item { ClickRow("Sync & backup", if (s.syncEnabled) "Supabase sync on" else "Off · JSON backup available", onClick = { nav.navigate(Routes.SYNC) }) }
        item { ClickRow("Diagnostics", "Why did my music stop? Every pause, with its cause", onClick = { nav.navigate(Routes.DIAGNOSTICS) }) }
        item { SectionHeader("Appearance") }
        item {
            Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeMode.entries.forEach { m -> Chip(m.name.lowercase().replaceFirstChar { it.uppercase() }, s.themeMode == m, { vm.update { it.copy(themeMode = m) } }) }
            }
        }
        item { SectionHeader("Sources") }
        item { SwitchRow("Internet radio", "Browse stations from the Radio Browser directory", s.radioEnabled) { v -> vm.update { it.copy(radioEnabled = v) } } }
        item { ClickRow("About", "Version ${BuildConfig.VERSION_NAME} · licences", onClick = { nav.navigate(Routes.ABOUT) }) }
    }
}

@Composable
fun InterruptionSettingsScreen(vm: MainViewModel, nav: NavHostController) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val power = remember { context.getSystemService(android.os.PowerManager::class.java) }
    var unrestricted by remember { mutableStateOf(power?.isIgnoringBatteryOptimizations(context.packageName) == true) }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        unrestricted = power?.isIgnoringBatteryOptimizations(context.packageName) == true
        onPauseOrDispose { }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { ScreenTopBar("Interruptions", nav) }
        item {
            InfoCard {
                Text(
                    "Android lets one app at a time hold “audio focus”. When a game, video or payment app takes it, music apps are asked to pause " +
                        "(and since Android 12 the system also fades out the app that loses focus). No app can override that. " +
                        "What Unpaused can choose is how it takes part:",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        FocusMode.entries.forEach { m ->
            item {
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                    Chip(modeName(m) + if (m == FocusMode.SMART) " (recommended)" else "", s.focusMode == m, { vm.setFocusMode(m) })
                    Spacer(Modifier.height(4.dp))
                    Text(modeSummary(m), style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.dim)
                }
            }
        }
        item { SectionHeader("Smart mode") }
        item {
            SliderRow(
                "Keep waiting for up to", "${s.autoResumeWindowMin} min", s.autoResumeWindowMin.toFloat(), 1f..10f, steps = 8,
                subtitle = "Limited to 10 minutes: after that Android may stop the paused playback service.",
            ) { v -> vm.update { it.copy(autoResumeWindowMin = v.roundToInt()) } }
        }
        item {
            SliderRow("Resume after silence of", "${s.quietPeriodSec} s", s.quietPeriodSec.toFloat(), 1f..15f, steps = 13) { v ->
                vm.update { it.copy(quietPeriodSec = v.roundToInt()) }
            }
        }
        item { SectionHeader("Short interruptions") }
        item {
            SwitchRow("Lower volume instead of pausing", "For notifications and navigation prompts that allow it. Never during calls.", s.duckOnTransientLoss) { v ->
                vm.update { it.copy(duckOnTransientLoss = v) }
            }
        }
        item { SwitchRow("Resume after calls and short interruptions", null, s.resumeAfterInterruption) { v -> vm.update { it.copy(resumeAfterInterruption = v) } } }
        item {
            SwitchRow("Pause for voice chat", "Internet calls and in-game voice chat (phone calls always pause).", s.pauseForVoiceChat) { v ->
                vm.update { it.copy(pauseForVoiceChat = v) }
            }
        }
        item { SectionHeader("Headphones & recovery") }
        item { SwitchRow("Resume when headphones connect", "Off by default so music never starts by surprise.", s.resumeOnHeadsetConnect) { v -> vm.update { it.copy(resumeOnHeadsetConnect = v) } } }
        item {
            SliderRow(
                "Keep player ready after pausing", "${s.keepAliveAfterPauseMin} min", s.keepAliveAfterPauseMin.toFloat(), 1f..10f, steps = 8,
                subtitle = "How long the notification stays and Smart mode can resume. Media3 allows up to 10 minutes.",
            ) { v -> vm.update { it.copy(keepAliveAfterPauseMin = v.roundToInt()) } }
        }
        item { SwitchRow("Restore the queue when the app opens", null, s.restoreQueueOnLaunch) { v -> vm.update { it.copy(restoreQueueOnLaunch = v) } } }
        item { SectionHeader("Battery") }
        item {
            ClickRow(
                "Battery use: " + if (unrestricted) "Unrestricted" else "Optimised",
                if (unrestricted) "Android won't limit Unpaused in the background."
                else "Fine while music plays. When paused, Android may close Unpaused sooner. Tap, then choose Unrestricted for Unpaused.",
                onClick = { runCatching { context.startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } },
            )
        }
    }
}

@Composable
fun AudioSettingsScreen(vm: MainViewModel, nav: NavHostController) {
    val s by vm.settings.collectAsStateWithLifecycle()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { ScreenTopBar("Audio", nav) }
        item { SectionHeader("ReplayGain") }
        item {
            Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ReplayGainMode.entries.forEach { m -> Chip(m.name.lowercase().replaceFirstChar { it.uppercase() }, s.replayGainMode == m, { vm.update { it.copy(replayGainMode = m) } }) }
            }
        }
        item {
            SliderRow("Pre-amp (tagged files)", "%+.1f dB".format(s.replayGainPreampDb), s.replayGainPreampDb, -12f..12f, steps = 47) { v ->
                vm.update { it.copy(replayGainPreampDb = (v * 2).roundToInt() / 2f) }
            }
        }
        item {
            SliderRow("Files without tags", "%+.1f dB".format(s.replayGainUntaggedDb), s.replayGainUntaggedDb, -12f..12f, steps = 47) { v ->
                vm.update { it.copy(replayGainUntaggedDb = (v * 2).roundToInt() / 2f) }
            }
        }
        item { SwitchRow("Prevent clipping", "Limit gain so the tagged peak stays below full scale.", s.replayGainPreventClipping) { v -> vm.update { it.copy(replayGainPreventClipping = v) } } }
        item { SectionHeader("Output") }
        item {
            SwitchRow(
                "Hi-res output (32-bit float)",
                "Passes 24-bit and higher files to Android without cutting them to 16-bit. Speed changes and skip-silence don't apply to those files. Takes effect the next time the player starts.",
                s.hiResOutput,
            ) { v -> vm.update { it.copy(hiResOutput = v) } }
        }
        item { SwitchRow("Skip silence", "Removes silent gaps (not for gapless albums).", s.skipSilence) { v -> vm.update { it.copy(skipSilence = v) } } }
        item { SwitchRow("Loudness normaliser", "A gentle compressor for mixed playlists. Changes the sound; off for a pure path.", s.normalize) { v -> vm.update { it.copy(normalize = v) } } }
    }
}

private val presets = linkedMapOf(
    "Flat" to List(10) { 0f },
    "Bass" to listOf(6f, 5f, 4f, 2f, 0f, 0f, 0f, 0f, 0f, 0f),
    "Vocal" to listOf(-2f, -1f, 0f, 1f, 3f, 3f, 2f, 1f, 0f, -1f),
    "Treble" to listOf(0f, 0f, 0f, 0f, 0f, 1f, 2f, 4f, 5f, 6f),
    "Loudness" to listOf(5f, 4f, 2f, 0f, -1f, 0f, 0f, 2f, 4f, 5f),
)

@Composable
fun EqualizerScreen(vm: MainViewModel, nav: NavHostController) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val effects by vm.c.playbackStatus.effects.collectAsStateWithLifecycle()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { ScreenTopBar("Equaliser", nav) }
        item { SwitchRow("Equaliser", "Engine now: ${effects.engine}" + (effects.note?.let { " · $it" } ?: ""), s.eqEnabled) { v -> vm.update { it.copy(eqEnabled = v) } } }
        item {
            Row(Modifier.padding(horizontal = 20.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("Built-in", s.eqEngine == EqEngine.BUILT_IN, { vm.update { it.copy(eqEngine = EqEngine.BUILT_IN) } })
                Chip("System / other app", s.eqEngine == EqEngine.EXTERNAL, { vm.update { it.copy(eqEngine = EqEngine.EXTERNAL) } })
            }
        }
        if (s.eqEngine == EqEngine.EXTERNAL) item {
            InfoCard { Text("Unpaused hands its audio session to the equaliser app you've installed (if any). Built-in EQ is off.", style = MaterialTheme.typography.bodySmall) }
        } else {
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    presets.forEach { (name, bands) -> Chip(name, s.eqBandsDb == bands, { vm.update { it.copy(eqBandsDb = bands, eqEnabled = true) } }) }
                }
            }
            item {
                SliderRow("Bass", "%+.0f dB".format(s.bassDb), s.bassDb, AppSettings.EQ_MIN_DB..AppSettings.EQ_MAX_DB, steps = 23) { v -> vm.update { it.copy(bassDb = v.roundToInt().toFloat()) } }
            }
            item {
                SliderRow("Treble", "%+.0f dB".format(s.trebleDb), s.trebleDb, AppSettings.EQ_MIN_DB..AppSettings.EQ_MAX_DB, steps = 23) { v -> vm.update { it.copy(trebleDb = v.roundToInt().toFloat()) } }
            }
            items(AppSettings.EQ_BANDS) { i ->
                val hz = AppSettings.EQ_CENTERS_HZ[i]
                val label = if (hz >= 1000) "${hz / 1000} kHz" else "$hz Hz"
                SliderRow(label, "%+.0f dB".format(s.eqBandsDb[i]), s.eqBandsDb[i], AppSettings.EQ_MIN_DB..AppSettings.EQ_MAX_DB, steps = 23) { v ->
                    vm.update { st -> st.copy(eqBandsDb = st.eqBandsDb.toMutableList().also { it[i] = v.roundToInt().toFloat() }) }
                }
            }
            item {
                Text(
                    "EQ boosts use headroom and a limiter to avoid clipping. With EQ off, flat and no normaliser, no effect is applied.",
                    style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.dim, modifier = Modifier.padding(20.dp),
                )
            }
        }
    }
}

@Composable
fun DiagnosticsScreen(vm: MainViewModel, nav: NavHostController) {
    val events by remember { vm.c.db.diagnostics().observeRecent(400) }.collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var onlyProblems by remember { mutableStateOf(false) }
    val shown = if (onlyProblems) events.filter { it.verdict == Verdict.UNEXPECTED.name || it.verdict == Verdict.EXPECTED_ANDROID.name } else events
    val time = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            ScreenTopBar(
                "Diagnostics", nav,
                listOf(
                    app.unpaused.ui.components.MenuAction("Share log") {
                        val text = events.joinToString("\n") { e -> "${time.format(Date(e.atMs))}  ${e.verdict}  ${e.type}  ${e.message}  ${e.context}" }
                        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share diagnostics"))
                    },
                    app.unpaused.ui.components.MenuAction("Clear log") { scope.launch { vm.c.db.diagnostics().clear() } },
                ),
            )
        }
        item {
            InfoCard {
                Text(
                    "Every start, pause and resume is recorded with its cause, so you can tell Android's normal behaviour (ANDROID) from your actions (YOU), " +
                        "this app's decisions (APP) and real problems (UNEXPECTED). Kept on this phone for 14 days.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        item {
            Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("All", !onlyProblems, { onlyProblems = false })
                Chip("Interruptions", onlyProblems, { onlyProblems = true })
            }
        }
        items(shown, key = { it.id }) { e ->
            val (label, color) = when (e.verdict) {
                Verdict.EXPECTED_ANDROID.name -> "ANDROID" to MaterialTheme.colorScheme.onBackground
                Verdict.USER.name -> "YOU" to LocalExtra.current.dim
                Verdict.APP_DECISION.name -> "APP" to LocalExtra.current.dim
                Verdict.UNEXPECTED.name -> "UNEXPECTED" to LocalExtra.current.accent
                else -> "INFO" to LocalExtra.current.dim
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MonoLabel(label, color = color)
                    Spacer(Modifier.padding(horizontal = 6.dp))
                    MonoLabel(time.format(Date(e.atMs)) + " · " + e.mode.lowercase())
                }
                Text(e.message, style = MaterialTheme.typography.bodyMedium)
                if (e.context.isNotBlank()) Text(e.context, style = MaterialTheme.typography.labelSmall, color = LocalExtra.current.dim)
            }
        }
    }
}

@Composable
fun FoldersScreen(vm: MainViewModel, nav: NavHostController) {
    val folders by remember { vm.c.library.pickedFolders() }.collectAsStateWithLifecycle(emptyList())
    val s by vm.settings.collectAsStateWithLifecycle()
    val count by vm.c.library.count.collectAsStateWithLifecycle(0)
    val scope = rememberCoroutineScope()
    var granted by remember { mutableStateOf(vm.c.library.hasAudioPermission()) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> if (uri != null) vm.addFolder(uri) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> granted = ok; if (ok) vm.rescan() }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { ScreenTopBar("Library folders", nav) }
        item {
            SwitchRow("Use Android's music index", if (granted) "All music Android has found on this phone" else "Needs music access", s.useMediaStore) { v ->
                vm.update { it.copy(useMediaStore = v) }
                if (v && !granted) ask.launch(app.unpaused.data.library.LibraryRepository.audioPermission())
            }
        }
        if (s.useMediaStore && !granted) item {
            Row(Modifier.padding(horizontal = 20.dp)) { PillButton("Allow music access", { ask.launch(app.unpaused.data.library.LibraryRepository.audioPermission()) }) }
        }
        item {
            SliderRow("Ignore files shorter than", "${s.minTrackSeconds} s", s.minTrackSeconds.toFloat(), 0f..120f, steps = 23, subtitle = "Skips ringtones and voice notes.") { v ->
                vm.update { it.copy(minTrackSeconds = (v / 5).roundToInt() * 5) }
            }
        }
        item { SectionHeader("Folders you added") }
        items(folders, key = { it.treeUri }) { f ->
            ClickRow(f.displayName, "${f.trackCount} files" + if (f.lastScanAt > 0) " · scanned " + DateFormat.getDateInstance(DateFormat.SHORT).format(Date(f.lastScanAt)) else "", trailing = "Remove") {
                scope.launch { vm.c.library.removeFolder(f.treeUri) }
            }
        }
        item {
            Row(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton("Add folder", { pick.launch(null) })
                PillButton("Rescan", { vm.rescan() }, filled = false)
            }
        }
        item {
            Text(
                "$count tracks. Folders you add are read through Android's file picker (no storage permission needed), which also finds .lrc lyrics " +
                    "and cover images next to your songs. Nothing is uploaded.",
                style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.dim, modifier = Modifier.padding(20.dp),
            )
        }
    }
}

@Composable
fun SyncScreen(vm: MainViewModel, nav: NavHostController) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val status by vm.c.sync.status.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var url by remember(s.supabaseUrl) { mutableStateOf(s.supabaseUrl) }
    var key by remember(s.supabasePublishableKey) { mutableStateOf(s.supabasePublishableKey) }
    var email by remember(s.syncEmail) { mutableStateOf(s.syncEmail) }
    var password by remember { mutableStateOf("") }
    var signedIn by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    androidx.compose.runtime.LaunchedEffect(status) { signedIn = vm.c.sync.isSignedIn() }

    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            note = runCatching {
                val json = vm.c.sync.exportBackup()
                context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
                "Backup saved."
            }.getOrElse { "Export failed: ${it.message}" }
        }
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            note = runCatching {
                val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } ?: error("Can't read file")
                val r = vm.c.sync.importBackup(text)
                "Imported ${r.pulled} items."
            }.getOrElse { "Import failed: ${it.message}" }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        ScreenTopBar("Sync & backup", nav)
        SectionHeader("Backup file (no account)")
        Text(
            "Saves playlists, favourites and listening history to a JSON file you keep. Import it on another phone or in the web app.",
            style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.dim, modifier = Modifier.padding(horizontal = 20.dp),
        )
        Row(Modifier.padding(20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton("Export", { export.launch("unpaused-backup.json") })
            PillButton("Import", { import.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }, filled = false)
        }
        SectionHeader("Sync with your own Supabase project")
        Text(
            "Optional and free: create a Supabase project, run backend/supabase/migrations, and paste the project URL and publishable key here " +
                "(see docs/SETUP.md). Synced: playlists, favourites, history. Never synced: audio files, artwork, file paths.",
            style = MaterialTheme.typography.bodySmall, color = LocalExtra.current.dim, modifier = Modifier.padding(horizontal = 20.dp),
        )
        SwitchRow("Enable sync", null, s.syncEnabled) { v -> vm.update { it.copy(syncEnabled = v) } }
        if (s.syncEnabled) {
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(url, { url = it }, label = { Text("Project URL (https://…supabase.co)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(key, { key = it }, label = { Text("Publishable key (sb_publishable_…)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(email, { email = it }, label = { Text("Email") }, singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
                if (!signedIn) OutlinedTextField(
                    password, { password = it }, label = { Text("Password") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                val save: suspend () -> Unit = { vm.c.settings.update { it.copy(supabaseUrl = url, supabasePublishableKey = key, syncEmail = email) } }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (signedIn) {
                        PillButton("Sync now", { scope.launch { save(); vm.c.sync.syncNow() } })
                        PillButton("Sign out", { scope.launch { vm.c.sync.signOut(); signedIn = false } }, filled = false)
                    } else {
                        PillButton("Sign in", { scope.launch { save(); note = vm.c.sync.signIn(email, password).fold({ password = ""; signedIn = true; "Signed in." }, { it.message }) } })
                        PillButton("Create account", { scope.launch { save(); note = vm.c.sync.signUp(email, password).fold({ it }, { it.message }); signedIn = vm.c.sync.isSignedIn() } }, filled = false)
                    }
                }
            }
            SwitchRow(
                "Also sync library metadata",
                "Titles, artists, albums and durations of your files (never the files), so the web app can show what's on your phone.",
                s.syncLibrary,
            ) { v -> vm.update { it.copy(syncLibrary = v) } }
        }
        val st = status
        val line = when (st) {
            is SyncStatus.Running -> "Syncing…"
            is SyncStatus.Done -> "Synced ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(st.atMs))}: ${st.pulled} received, ${st.pushed} sent"
            is SyncStatus.Failed -> "Sync failed: ${st.message}"
            else -> null
        }
        listOfNotNull(line, note).forEach { Text(it, style = MaterialTheme.typography.labelMedium, color = LocalExtra.current.accent, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) }
    }
}

@Composable
fun AboutScreen(nav: NavHostController) {
    val context = LocalContext.current
    val licences = remember {
        listOf("OFL-Geist.txt", "OFL-Doto.txt").joinToString("\n\n") { name ->
            runCatching { context.assets.open("licenses/$name").bufferedReader().use { it.readText() } }.getOrDefault("")
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenTopBar("About", nav)
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("UNPAUSED", style = MaterialTheme.typography.displayMedium)
            MonoLabel("Version ${BuildConfig.VERSION_NAME}")
            Text(
                "Free software under the GNU GPL v3 or later. Source: github.com/ratnesh-lord/music-player. Playback by AndroidX Media3 (Apache 2.0). " +
                    "Fonts: Geist and Geist Mono (Vercel) and Doto, under the SIL Open Font License 1.1.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(licences, style = MaterialTheme.typography.labelSmall, color = LocalExtra.current.dim)
        }
    }
}
