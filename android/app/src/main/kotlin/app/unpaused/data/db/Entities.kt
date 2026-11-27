package app.unpaused.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.FtsOptions
import androidx.room.Index
import androidx.room.PrimaryKey

object TrackSource {
    const val MEDIA_STORE = 0
    const val FOLDER = 1
}

/** One audio file on this device. */
@Entity(
    tableName = "tracks",
    indices = [
        Index(value = ["uri"], unique = true),
        Index("albumId"),
        Index("artistKey"),
        Index("trackKey"),
        Index("dateAdded"),
        Index("folderPath"),
        Index("genre"),
    ],
)
data class TrackEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uri: String,
    val source: Int,
    val mediaStoreId: Long? = null,
    /** SAF tree this file was found under (FOLDER source only). */
    val treeUri: String? = null,
    val title: String,
    val artist: String,
    val albumArtist: String?,
    val album: String,
    /** Stable album identity: normalised "albumArtist|album". */
    val albumId: String,
    val artistKey: String,
    val genre: String?,
    val year: Int?,
    val trackNumber: Int?,
    val discNumber: Int?,
    val durationMs: Long,
    val sizeBytes: Long,
    val mimeType: String?,
    val folderPath: String,
    val fileName: String,
    val dateAdded: Long,
    val dateModified: Long,
    /** Cross-device identity (see core TrackKey). */
    val trackKey: String,
    /** Sort keys (articles ignored). */
    val titleSort: String,
    val artistSort: String,
    val albumSort: String,
    // Filled in by MetadataEnricher.
    val codec: String? = null,
    val sampleRate: Int? = null,
    val bitDepth: Int? = null,
    val channels: Int? = null,
    val bitrate: Long? = null,
    val rgTrackGain: Float? = null,
    val rgTrackPeak: Float? = null,
    val rgAlbumGain: Float? = null,
    val rgAlbumPeak: Float? = null,
    val hasLyrics: Boolean = false,
    val enrichedAt: Long? = null,
    val artworkPath: String? = null,
    /** Last time a scan saw this file; stale rows are removed after a full scan. */
    val seenAt: Long = 0,
)

/** Full-text index over the searchable columns of [TrackEntity]. */
@Fts4(contentEntity = TrackEntity::class, tokenizer = FtsOptions.TOKENIZER_UNICODE61)
@Entity(tableName = "tracks_fts")
data class TrackFts(
    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String?,
    val genre: String?,
    val fileName: String,
)

@Entity(tableName = "playlists", indices = [Index(value = ["syncId"], unique = true)])
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Device-independent id used by sync. */
    val syncId: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    val deleted: Boolean = false,
    val deviceId: String,
)

@Entity(
    tableName = "playlist_items",
    primaryKeys = ["playlistId", "position"],
    indices = [Index("trackKey")],
)
data class PlaylistItemEntity(
    val playlistId: Long,
    val position: Int,
    val trackKey: String,
    /** Cached display data so items stay readable even if the file isn't on this device. */
    val title: String,
    val artist: String,
    val addedAt: Long,
)

@Entity(tableName = "favorites")
data class FavoriteEntity(
    @PrimaryKey val trackKey: String,
    val title: String,
    val artist: String,
    val updatedAt: Long,
    val deleted: Boolean = false,
    val deviceId: String,
)

@Entity(tableName = "play_history", indices = [Index("playedAt"), Index("trackKey")])
data class PlayEventEntity(
    @PrimaryKey val id: String,
    val trackKey: String,
    val title: String,
    val artist: String,
    val album: String,
    val playedAt: Long,
    val listenedMs: Long,
    val deviceId: String,
    val synced: Boolean = false,
)

@Entity(tableName = "diagnostics", indices = [Index("atMs")])
data class DiagnosticEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val atMs: Long,
    val type: String,
    val mode: String,
    val origin: String,
    val verdict: String,
    val message: String,
    /** Extra context, e.g. active playback usages at the time. */
    val context: String = "",
)

@Entity(tableName = "folders")
data class FolderEntity(
    @PrimaryKey val treeUri: String,
    val displayName: String,
    val addedAt: Long,
    val lastScanAt: Long = 0,
    val trackCount: Int = 0,
)

@Entity(tableName = "radio_stations")
data class RadioStationEntity(
    @PrimaryKey val stationUuid: String,
    val name: String,
    val streamUrl: String,
    val homepage: String?,
    val faviconUrl: String?,
    val tags: String?,
    val countryCode: String?,
    val codec: String?,
    val bitrateKbps: Int?,
    @ColumnInfo(defaultValue = "0") val favoritedAt: Long = 0,
    @ColumnInfo(defaultValue = "0") val lastPlayedAt: Long = 0,
)

@Entity(tableName = "lyrics_sidecars")
data class LyricsSidecarEntity(
    /** Track uri this .lrc belongs to. */
    @PrimaryKey val trackUri: String,
    val lrcUri: String,
)
