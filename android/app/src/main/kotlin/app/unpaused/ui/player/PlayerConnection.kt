package app.unpaused.ui.player

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import app.unpaused.data.db.RadioStationEntity
import app.unpaused.data.db.TrackEntity
import app.unpaused.playback.PlaybackService
import app.unpaused.playback.isRadio
import app.unpaused.playback.toMediaItem
import app.unpaused.playback.trackKey
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class NowPlaying(
    val mediaId: String,
    val title: String,
    val artist: String,
    val album: String,
    val artworkUri: Uri?,
    val isRadio: Boolean,
    val trackKey: String?,
    val durationMs: Long,
)

data class QueueEntry(val index: Int, val mediaId: String, val title: String, val artist: String)

data class PlayerUi(
    val connected: Boolean = false,
    val item: NowPlaying? = null,
    val isPlaying: Boolean = false,
    val playWhenReady: Boolean = false,
    val buffering: Boolean = false,
    val ended: Boolean = false,
    val shuffle: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val queue: List<QueueEntry> = emptyList(),
    val currentIndex: Int = -1,
    val error: String? = null,
)

data class Progress(val positionMs: Long = 0, val bufferedMs: Long = 0, val durationMs: Long = 0)

/** The UI's handle on [PlaybackService], through a Media3 MediaController. Main thread only. */
class PlayerConnection(private val context: Context, private val scope: CoroutineScope) {

    private val _state = MutableStateFlow(PlayerUi())
    val state: StateFlow<PlayerUi> = _state.asStateFlow()
    private val _progress = MutableStateFlow(Progress())
    val progress: StateFlow<Progress> = _progress.asStateFlow()

    private var future: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var ticker: Job? = null
    private val onConnected = ArrayList<(MediaController) -> Unit>()

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            sync(rebuildQueue = events.contains(Player.EVENT_TIMELINE_CHANGED))
        }
    }

    fun connect() {
        if (future != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val f = MediaController.Builder(context, token).buildAsync()
        future = f
        f.addListener({
            val c = runCatching { f.get() }.getOrNull() ?: run { future = null; return@addListener }
            controller = c
            c.addListener(listener)
            sync(rebuildQueue = true)
            onConnected.forEach { it(c) }
            onConnected.clear()
        }, ContextCompat.getMainExecutor(context))
    }

    fun release() {
        ticker?.cancel()
        controller?.removeListener(listener)
        future?.let { MediaController.releaseFuture(it) }
        future = null
        controller = null
        _state.value = PlayerUi()
    }

    private fun withController(block: (MediaController) -> Unit) {
        val c = controller
        if (c != null) block(c) else { onConnected += block; connect() }
    }

    // ---- Commands -------------------------------------------------------------------------------

    fun playTracks(tracks: List<TrackEntity>, startIndex: Int = 0, shuffle: Boolean = false) {
        if (tracks.isEmpty()) return
        withController { c ->
            val start = if (shuffle) tracks.indices.random() else startIndex.coerceIn(0, tracks.size - 1)
            c.shuffleModeEnabled = shuffle
            c.setMediaItems(tracks.map { it.toMediaItem() }, start, 0)
            c.prepare()
            c.play()
        }
    }

    fun playRadio(station: RadioStationEntity) = withController { c ->
        c.setMediaItem(station.toMediaItem())
        c.prepare()
        c.play()
    }

    /** A file opened from another app ("Open with"). The URI travels in request metadata. */
    fun playUri(uri: Uri, title: String?) = withController { c ->
        val item = MediaItem.Builder()
            .setMediaId("uri:$uri")
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
            .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())
            .build()
        c.setMediaItem(item)
        c.prepare()
        c.play()
    }

    fun playNext(tracks: List<TrackEntity>) = withController { c ->
        val at = if (c.mediaItemCount == 0) 0 else c.currentMediaItemIndex + 1
        c.addMediaItems(at, tracks.map { it.toMediaItem() })
        if (c.mediaItemCount == tracks.size) c.prepare()
    }

    fun addToQueue(tracks: List<TrackEntity>) = withController { c ->
        c.addMediaItems(tracks.map { it.toMediaItem() })
        if (c.mediaItemCount == tracks.size) c.prepare()
    }

    fun togglePlay() = withController { c ->
        if (c.playWhenReady && c.playbackState != Player.STATE_ENDED) {
            c.pause()
        } else {
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            if (c.playbackState == Player.STATE_ENDED) c.seekToDefaultPosition()
            c.play()
        }
    }

    fun next() = withController { it.seekToNext() }
    fun previous() = withController { it.seekToPrevious() }
    fun seekTo(ms: Long) = withController { it.seekTo(ms.coerceAtLeast(0)) }
    fun skipTo(index: Int) = withController { c -> if (index in 0 until c.mediaItemCount) { c.seekToDefaultPosition(index); c.play() } }
    fun setShuffle(on: Boolean) = withController { it.shuffleModeEnabled = on }
    fun cycleRepeat() = withController { c ->
        c.repeatMode = when (c.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }
    fun move(from: Int, to: Int) = withController { c -> if (from in 0 until c.mediaItemCount && to in 0 until c.mediaItemCount) c.moveMediaItem(from, to) }
    fun remove(index: Int) = withController { c -> if (index in 0 until c.mediaItemCount) c.removeMediaItem(index) }
    fun clearQueue() = withController { it.clearMediaItems() }

    suspend fun command(action: String, args: Bundle = Bundle.EMPTY): SessionResult? {
        val c = controller ?: return null
        return runCatching { c.sendCustomCommand(SessionCommand(action, Bundle.EMPTY), args).await() }.getOrNull()
    }

    fun commandAsync(action: String, args: Bundle = Bundle.EMPTY) {
        scope.launch { command(action, args) }
    }

    fun onReady(block: (MediaController) -> Unit) = withController(block)

    // ---- State ----------------------------------------------------------------------------------

    private fun sync(rebuildQueue: Boolean) {
        val c = controller ?: return
        val item = c.currentMediaItem
        val queue = if (rebuildQueue || _state.value.queue.size != c.mediaItemCount) {
            (0 until c.mediaItemCount).map { i ->
                val m = c.getMediaItemAt(i)
                QueueEntry(i, m.mediaId, m.mediaMetadata.title?.toString() ?: "Unknown", m.mediaMetadata.artist?.toString() ?: "")
            }
        } else {
            _state.value.queue
        }
        _state.value = PlayerUi(
            connected = true,
            item = item?.let {
                val md = c.mediaMetadata
                NowPlaying(
                    mediaId = it.mediaId,
                    title = (md.title ?: it.mediaMetadata.title)?.toString() ?: "Unknown",
                    artist = (md.artist ?: it.mediaMetadata.artist)?.toString() ?: "",
                    album = (md.albumTitle ?: it.mediaMetadata.albumTitle)?.toString() ?: "",
                    artworkUri = it.mediaMetadata.artworkUri,
                    isRadio = it.isRadio,
                    trackKey = it.trackKey,
                    durationMs = c.duration.takeIf { d -> d > 0 } ?: it.mediaMetadata.durationMs ?: 0,
                )
            },
            isPlaying = c.isPlaying,
            playWhenReady = c.playWhenReady,
            buffering = c.playbackState == Player.STATE_BUFFERING,
            ended = c.playbackState == Player.STATE_ENDED,
            shuffle = c.shuffleModeEnabled,
            repeatMode = c.repeatMode,
            queue = queue,
            currentIndex = c.currentMediaItemIndex,
            error = c.playerError?.let { e -> e.errorCodeName },
        )
        updateProgress(c)
        if (c.isPlaying && ticker?.isActive != true) {
            ticker = scope.launch {
                while (isActive) {
                    controller?.let(::updateProgress)
                    delay(250)
                }
            }
        } else if (!c.isPlaying) {
            ticker?.cancel()
        }
    }

    private fun updateProgress(c: MediaController) {
        _progress.value = Progress(c.currentPosition.coerceAtLeast(0), c.bufferedPosition.coerceAtLeast(0), c.duration.coerceAtLeast(0))
    }
}
