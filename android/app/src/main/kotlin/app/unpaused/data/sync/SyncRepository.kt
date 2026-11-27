package app.unpaused.data.sync

import app.unpaused.core.sync.SyncMerge
import app.unpaused.core.sync.SyncStamp
import app.unpaused.data.db.AppDatabase
import app.unpaused.data.db.FavoriteEntity
import app.unpaused.data.db.PlayEventEntity
import app.unpaused.data.db.PlaylistEntity
import app.unpaused.data.db.PlaylistItemEntity
import app.unpaused.data.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

// One set of records, two encodings: snake_case for the Supabase tables, camelCase for the JSON backup.
// Both are specified in shared/sync-schema.md and mirrored by web/src/lib/sync.

@Serializable
data class SyncPlaylistItem(val trackKey: String, val title: String = "", val artist: String = "", val addedAt: Long = 0)

@Serializable
data class SyncPlaylist(
    val id: String,
    val name: String,
    val items: List<SyncPlaylistItem> = emptyList(),
    val createdAt: Long = 0,
    val updatedAt: Long,
    val deleted: Boolean = false,
    val deviceId: String = "",
    val userId: String? = null,
)

@Serializable
data class SyncFavorite(
    val trackKey: String,
    val title: String = "",
    val artist: String = "",
    val updatedAt: Long,
    val deleted: Boolean = false,
    val deviceId: String = "",
    val userId: String? = null,
)

@Serializable
data class SyncPlay(
    val id: String,
    val trackKey: String,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val playedAt: Long,
    val listenedMs: Long = 0,
    val deviceId: String = "",
    val userId: String? = null,
)

@Serializable
data class SyncLibraryTrack(
    val trackKey: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val codec: String? = null,
    val sampleRate: Int? = null,
    val bitDepth: Int? = null,
    val updatedAt: Long,
    val deviceId: String,
    val userId: String? = null,
)

@Serializable
data class Backup(
    val format: String = FORMAT,
    val version: Int = 1,
    val exportedAt: Long,
    val deviceId: String,
    val playlists: List<SyncPlaylist> = emptyList(),
    val favorites: List<SyncFavorite> = emptyList(),
    val history: List<SyncPlay> = emptyList(),
) {
    companion object {
        const val FORMAT = "unpaused-backup"
    }
}

data class MergeCounts(val pulled: Int, val pushed: Int)

sealed interface SyncStatus {
    data object Idle : SyncStatus
    data object Running : SyncStatus
    data class Done(val atMs: Long, val pulled: Int, val pushed: Int) : SyncStatus
    data class Failed(val message: String) : SyncStatus
}

/**
 * Optional sync through the user's own free Supabase project (email + password auth, row-level
 * security), plus a no-account JSON backup. Only playlists, favourites, listening history and — if the
 * user opts in — library *metadata* leave the device. Audio files, artwork and paths never do.
 */
@OptIn(ExperimentalSerializationApi::class)
class SyncRepository(private val db: AppDatabase, private val settings: SettingsRepository, private val http: OkHttpClient) {

    // explicitNulls: PostgREST bulk inserts need every row to have the same keys.
    private val remoteJson = Json { namingStrategy = JsonNamingStrategy.SnakeCase; ignoreUnknownKeys = true; explicitNulls = true; encodeDefaults = true }
    private val backupJson = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true; prettyPrint = true }
    private val mutex = Mutex()
    private val _status = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    // ---- Account ---------------------------------------------------------------------------------

    suspend fun signUp(email: String, password: String): Result<String> = runCatching {
        val cfg = config()
        val body = auth(cfg, "signup", """{"email":${q(email)},"password":${q(password)}}""")
        val session = parseSession(body)
        if (session != null) {
            settings.saveSyncSession(session)
            "Signed in."
        } else {
            "Check your email and confirm the address, then sign in."
        }
    }

    suspend fun signIn(email: String, password: String): Result<Unit> = runCatching {
        val body = auth(config(), "token?grant_type=password", """{"email":${q(email)},"password":${q(password)}}""")
        settings.saveSyncSession(parseSession(body) ?: throw IOException("Sign-in failed"))
    }

    suspend fun signOut() {
        val cfg = runCatching { config() }.getOrNull()
        val s = settings.syncSession()
        if (cfg != null && s != null) runCatching {
            withContext(Dispatchers.IO) {
                http.newCall(
                    Request.Builder().url("${cfg.url}/auth/v1/logout").header("apikey", cfg.key)
                        .header("Authorization", "Bearer ${s.accessToken}").post(ByteArray(0).toRequestBody()).build(),
                ).execute().close()
            }
        }
        settings.saveSyncSession(null)
        _status.value = SyncStatus.Idle
    }

    suspend fun isSignedIn(): Boolean = settings.syncSession() != null

    // ---- Sync --------------------------------------------------------------------------------------

    suspend fun syncNow(): Result<MergeCounts> = mutex.withLock {
        _status.value = SyncStatus.Running
        runCatching {
            withContext(Dispatchers.IO) {
                val api = Api(config(), settings.syncSession() ?: throw IOException("Not signed in"))
                val me = settings.deviceId()
                val startedAt = System.currentTimeMillis()
                var pulled = 0
                var pushed = 0

                val remoteFavs = api.getAll("favorites", "select=track_key,title,artist,updated_at,deleted,device_id", SyncFavorite.serializer())
                mergeFavorites(remoteFavs, me).let { (pull, push) ->
                    pulled += pull.size
                    pushed += push.size
                    api.upsert("favorites", "user_id,track_key", push.map { it.copy(userId = api.userId) }, SyncFavorite.serializer())
                }

                val remoteLists = api.getAll("playlists", "select=id,name,items,created_at,updated_at,deleted,device_id", SyncPlaylist.serializer())
                mergePlaylists(remoteLists).let { (pull, push) ->
                    pulled += pull
                    pushed += push.size
                    api.upsert("playlists", "user_id,id", push.map { it.copy(userId = api.userId) }, SyncPlaylist.serializer())
                }

                // History is append-only: upload what's new here, download what's new elsewhere.
                while (true) {
                    val batch = db.history().unsynced(500)
                    if (batch.isEmpty()) break
                    api.upsert("play_history", "user_id,id", batch.map { it.toSync().copy(userId = api.userId) }, SyncPlay.serializer())
                    db.history().markSynced(batch.map { it.id })
                    pushed += batch.size
                }
                val since = (settings.lastSyncAt() - HISTORY_OVERLAP_MS).coerceAtLeast(0)
                val remotePlays = api.getAll("play_history", "select=id,track_key,title,artist,album,played_at,listened_ms,device_id&played_at=gt.$since&device_id=neq.${enc(me)}", SyncPlay.serializer())
                db.history().insertAll(remotePlays.map { it.toEntity(synced = true) })
                pulled += remotePlays.size

                if (settings.current().syncLibrary) {
                    val rows = db.tracks().all().distinctBy { it.trackKey }.map {
                        SyncLibraryTrack(it.trackKey, it.title, it.artist, it.album, it.durationMs, it.codec, it.sampleRate, it.bitDepth, startedAt, me, api.userId)
                    }
                    api.delete("library_tracks", "device_id=eq.${enc(me)}")
                    api.upsert("library_tracks", "user_id,device_id,track_key", rows, SyncLibraryTrack.serializer())
                    pushed += rows.size
                } else {
                    api.delete("library_tracks", "device_id=eq.${enc(me)}")
                }

                settings.setLastSyncAt(startedAt)
                MergeCounts(pulled, pushed)
            }
        }.onSuccess { _status.value = SyncStatus.Done(System.currentTimeMillis(), it.pulled, it.pushed) }
            .onFailure { _status.value = SyncStatus.Failed(it.message ?: it.javaClass.simpleName) }
    }

    // ---- Backup (no account needed) ------------------------------------------------------------------

    suspend fun exportBackup(): String = withContext(Dispatchers.IO) {
        val me = settings.deviceId()
        backupJson.encodeToString(
            Backup.serializer(),
            Backup(
                exportedAt = System.currentTimeMillis(),
                deviceId = me,
                playlists = localPlaylists().values.toList(),
                favorites = db.favorites().allIncludingDeleted().map { it.toSync() },
                history = db.history().latest(100_000).map { it.toSync() },
            ),
        )
    }

    /** Merges a backup with last-writer-wins, the same rule as sync. */
    suspend fun importBackup(text: String): MergeCounts = withContext(Dispatchers.IO) {
        val backup = backupJson.decodeFromString(Backup.serializer(), text)
        require(backup.format == Backup.FORMAT) { "Not an Unpaused backup" }
        require(backup.version <= 1) { "This backup is from a newer version of Unpaused" }
        val favs = mergeFavorites(backup.favorites, settings.deviceId()).first.size
        val lists = mergePlaylists(backup.playlists).first
        db.history().insertAll(backup.history.map { it.toEntity(synced = false) })
        MergeCounts(favs + lists + backup.history.size, 0)
    }

    // ---- Merging -------------------------------------------------------------------------------

    /** Applies remote winners locally; returns (pulled, local winners to push). */
    private suspend fun mergeFavorites(remote: List<SyncFavorite>, me: String): Pair<List<SyncFavorite>, List<SyncFavorite>> {
        val local = db.favorites().allIncludingDeleted()
        val plan = SyncMerge.plan(
            local.map { SyncStamp(it.trackKey, it.updatedAt, it.deleted, it.deviceId) },
            remote.map { SyncStamp(it.trackKey, it.updatedAt, it.deleted, it.deviceId) },
        )
        val r = remote.associateBy { it.trackKey }
        val l = local.associateBy { it.trackKey }
        val pull = plan.pull.mapNotNull { r[it] }
        db.favorites().upsertAll(pull.map { FavoriteEntity(it.trackKey, it.title, it.artist, it.updatedAt, it.deleted, it.deviceId.ifEmpty { me }) })
        return pull to plan.push.mapNotNull { l[it]?.toSync() }
    }

    /** Returns (number pulled, local winners to push). */
    private suspend fun mergePlaylists(remote: List<SyncPlaylist>): Pair<Int, List<SyncPlaylist>> {
        val local = localPlaylists()
        val plan = SyncMerge.plan(
            local.values.map { SyncStamp(it.id, it.updatedAt, it.deleted, it.deviceId) },
            remote.map { SyncStamp(it.id, it.updatedAt, it.deleted, it.deviceId) },
        )
        val r = remote.associateBy { it.id }
        for (id in plan.pull) {
            val p = r[id] ?: continue
            val existing = db.playlists().bySyncId(p.id)
            val entity = PlaylistEntity(
                id = existing?.id ?: 0, syncId = p.id, name = p.name, createdAt = p.createdAt.takeIf { it > 0 } ?: p.updatedAt,
                updatedAt = p.updatedAt, deleted = p.deleted, deviceId = p.deviceId,
            )
            val rowId = if (existing == null) db.playlists().insert(entity) else entity.id.also { db.playlists().upsert(entity) }
            db.playlists().replaceItems(
                rowId,
                if (p.deleted) emptyList() else p.items.mapIndexed { i, it -> PlaylistItemEntity(rowId, i, it.trackKey, it.title, it.artist, it.addedAt) },
            )
        }
        return plan.pull.size to plan.push.mapNotNull { local[it] }
    }

    private suspend fun localPlaylists(): Map<String, SyncPlaylist> = db.playlists().allIncludingDeleted().associate { p ->
        p.syncId to SyncPlaylist(
            id = p.syncId, name = p.name,
            items = db.playlists().items(p.id).map { SyncPlaylistItem(it.trackKey, it.title, it.artist, it.addedAt) },
            createdAt = p.createdAt, updatedAt = p.updatedAt, deleted = p.deleted, deviceId = p.deviceId,
        )
    }

    private fun FavoriteEntity.toSync() = SyncFavorite(trackKey, title, artist, updatedAt, deleted, deviceId)
    private fun PlayEventEntity.toSync() = SyncPlay(id, trackKey, title, artist, album, playedAt, listenedMs, deviceId)
    private fun SyncPlay.toEntity(synced: Boolean) = PlayEventEntity(id, trackKey, title, artist, album, playedAt, listenedMs, deviceId, synced)

    // ---- HTTP ----------------------------------------------------------------------------------

    private data class Config(val url: String, val key: String)

    private suspend fun config(): Config {
        val s = settings.current()
        val url = s.supabaseUrl.trim().trimEnd('/')
        require(url.startsWith("https://")) { "Enter your Supabase project URL (https://…)" }
        require(s.supabasePublishableKey.isNotBlank()) { "Enter your Supabase publishable key" }
        require(!s.supabasePublishableKey.startsWith("sb_secret_")) { "That's a secret key. Use the publishable key (sb_publishable_…)" }
        return Config(url, s.supabasePublishableKey.trim())
    }

    private suspend fun auth(cfg: Config, path: String, json: String): String = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("${cfg.url}/auth/v1/$path").header("apikey", cfg.key)
            .post(json.toRequestBody(JSON)).build()
        http.newCall(req).execute().use { r ->
            val body = r.body.string()
            if (!r.isSuccessful) throw IOException(errorMessage(body) ?: "HTTP ${r.code}")
            body
        }
    }

    private fun parseSession(body: String): SettingsRepository.SyncSession? = runCatching {
        val o = Json.parseToJsonElement(body) as JsonObject
        val access = o["access_token"]?.jsonPrimitive?.content ?: return null
        val refresh = o["refresh_token"]?.jsonPrimitive?.content ?: return null
        val user = (o["user"] as? JsonObject)?.get("id")?.jsonPrimitive?.content ?: return null
        SettingsRepository.SyncSession(access, refresh, user)
    }.getOrNull()

    private fun errorMessage(body: String): String? = runCatching {
        val o = Json.parseToJsonElement(body) as JsonObject
        listOf("msg", "message", "error_description", "error").firstNotNullOfOrNull { o[it]?.jsonPrimitive?.content }
    }.getOrNull()

    private inner class Api(private val cfg: Config, private var session: SettingsRepository.SyncSession) {
        val userId: String get() = session.userId

        fun <T> getAll(table: String, query: String, serializer: KSerializer<T>): List<T> {
            val out = ArrayList<T>()
            var offset = 0
            while (true) {
                val body = call(table) { Request.Builder().url("${cfg.url}/rest/v1/$table?$query&limit=$PAGE&offset=$offset").get() }
                val page = remoteJson.decodeFromString(ListSerializer(serializer), body)
                out += page
                if (page.size < PAGE) break
                offset += PAGE
            }
            return out
        }

        fun <T> upsert(table: String, conflict: String, rows: List<T>, serializer: KSerializer<T>) {
            rows.chunked(500).forEach { chunk ->
                val json = remoteJson.encodeToString(ListSerializer(serializer), chunk)
                call(table) {
                    Request.Builder().url("${cfg.url}/rest/v1/$table?on_conflict=$conflict")
                        .header("Prefer", "resolution=merge-duplicates,missing=default,return=minimal")
                        .post(json.toRequestBody(JSON))
                }
            }
        }

        fun delete(table: String, filter: String) {
            call(table) { Request.Builder().url("${cfg.url}/rest/v1/$table?$filter").header("Prefer", "return=minimal").delete() }
        }

        /** Runs a request; on 401 refreshes the session once and retries. */
        private fun call(table: String, build: () -> Request.Builder): String {
            repeat(2) { attempt ->
                val req = build().header("apikey", cfg.key).header("Authorization", "Bearer ${session.accessToken}").build()
                http.newCall(req).execute().use { r ->
                    val body = r.body.string()
                    if (r.isSuccessful) return body
                    if (r.code == 401 && attempt == 0) {
                        refresh()
                    } else {
                        throw IOException("$table: ${errorMessage(body) ?: "HTTP ${r.code}"}")
                    }
                }
            }
            throw IOException("Unauthorized")
        }

        private fun refresh() {
            val req = Request.Builder().url("${cfg.url}/auth/v1/token?grant_type=refresh_token").header("apikey", cfg.key)
                .post("""{"refresh_token":${q(session.refreshToken)}}""".toRequestBody(JSON)).build()
            val body = http.newCall(req).execute().use { r ->
                val b = r.body.string()
                if (!r.isSuccessful) throw IOException("Session expired. Sign in again.")
                b
            }
            session = parseSession(body) ?: throw IOException("Session expired. Sign in again.")
            kotlinx.coroutines.runBlocking { settings.saveSyncSession(session) }
        }
    }

    companion object {
        private val JSON = "application/json".toMediaType()
        private const val PAGE = 1000
        private const val HISTORY_OVERLAP_MS = 24L * 60 * 60 * 1000

        private fun q(s: String) = Json.encodeToString(kotlinx.serialization.serializer<String>(), s)
        private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
    }
}
