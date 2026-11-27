package app.unpaused.playback

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import app.unpaused.data.db.RadioStationEntity
import app.unpaused.data.db.TrackEntity
import java.io.File

/** Media ids used in the session: "track:<row id>" and "radio:<station uuid>". */
object MediaIds {
    const val TRACK = "track:"
    const val RADIO = "radio:"
    const val ROOT = "root"
    /** Root for Android's "recent media" (System UI playback resumption). */
    const val RECENT = "recent"
    const val SONGS = "songs"
    const val ALBUMS = "albums"
    const val ALBUM = "album:"
    const val PLAYLISTS = "playlists"
    const val PLAYLIST = "playlist:"
    const val FAVORITES = "favorites"

    fun trackId(mediaId: String): Long? = if (mediaId.startsWith(TRACK)) mediaId.removePrefix(TRACK).toLongOrNull() else null
    fun radioUuid(mediaId: String): String? = if (mediaId.startsWith(RADIO)) mediaId.removePrefix(RADIO) else null
}

object MediaExtras {
    const val TRACK_KEY = "unpaused.trackKey"
    const val CODEC = "unpaused.codec"
    const val SIZE = "unpaused.size"
    const val IS_RADIO = "unpaused.radio"
}

fun TrackEntity.toMediaItem(): MediaItem {
    val extras = Bundle().apply {
        putString(MediaExtras.TRACK_KEY, trackKey)
        putLong(MediaExtras.SIZE, sizeBytes)
        codec?.let { putString(MediaExtras.CODEC, it) }
    }
    val metadata = MediaMetadata.Builder()
        .setTitle(title)
        .setArtist(artist)
        .setAlbumTitle(album)
        .setAlbumArtist(albumArtist)
        .setGenre(genre)
        .setTrackNumber(trackNumber)
        .setDiscNumber(discNumber)
        .setRecordingYear(year)
        .setDurationMs(durationMs.takeIf { it > 0 })
        .setArtworkUri(artworkPath?.takeIf { it.isNotEmpty() }?.let { Uri.fromFile(File(it)) })
        .setIsBrowsable(false)
        .setIsPlayable(true)
        .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
        .setExtras(extras)
        .build()
    return MediaItem.Builder()
        .setMediaId(MediaIds.TRACK + id)
        .setUri(uri)
        .setMediaMetadata(metadata)
        .build()
}

fun RadioStationEntity.toMediaItem(): MediaItem {
    val subtitle = listOfNotNull(
        tags?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.take(3)?.joinToString(" · ")?.ifEmpty { null },
        countryCode,
    ).joinToString(" · ").ifEmpty { "Internet radio" }
    val metadata = MediaMetadata.Builder()
        .setTitle(name)
        .setStation(name)
        .setArtist(subtitle)
        .setArtworkUri(faviconUrl?.takeIf { it.startsWith("https://") }?.let(Uri::parse))
        .setIsBrowsable(false)
        .setIsPlayable(true)
        .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION)
        .setExtras(Bundle().apply { putBoolean(MediaExtras.IS_RADIO, true) })
        .build()
    return MediaItem.Builder()
        .setMediaId(MediaIds.RADIO + stationUuid)
        .setUri(streamUrl)
        .setMediaMetadata(metadata)
        .build()
}

fun browsableItem(id: String, title: String, subtitle: String? = null, artwork: String? = null, type: Int = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED): MediaItem =
    MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setArtworkUri(artwork?.takeIf { it.isNotEmpty() }?.let { Uri.fromFile(File(it)) })
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(type)
                .build(),
        )
        .build()

val MediaItem.trackKey: String? get() = mediaMetadata.extras?.getString(MediaExtras.TRACK_KEY)
val MediaItem.isRadio: Boolean get() = mediaId.startsWith(MediaIds.RADIO)
