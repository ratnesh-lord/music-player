package app.unpaused.playback

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class SavedItem(
    val mediaId: String,
    val uri: String,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val artworkUri: String? = null,
    val durationMs: Long? = null,
    val trackKey: String? = null,
    val radio: Boolean = false,
)

@Serializable
data class SavedQueue(
    val items: List<SavedItem>,
    val index: Int,
    val positionMs: Long,
    val shuffle: Boolean,
    val repeatMode: Int,
    /** True while music is playing; still true after a crash or a kill by Android. */
    val wasPlaying: Boolean,
    val savedAt: Long,
)

/** Position-only save: written every few seconds while playing, so the big queue file isn't rewritten. */
@Serializable
data class SavedPosition(
    val mediaId: String,
    val index: Int,
    val positionMs: Long,
    val wasPlaying: Boolean,
    val savedAt: Long,
)

/**
 * Persists the queue and position to small JSON files so playback survives the process being killed,
 * a reboot (via Media3 playback resumption) or the app being swiped away. File writes are atomic
 * (write to a temp file, then rename).
 */
class PlaybackStateStore(context: Context) {

    private val file = File(context.filesDir, "playback_state.json")
    private val positionFile = File(context.filesDir, "playback_position.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    suspend fun load(): SavedQueue? = withContext(Dispatchers.IO) { loadBlocking() }

    @Synchronized
    fun loadBlocking(): SavedQueue? {
        val q = runCatching { json.decodeFromString(SavedQueue.serializer(), file.readText()) }.getOrNull()
            ?.takeIf { it.items.isNotEmpty() } ?: return null
        val p = runCatching { json.decodeFromString(SavedPosition.serializer(), positionFile.readText()) }.getOrNull()
        return if (p != null && p.savedAt >= q.savedAt && p.index in q.items.indices && q.items[p.index].mediaId == p.mediaId) {
            q.copy(index = p.index, positionMs = p.positionMs, wasPlaying = p.wasPlaying, savedAt = p.savedAt)
        } else {
            q
        }
    }

    suspend fun save(queue: SavedQueue) = withContext(Dispatchers.IO) {
        synchronized(this@PlaybackStateStore) {
            writeAtomic(file, json.encodeToString(SavedQueue.serializer(), queue))
            positionFile.delete()
        }
    }

    suspend fun savePosition(position: SavedPosition) = withContext(Dispatchers.IO) { savePositionBlocking(position) }

    /** Small enough to call on the main thread from onDestroy. */
    @Synchronized
    fun savePositionBlocking(position: SavedPosition) {
        writeAtomic(positionFile, json.encodeToString(SavedPosition.serializer(), position))
    }

    private fun writeAtomic(target: File, text: String) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) {
            target.delete()
            tmp.renameTo(target)
        }
    }

    companion object {
        fun snapshot(player: Player, wasPlaying: Boolean = player.isPlaying): SavedQueue? {
            val count = player.mediaItemCount
            if (count == 0) return null
            val items = (0 until count).map { player.getMediaItemAt(it).toSaved() }
            return SavedQueue(
                items = items,
                index = player.currentMediaItemIndex.coerceIn(0, count - 1),
                positionMs = player.currentPosition.coerceAtLeast(0),
                shuffle = player.shuffleModeEnabled,
                repeatMode = player.repeatMode,
                wasPlaying = wasPlaying,
                savedAt = System.currentTimeMillis(),
            )
        }

        fun position(player: Player, wasPlaying: Boolean = player.isPlaying): SavedPosition? {
            val item = player.currentMediaItem ?: return null
            return SavedPosition(item.mediaId, player.currentMediaItemIndex, player.currentPosition.coerceAtLeast(0), wasPlaying, System.currentTimeMillis())
        }

        private fun MediaItem.toSaved(): SavedItem = SavedItem(
            mediaId = mediaId,
            uri = localConfiguration?.uri?.toString() ?: requestMetadata.mediaUri?.toString() ?: "",
            title = mediaMetadata.title?.toString(),
            artist = mediaMetadata.artist?.toString(),
            album = mediaMetadata.albumTitle?.toString(),
            artworkUri = mediaMetadata.artworkUri?.toString(),
            durationMs = mediaMetadata.durationMs,
            trackKey = trackKey,
            radio = isRadio,
        )

        fun SavedItem.toMediaItem(): MediaItem {
            val extras = Bundle().apply {
                trackKey?.let { putString(MediaExtras.TRACK_KEY, it) }
                if (radio) putBoolean(MediaExtras.IS_RADIO, true)
            }
            return MediaItem.Builder()
                .setMediaId(mediaId)
                .setUri(uri)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(title)
                        .setArtist(artist)
                        .setAlbumTitle(album)
                        .setArtworkUri(artworkUri?.let(Uri::parse))
                        .setDurationMs(durationMs)
                        .setIsPlayable(true)
                        .setIsBrowsable(false)
                        .setMediaType(if (radio) MediaMetadata.MEDIA_TYPE_RADIO_STATION else MediaMetadata.MEDIA_TYPE_MUSIC)
                        .setExtras(extras)
                        .build(),
                )
                .build()
        }
    }
}
