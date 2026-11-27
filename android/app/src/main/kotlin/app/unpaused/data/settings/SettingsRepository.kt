package app.unpaused.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.unpaused.core.audio.ReplayGainMode
import app.unpaused.core.focus.FocusMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {

    private object K {
        val focusMode = stringPreferencesKey("focus_mode")
        val autoResumeWindowMin = intPreferencesKey("auto_resume_window_min")
        val quietPeriodSec = intPreferencesKey("quiet_period_sec")
        val duckOnTransientLoss = booleanPreferencesKey("duck_on_transient")
        val pauseForVoiceChat = booleanPreferencesKey("pause_for_voice_chat")
        val resumeAfterInterruption = booleanPreferencesKey("resume_after_interruption")
        val resumeOnHeadsetConnect = booleanPreferencesKey("resume_on_headset")
        val keepAliveAfterPauseMin = intPreferencesKey("keep_alive_after_pause_min")
        val restoreQueueOnLaunch = booleanPreferencesKey("restore_queue")
        val hiResOutput = booleanPreferencesKey("hires_output")
        val replayGainMode = stringPreferencesKey("rg_mode")
        val replayGainPreampDb = floatPreferencesKey("rg_preamp")
        val replayGainUntaggedDb = floatPreferencesKey("rg_untagged")
        val replayGainPreventClipping = booleanPreferencesKey("rg_prevent_clipping")
        val eqEnabled = booleanPreferencesKey("eq_enabled")
        val eqEngine = stringPreferencesKey("eq_engine")
        val eqBands = stringPreferencesKey("eq_bands")
        val bassDb = floatPreferencesKey("bass_db")
        val trebleDb = floatPreferencesKey("treble_db")
        val normalize = booleanPreferencesKey("normalize")
        val skipSilence = booleanPreferencesKey("skip_silence")
        val useMediaStore = booleanPreferencesKey("use_mediastore")
        val minTrackSeconds = intPreferencesKey("min_track_seconds")
        val themeMode = stringPreferencesKey("theme_mode")
        val radioEnabled = booleanPreferencesKey("radio_enabled")
        val syncEnabled = booleanPreferencesKey("sync_enabled")
        val supabaseUrl = stringPreferencesKey("supabase_url")
        val supabaseKey = stringPreferencesKey("supabase_key")
        val syncEmail = stringPreferencesKey("sync_email")
        val syncLibrary = booleanPreferencesKey("sync_library")
        val lastNonMixMode = stringPreferencesKey("last_non_mix_mode")
        val deviceId = stringPreferencesKey("device_id")
        val syncAccessToken = stringPreferencesKey("sync_access_token")
        val syncRefreshToken = stringPreferencesKey("sync_refresh_token")
        val syncUserId = stringPreferencesKey("sync_user_id")
        val lastSyncAt = stringPreferencesKey("last_sync_at")
        val onboarded = booleanPreferencesKey("onboarded")
    }

    val settings: Flow<AppSettings> = context.settingsStore.data.map { p -> p.toSettings() }

    val onboarded: Flow<Boolean> = context.settingsStore.data.map { it[K.onboarded] ?: false }

    suspend fun current(): AppSettings = settings.first()

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.settingsStore.edit { p ->
            val next = transform(p.toSettings())
            p[K.focusMode] = next.focusMode.name
            p[K.autoResumeWindowMin] = next.autoResumeWindowMin.coerceIn(0, 10)
            p[K.quietPeriodSec] = next.quietPeriodSec.coerceIn(1, 30)
            p[K.duckOnTransientLoss] = next.duckOnTransientLoss
            p[K.pauseForVoiceChat] = next.pauseForVoiceChat
            p[K.resumeAfterInterruption] = next.resumeAfterInterruption
            p[K.resumeOnHeadsetConnect] = next.resumeOnHeadsetConnect
            p[K.keepAliveAfterPauseMin] = next.keepAliveAfterPauseMin.coerceIn(0, 10)
            p[K.restoreQueueOnLaunch] = next.restoreQueueOnLaunch
            p[K.hiResOutput] = next.hiResOutput
            p[K.replayGainMode] = next.replayGainMode.name
            p[K.replayGainPreampDb] = next.replayGainPreampDb.coerceIn(-15f, 15f)
            p[K.replayGainUntaggedDb] = next.replayGainUntaggedDb.coerceIn(-15f, 15f)
            p[K.replayGainPreventClipping] = next.replayGainPreventClipping
            p[K.eqEnabled] = next.eqEnabled
            p[K.eqEngine] = next.eqEngine.name
            p[K.eqBands] = next.eqBandsDb.joinToString(",") { it.coerceIn(AppSettings.EQ_MIN_DB, AppSettings.EQ_MAX_DB).toString() }
            p[K.bassDb] = next.bassDb.coerceIn(AppSettings.EQ_MIN_DB, AppSettings.EQ_MAX_DB)
            p[K.trebleDb] = next.trebleDb.coerceIn(AppSettings.EQ_MIN_DB, AppSettings.EQ_MAX_DB)
            p[K.normalize] = next.normalize
            p[K.skipSilence] = next.skipSilence
            p[K.useMediaStore] = next.useMediaStore
            p[K.minTrackSeconds] = next.minTrackSeconds.coerceIn(0, 600)
            p[K.themeMode] = next.themeMode.name
            p[K.radioEnabled] = next.radioEnabled
            p[K.syncEnabled] = next.syncEnabled
            p[K.supabaseUrl] = next.supabaseUrl.trim()
            p[K.supabaseKey] = next.supabasePublishableKey.trim()
            p[K.syncEmail] = next.syncEmail.trim()
            p[K.syncLibrary] = next.syncLibrary
            p[K.lastNonMixMode] = (if (next.lastNonMixMode == FocusMode.MIX) FocusMode.SMART else next.lastNonMixMode).name
        }
    }

    suspend fun setOnboarded() {
        context.settingsStore.edit { it[K.onboarded] = true }
    }

    /** Random id for this installation; used only to break sync ties. */
    suspend fun deviceId(): String {
        val existing = context.settingsStore.data.first()[K.deviceId]
        if (existing != null) return existing
        val id = "android-" + UUID.randomUUID().toString().take(8)
        context.settingsStore.edit { it[K.deviceId] = id }
        return id
    }

    data class SyncSession(val accessToken: String, val refreshToken: String, val userId: String)

    suspend fun syncSession(): SyncSession? {
        val p = context.settingsStore.data.first()
        val a = p[K.syncAccessToken] ?: return null
        val r = p[K.syncRefreshToken] ?: return null
        val u = p[K.syncUserId] ?: return null
        return SyncSession(a, r, u)
    }

    suspend fun saveSyncSession(session: SyncSession?) {
        context.settingsStore.edit {
            if (session == null) {
                it.remove(K.syncAccessToken); it.remove(K.syncRefreshToken); it.remove(K.syncUserId)
            } else {
                it[K.syncAccessToken] = session.accessToken
                it[K.syncRefreshToken] = session.refreshToken
                it[K.syncUserId] = session.userId
            }
        }
    }

    suspend fun lastSyncAt(): Long = context.settingsStore.data.first()[K.lastSyncAt]?.toLongOrNull() ?: 0L

    suspend fun setLastSyncAt(ms: Long) {
        context.settingsStore.edit { it[K.lastSyncAt] = ms.toString() }
    }

    private fun Preferences.toSettings(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            focusMode = this[K.focusMode]?.let { runCatching { FocusMode.valueOf(it) }.getOrNull() } ?: d.focusMode,
            autoResumeWindowMin = this[K.autoResumeWindowMin] ?: d.autoResumeWindowMin,
            quietPeriodSec = this[K.quietPeriodSec] ?: d.quietPeriodSec,
            duckOnTransientLoss = this[K.duckOnTransientLoss] ?: d.duckOnTransientLoss,
            pauseForVoiceChat = this[K.pauseForVoiceChat] ?: d.pauseForVoiceChat,
            resumeAfterInterruption = this[K.resumeAfterInterruption] ?: d.resumeAfterInterruption,
            resumeOnHeadsetConnect = this[K.resumeOnHeadsetConnect] ?: d.resumeOnHeadsetConnect,
            keepAliveAfterPauseMin = this[K.keepAliveAfterPauseMin] ?: d.keepAliveAfterPauseMin,
            restoreQueueOnLaunch = this[K.restoreQueueOnLaunch] ?: d.restoreQueueOnLaunch,
            hiResOutput = this[K.hiResOutput] ?: d.hiResOutput,
            replayGainMode = this[K.replayGainMode]?.let { runCatching { ReplayGainMode.valueOf(it) }.getOrNull() } ?: d.replayGainMode,
            replayGainPreampDb = this[K.replayGainPreampDb] ?: d.replayGainPreampDb,
            replayGainUntaggedDb = this[K.replayGainUntaggedDb] ?: d.replayGainUntaggedDb,
            replayGainPreventClipping = this[K.replayGainPreventClipping] ?: d.replayGainPreventClipping,
            eqEnabled = this[K.eqEnabled] ?: d.eqEnabled,
            eqEngine = this[K.eqEngine]?.let { runCatching { EqEngine.valueOf(it) }.getOrNull() } ?: d.eqEngine,
            eqBandsDb = this[K.eqBands]?.split(',')?.mapNotNull { it.toFloatOrNull() }
                ?.takeIf { it.size == AppSettings.EQ_BANDS } ?: d.eqBandsDb,
            bassDb = this[K.bassDb] ?: d.bassDb,
            trebleDb = this[K.trebleDb] ?: d.trebleDb,
            normalize = this[K.normalize] ?: d.normalize,
            skipSilence = this[K.skipSilence] ?: d.skipSilence,
            useMediaStore = this[K.useMediaStore] ?: d.useMediaStore,
            minTrackSeconds = this[K.minTrackSeconds] ?: d.minTrackSeconds,
            themeMode = this[K.themeMode]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: d.themeMode,
            radioEnabled = this[K.radioEnabled] ?: d.radioEnabled,
            syncEnabled = this[K.syncEnabled] ?: d.syncEnabled,
            supabaseUrl = this[K.supabaseUrl] ?: d.supabaseUrl,
            supabasePublishableKey = this[K.supabaseKey] ?: d.supabasePublishableKey,
            syncEmail = this[K.syncEmail] ?: d.syncEmail,
            syncLibrary = this[K.syncLibrary] ?: d.syncLibrary,
            lastNonMixMode = this[K.lastNonMixMode]?.let { runCatching { FocusMode.valueOf(it) }.getOrNull() } ?: d.lastNonMixMode,
        )
    }
}
