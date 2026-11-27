package app.unpaused.data.user

import app.unpaused.data.db.AppDatabase
import app.unpaused.data.db.FavoriteEntity
import app.unpaused.data.db.PlayEventEntity
import app.unpaused.data.db.PlaylistEntity
import app.unpaused.data.db.PlaylistItemEntity
import app.unpaused.data.db.RecentPlayRow
import app.unpaused.data.db.TrackEntity
import app.unpaused.data.settings.SettingsRepository
import kotlinx.coroutines.flow.Flow
import java.util.UUID

/**
 * Playlists store track *keys* (artist|album|title|duration bucket, see core TrackKey), not file
 * paths, so they survive rescans, moving files and syncing to another device. Deletes are tombstones
 * so sync can propagate them.
 */
class PlaylistRepository(private val db: AppDatabase, private val settings: SettingsRepository) {
    private val dao = db.playlists()

    fun playlists(): Flow<List<PlaylistEntity>> = dao.observePlaylists()
    fun playlist(id: Long): Flow<PlaylistEntity?> = dao.observePlaylist(id)
    fun items(id: Long): Flow<List<PlaylistItemEntity>> = dao.observeItems(id)
    suspend fun itemsNow(id: Long): List<PlaylistItemEntity> = dao.items(id)
    suspend fun get(id: Long): PlaylistEntity? = dao.byId(id)

    suspend fun create(name: String, tracks: List<TrackEntity> = emptyList()): Long {
        val now = System.currentTimeMillis()
        val id = dao.insert(
            PlaylistEntity(syncId = UUID.randomUUID().toString(), name = name.trim().ifEmpty { "New playlist" }, createdAt = now, updatedAt = now, deviceId = settings.deviceId()),
        )
        if (tracks.isNotEmpty()) dao.replaceItems(id, tracks.mapIndexed { i, t -> t.toItem(id, i, now) })
        return id
    }

    suspend fun rename(id: Long, name: String) = dao.rename(id, name.trim().ifEmpty { "Playlist" }, System.currentTimeMillis(), settings.deviceId())

    suspend fun delete(id: Long) {
        dao.clearItems(id)
        dao.markDeleted(id, System.currentTimeMillis(), settings.deviceId())
    }

    suspend fun add(id: Long, tracks: List<TrackEntity>) {
        if (tracks.isEmpty()) return
        val now = System.currentTimeMillis()
        val existing = dao.items(id)
        dao.replaceItems(id, existing + tracks.mapIndexed { i, t -> t.toItem(id, existing.size + i, now) })
        dao.touch(id, now, settings.deviceId())
    }

    suspend fun removeAt(id: Long, position: Int) = edit(id) { it.toMutableList().apply { if (position in indices) removeAt(position) } }

    suspend fun move(id: Long, from: Int, to: Int) = edit(id) { list ->
        if (from !in list.indices || to !in list.indices) list else list.toMutableList().apply { add(to, removeAt(from)) }
    }

    /** Replaces the items keeping their cached display data; positions are renumbered. */
    private suspend fun edit(id: Long, change: (List<PlaylistItemEntity>) -> List<PlaylistItemEntity>) {
        val now = System.currentTimeMillis()
        val updated = change(dao.items(id)).mapIndexed { i, item -> item.copy(position = i) }
        dao.replaceItems(id, updated)
        dao.touch(id, now, settings.deviceId())
    }

    private fun TrackEntity.toItem(playlistId: Long, position: Int, at: Long) =
        PlaylistItemEntity(playlistId = playlistId, position = position, trackKey = trackKey, title = title, artist = artist, addedAt = at)
}

class FavoritesRepository(private val db: AppDatabase, private val settings: SettingsRepository) {
    private val dao = db.favorites()

    fun favorites(): Flow<List<FavoriteEntity>> = dao.observeFavorites()
    fun isFavorite(trackKey: String): Flow<Boolean> = dao.observeIsFavorite(trackKey)

    suspend fun isFavoriteNow(trackKey: String): Boolean = dao.get(trackKey)?.deleted == false

    /** Returns the new state. */
    suspend fun toggle(trackKey: String, title: String, artist: String): Boolean {
        val now = !isFavoriteNow(trackKey)
        set(trackKey, title, artist, now)
        return now
    }

    suspend fun set(trackKey: String, title: String, artist: String, favorite: Boolean) {
        dao.upsert(FavoriteEntity(trackKey, title, artist, System.currentTimeMillis(), deleted = !favorite, deviceId = settings.deviceId()))
    }
}

class HistoryRepository(private val db: AppDatabase, private val settings: SettingsRepository) {
    private val dao = db.history()

    fun recent(limit: Int = 50): Flow<List<RecentPlayRow>> = dao.observeRecent(limit)
    fun mostPlayed(sinceMs: Long, limit: Int = 25): Flow<List<RecentPlayRow>> = dao.observeMostPlayed(sinceMs, limit)

    suspend fun record(trackKey: String, title: String, artist: String, album: String, listenedMs: Long, playedAt: Long = System.currentTimeMillis()) {
        dao.insert(PlayEventEntity(UUID.randomUUID().toString(), trackKey, title, artist, album, playedAt, listenedMs, settings.deviceId()))
    }

    suspend fun clear() = dao.clear()

    companion object {
        /** A play counts once the listener heard 30 s or half the track, whichever is shorter. */
        fun countsAsPlay(listenedMs: Long, durationMs: Long): Boolean {
            val threshold = if (durationMs > 0) minOf(30_000L, durationMs / 2) else 30_000L
            return listenedMs >= threshold
        }
    }
}
