package app.unpaused.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

data class AlbumRow(
    val albumId: String,
    val album: String,
    val artist: String,
    val trackCount: Int,
    val durationMs: Long,
    val year: Int?,
    val artworkPath: String?,
    val dateAdded: Long,
    val albumSort: String,
)

data class ArtistRow(
    val artistKey: String,
    val artist: String,
    val trackCount: Int,
    val albumCount: Int,
    val artworkPath: String?,
    val artistSort: String,
)

data class GroupRow(val name: String, val trackCount: Int, val artworkPath: String?)

data class RecentPlayRow(
    val trackKey: String,
    val title: String,
    val artist: String,
    val album: String,
    val lastPlayed: Long,
    val plays: Int,
)

@Dao
interface TrackDao {
    @Upsert
    suspend fun upsert(tracks: List<TrackEntity>)

    @Query("SELECT * FROM tracks WHERE uri = :uri LIMIT 1")
    suspend fun byUri(uri: String): TrackEntity?

    @Query("SELECT * FROM tracks WHERE id = :id")
    suspend fun byId(id: Long): TrackEntity?

    @Query("SELECT * FROM tracks WHERE id IN (:ids)")
    suspend fun byIds(ids: List<Long>): List<TrackEntity>

    @Query("SELECT * FROM tracks WHERE trackKey IN (:keys)")
    suspend fun byTrackKeys(keys: List<String>): List<TrackEntity>

    @Query("SELECT * FROM tracks WHERE trackKey IN (:keys)")
    fun observeByTrackKeys(keys: List<String>): Flow<List<TrackEntity>>

    @Query("SELECT id, uri, dateModified, sizeBytes, enrichedAt FROM tracks WHERE source = :source")
    suspend fun fingerprints(source: Int): List<TrackFingerprint>

    @Query("SELECT id, uri, dateModified, sizeBytes, enrichedAt FROM tracks WHERE treeUri = :treeUri")
    suspend fun fingerprintsInTree(treeUri: String): List<TrackFingerprint>

    @Query("SELECT * FROM tracks ORDER BY titleSort COLLATE NOCASE")
    fun observeAll(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks ORDER BY titleSort COLLATE NOCASE")
    suspend fun all(): List<TrackEntity>

    @Query("SELECT COUNT(*) FROM tracks")
    fun observeCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM tracks")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM tracks WHERE enrichedAt IS NULL")
    suspend fun unenrichedCount(): Int

    @Query(
        """SELECT albumId, album, COALESCE(MAX(albumArtist), MAX(artist)) AS artist, COUNT(*) AS trackCount,
           SUM(durationMs) AS durationMs, MAX(year) AS year, MAX(artworkPath) AS artworkPath,
           MAX(dateAdded) AS dateAdded, MIN(albumSort) AS albumSort
           FROM tracks GROUP BY albumId ORDER BY albumSort COLLATE NOCASE""",
    )
    fun observeAlbums(): Flow<List<AlbumRow>>

    @Query(
        """SELECT albumId, album, COALESCE(MAX(albumArtist), MAX(artist)) AS artist, COUNT(*) AS trackCount,
           SUM(durationMs) AS durationMs, MAX(year) AS year, MAX(artworkPath) AS artworkPath,
           MAX(dateAdded) AS dateAdded, MIN(albumSort) AS albumSort
           FROM tracks GROUP BY albumId ORDER BY MAX(dateAdded) DESC LIMIT :limit""",
    )
    fun observeRecentAlbums(limit: Int): Flow<List<AlbumRow>>

    @Query(
        """SELECT artistKey, MAX(artist) AS artist, COUNT(*) AS trackCount, COUNT(DISTINCT albumId) AS albumCount,
           MAX(artworkPath) AS artworkPath, MIN(artistSort) AS artistSort
           FROM tracks GROUP BY artistKey ORDER BY artistSort COLLATE NOCASE""",
    )
    fun observeArtists(): Flow<List<ArtistRow>>

    @Query(
        """SELECT genre AS name, COUNT(*) AS trackCount, MAX(artworkPath) AS artworkPath
           FROM tracks WHERE genre IS NOT NULL AND genre != '' GROUP BY genre ORDER BY genre COLLATE NOCASE""",
    )
    fun observeGenres(): Flow<List<GroupRow>>

    @Query(
        """SELECT folderPath AS name, COUNT(*) AS trackCount, MAX(artworkPath) AS artworkPath
           FROM tracks GROUP BY folderPath ORDER BY folderPath COLLATE NOCASE""",
    )
    fun observeFolders(): Flow<List<GroupRow>>

    @Query("SELECT * FROM tracks WHERE albumId = :albumId ORDER BY COALESCE(discNumber, 1), COALESCE(trackNumber, 9999), titleSort")
    fun observeAlbumTracks(albumId: String): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE artistKey = :artistKey ORDER BY albumSort, COALESCE(discNumber, 1), COALESCE(trackNumber, 9999), titleSort")
    fun observeArtistTracks(artistKey: String): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE genre = :genre ORDER BY artistSort, albumSort, COALESCE(trackNumber, 9999)")
    fun observeGenreTracks(genre: String): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE folderPath = :folder ORDER BY fileName COLLATE NOCASE")
    fun observeFolderTracks(folder: String): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks ORDER BY dateAdded DESC LIMIT :limit")
    fun observeRecentlyAdded(limit: Int): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks ORDER BY RANDOM() LIMIT :limit")
    suspend fun random(limit: Int): List<TrackEntity>

    @Query(
        """SELECT tracks.* FROM tracks JOIN tracks_fts ON tracks.id = tracks_fts.rowid
           WHERE tracks_fts MATCH :ftsQuery ORDER BY tracks.titleSort LIMIT :limit""",
    )
    suspend fun search(ftsQuery: String, limit: Int): List<TrackEntity>

    @Query("SELECT * FROM tracks WHERE enrichedAt IS NULL ORDER BY dateAdded DESC LIMIT :limit")
    suspend fun unenriched(limit: Int): List<TrackEntity>

    @Query(
        """UPDATE tracks SET codec = :codec, sampleRate = :sampleRate, bitDepth = :bitDepth, channels = :channels,
           bitrate = :bitrate, rgTrackGain = :rgTrackGain, rgTrackPeak = :rgTrackPeak, rgAlbumGain = :rgAlbumGain,
           rgAlbumPeak = :rgAlbumPeak, hasLyrics = :hasLyrics, durationMs = CASE WHEN :durationMs > 0 THEN :durationMs ELSE durationMs END,
           enrichedAt = :enrichedAt WHERE id = :id""",
    )
    suspend fun updateEnrichment(
        id: Long,
        codec: String?,
        sampleRate: Int?,
        bitDepth: Int?,
        channels: Int?,
        bitrate: Long?,
        rgTrackGain: Float?,
        rgTrackPeak: Float?,
        rgAlbumGain: Float?,
        rgAlbumPeak: Float?,
        hasLyrics: Boolean,
        durationMs: Long,
        enrichedAt: Long,
    )

    @Query("UPDATE tracks SET artworkPath = :path WHERE albumId = :albumId")
    suspend fun setAlbumArtwork(albumId: String, path: String)

    @Query("SELECT DISTINCT artworkPath FROM tracks WHERE artworkPath IS NOT NULL AND artworkPath != ''")
    suspend fun artworkPaths(): List<String>

    @Query("UPDATE tracks SET artworkPath = NULL WHERE artworkPath = :path")
    suspend fun clearArtworkPath(path: String)

    @Query("SELECT DISTINCT albumId FROM tracks WHERE artworkPath IS NULL LIMIT :limit")
    suspend fun albumsWithoutArtwork(limit: Int): List<String>

    @Query("SELECT * FROM tracks WHERE albumId = :albumId LIMIT 1")
    suspend fun firstOfAlbum(albumId: String): TrackEntity?

    @Query("DELETE FROM tracks WHERE source = :source AND seenAt < :scanStartedAt")
    suspend fun deleteStale(source: Int, scanStartedAt: Long): Int

    @Query("DELETE FROM tracks WHERE source = :source AND treeUri = :treeUri AND seenAt < :scanStartedAt")
    suspend fun deleteStaleInTree(source: Int, treeUri: String, scanStartedAt: Long): Int

    @Query("DELETE FROM tracks WHERE treeUri = :treeUri")
    suspend fun deleteTree(treeUri: String)

    @Query("UPDATE tracks SET seenAt = :at WHERE uri IN (:uris)")
    suspend fun markSeen(uris: List<String>, at: Long)
}

data class TrackFingerprint(val id: Long, val uri: String, val dateModified: Long, val sizeBytes: Long, val enrichedAt: Long?)

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlists WHERE deleted = 0 ORDER BY name COLLATE NOCASE")
    fun observePlaylists(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlists")
    suspend fun allIncludingDeleted(): List<PlaylistEntity>

    @Query("SELECT * FROM playlists WHERE id = :id")
    fun observePlaylist(id: Long): Flow<PlaylistEntity?>

    @Query("SELECT * FROM playlists WHERE id = :id")
    suspend fun byId(id: Long): PlaylistEntity?

    @Query("SELECT * FROM playlists WHERE syncId = :syncId")
    suspend fun bySyncId(syncId: String): PlaylistEntity?

    @Insert
    suspend fun insert(p: PlaylistEntity): Long

    @Upsert
    suspend fun upsert(p: PlaylistEntity)

    @Query("UPDATE playlists SET name = :name, updatedAt = :at, deviceId = :deviceId WHERE id = :id")
    suspend fun rename(id: Long, name: String, at: Long, deviceId: String)

    @Query("UPDATE playlists SET deleted = 1, updatedAt = :at, deviceId = :deviceId WHERE id = :id")
    suspend fun markDeleted(id: Long, at: Long, deviceId: String)

    @Query("UPDATE playlists SET updatedAt = :at, deviceId = :deviceId WHERE id = :id")
    suspend fun touch(id: Long, at: Long, deviceId: String)

    @Query("SELECT * FROM playlist_items WHERE playlistId = :playlistId ORDER BY position")
    fun observeItems(playlistId: Long): Flow<List<PlaylistItemEntity>>

    @Query("SELECT * FROM playlist_items WHERE playlistId = :playlistId ORDER BY position")
    suspend fun items(playlistId: Long): List<PlaylistItemEntity>

    @Query("SELECT COUNT(*) FROM playlist_items WHERE playlistId = :playlistId")
    suspend fun itemCount(playlistId: Long): Int

    @Query("DELETE FROM playlist_items WHERE playlistId = :playlistId")
    suspend fun clearItems(playlistId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertItems(items: List<PlaylistItemEntity>)

    @Transaction
    suspend fun replaceItems(playlistId: Long, items: List<PlaylistItemEntity>) {
        clearItems(playlistId)
        insertItems(items.mapIndexed { i, it -> it.copy(playlistId = playlistId, position = i) })
    }
}

@Dao
interface FavoriteDao {
    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE trackKey = :trackKey AND deleted = 0)")
    fun observeIsFavorite(trackKey: String): Flow<Boolean>

    @Query("SELECT * FROM favorites WHERE deleted = 0 ORDER BY updatedAt DESC")
    fun observeFavorites(): Flow<List<FavoriteEntity>>

    @Query("SELECT * FROM favorites")
    suspend fun allIncludingDeleted(): List<FavoriteEntity>

    @Query("SELECT * FROM favorites WHERE trackKey = :trackKey")
    suspend fun get(trackKey: String): FavoriteEntity?

    @Upsert
    suspend fun upsert(f: FavoriteEntity)

    @Upsert
    suspend fun upsertAll(f: List<FavoriteEntity>)
}

@Dao
interface HistoryDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(e: PlayEventEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(e: List<PlayEventEntity>)

    @Query(
        """SELECT trackKey, MAX(title) AS title, MAX(artist) AS artist, MAX(album) AS album,
           MAX(playedAt) AS lastPlayed, COUNT(*) AS plays
           FROM play_history GROUP BY trackKey ORDER BY lastPlayed DESC LIMIT :limit""",
    )
    fun observeRecent(limit: Int): Flow<List<RecentPlayRow>>

    @Query(
        """SELECT trackKey, MAX(title) AS title, MAX(artist) AS artist, MAX(album) AS album,
           MAX(playedAt) AS lastPlayed, COUNT(*) AS plays
           FROM play_history WHERE playedAt > :since GROUP BY trackKey ORDER BY plays DESC LIMIT :limit""",
    )
    fun observeMostPlayed(since: Long, limit: Int): Flow<List<RecentPlayRow>>

    @Query("SELECT * FROM play_history WHERE synced = 0 LIMIT :limit")
    suspend fun unsynced(limit: Int): List<PlayEventEntity>

    @Query("UPDATE play_history SET synced = 1 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<String>)

    @Query("SELECT * FROM play_history ORDER BY playedAt DESC LIMIT :limit")
    suspend fun latest(limit: Int): List<PlayEventEntity>

    @Query("DELETE FROM play_history")
    suspend fun clear()
}

@Dao
interface DiagnosticsDao {
    @Insert
    suspend fun insert(e: DiagnosticEventEntity)

    @Query("SELECT * FROM diagnostics ORDER BY atMs DESC, id DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<DiagnosticEventEntity>>

    @Query("SELECT * FROM diagnostics ORDER BY atMs DESC, id DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<DiagnosticEventEntity>

    @Query("DELETE FROM diagnostics WHERE atMs < :before")
    suspend fun prune(before: Long)

    @Query("DELETE FROM diagnostics")
    suspend fun clear()
}

@Dao
interface FolderDao {
    @Query("SELECT * FROM folders ORDER BY displayName COLLATE NOCASE")
    fun observeFolders(): Flow<List<FolderEntity>>

    @Query("SELECT * FROM folders")
    suspend fun all(): List<FolderEntity>

    @Upsert
    suspend fun upsert(f: FolderEntity)

    @Query("DELETE FROM folders WHERE treeUri = :treeUri")
    suspend fun delete(treeUri: String)
}

@Dao
interface RadioDao {
    @Query("SELECT * FROM radio_stations WHERE favoritedAt > 0 ORDER BY name COLLATE NOCASE")
    fun observeFavorites(): Flow<List<RadioStationEntity>>

    @Query("SELECT * FROM radio_stations WHERE lastPlayedAt > 0 ORDER BY lastPlayedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<RadioStationEntity>>

    @Query("SELECT * FROM radio_stations WHERE stationUuid = :uuid")
    suspend fun get(uuid: String): RadioStationEntity?

    @Upsert
    suspend fun upsert(s: RadioStationEntity)
}

@Dao
interface LyricsSidecarDao {
    @Upsert
    suspend fun upsertAll(items: List<LyricsSidecarEntity>)

    @Query("SELECT * FROM lyrics_sidecars WHERE trackUri = :trackUri")
    suspend fun get(trackUri: String): LyricsSidecarEntity?
}
