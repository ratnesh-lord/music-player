package app.unpaused.playback

import android.app.PendingIntent
import android.content.Intent
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.metadata.icy.IcyInfo
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import app.unpaused.AppContainer
import app.unpaused.MainActivity
import app.unpaused.R
import app.unpaused.core.audio.AudioQuality
import app.unpaused.core.audio.GainDecision
import app.unpaused.core.audio.ReplayGain
import app.unpaused.core.audio.ReplayGainInfo
import app.unpaused.core.audio.SourceQuality
import app.unpaused.core.diagnostics.InterruptionClassifier
import app.unpaused.core.diagnostics.Verdict
import app.unpaused.core.focus.FocusMode
import app.unpaused.core.playback.SleepTimer
import app.unpaused.core.playback.SleepTimerMath
import app.unpaused.container
import app.unpaused.data.db.TrackEntity
import app.unpaused.data.diagnostics.DiagnosticsLog
import app.unpaused.data.library.MetadataEnricher
import app.unpaused.data.library.TagReader
import app.unpaused.data.settings.AppSettings
import app.unpaused.data.user.HistoryRepository
import app.unpaused.playback.PlaybackStateStore.Companion.toMediaItem
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The playback engine. Runs as a Media3 MediaLibraryService, i.e. a `mediaPlayback` foreground service
 * while playing, which is what keeps music going when you switch apps or lock the phone. Audio focus is
 * handled by [AudioFocusController] (not ExoPlayer) so the Standard / Smart / Mix behaviours apply.
 */
class PlaybackService : MediaLibraryService() {

    private lateinit var app: AppContainer
    private lateinit var exo: ExoPlayer
    private lateinit var player: FocusAwarePlayer
    private lateinit var volume: VolumeMixer
    private lateinit var focus: AudioFocusController
    private lateinit var effects: AudioEffectsController
    private var session: MediaLibrarySession? = null

    private val scope = MainScope()
    private val handler = Handler(Looper.getMainLooper())
    private val audioManager by lazy { getSystemService(AudioManager::class.java) }

    private var settings = AppSettings()
    private var gain = GainDecision.NONE
    private var sleep: SleepTimer? = null
    private var preferredDevice: AudioDeviceInfo? = null
    private var devicesPrimed = false
    private var errorSkips = 0
    private var radioRetries = 0
    private var favoriteKeys: Set<String> = emptySet()

    /** Row data for items in the queue (ReplayGain, keys), keyed by media id. */
    private var queueInfo: Map<String, TrackEntity> = emptyMap()

    // Listening time of the current item, for history.
    private var listenItem: MediaItem? = null
    private var listenAccumMs = 0L
    private var listenStartedAt = 0L

    override fun onCreate() {
        super.onCreate()
        app = container
        settings = runBlocking { withTimeoutOrNull(1_000) { app.settings.settings.first() } } ?: AppSettings()

        exo = ExoPlayer.Builder(this, DefaultRenderersFactory(this).setEnableAudioFloatOutput(settings.hiResOutput))
            .setMediaSourceFactory(DefaultMediaSourceFactory(this, UnpausedExtractorsFactory()))
            // handleAudioFocus = false: AudioFocusController owns focus (Standard / Smart / Mix).
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), false)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setSkipSilenceEnabled(settings.skipSilence)
            .build()
        volume = VolumeMixer(exo)
        focus = AudioFocusController(this, exo, volume, app.diagnostics, app.playbackStatus, settings.focusSettings())
        player = FocusAwarePlayer(exo).also { it.focus = focus }
        focus.start()
        effects = AudioEffectsController(this, exo.audioSessionId, app.playbackStatus)

        setMediaNotificationProvider(DefaultMediaNotificationProvider.Builder(this).build().apply { setSmallIcon(R.drawable.ic_stat_unpaused) })
        applyKeepAlive()

        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaLibrarySession.Builder(this, player, LibraryCallback())
            .setSessionActivity(open)
            .setMediaButtonPreferences(buttons(favorite = false))
            .build()

        exo.addListener(PlayerEvents())
        exo.addAnalyticsListener(AudioEvents())
        audioManager.registerAudioDeviceCallback(deviceCallback, handler)
        refreshOutput()

        scope.launch { app.settings.settings.collect(::applySettings) }
        scope.launch { app.favorites.favorites().collect { list -> favoriteKeys = list.map { it.trackKey }.toSet(); updateButtons() } }
        scope.launch { detectUncleanExit() }
        handler.postDelayed(saveTick, SAVE_INTERVAL_MS)
        app.diagnostics.logOther(DiagnosticsLog.SERVICE_STARTED, Verdict.INFO, "Playback service started", settings.focusMode)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        saveQueue()
        // Media3's default keeps the service running while playing and stops it when paused.
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        commitListen()
        // A clean stop: record wasPlaying = false so the next start doesn't report a kill.
        PlaybackStateStore.position(exo, wasPlaying = false)?.let { runCatching { app.playbackState.savePositionBlocking(it) } }
        app.diagnostics.logOther(DiagnosticsLog.SERVICE_STOPPED, Verdict.INFO, "Playback service stopped", settings.focusMode)
        handler.removeCallbacksAndMessages(null)
        runCatching { audioManager.unregisterAudioDeviceCallback(deviceCallback) }
        focus.release()
        effects.release()
        session?.run {
            player.release()
            release()
        }
        session = null
        scope.cancel()
        super.onDestroy()
    }

    // ---- Settings ------------------------------------------------------------------------------

    private fun applySettings(s: AppSettings) {
        val old = settings
        settings = s
        focus.updateSettings(s.focusSettings())
        exo.skipSilenceEnabled = s.skipSilence
        if (old.keepAliveAfterPauseMin != s.keepAliveAfterPauseMin) applyKeepAlive()
        applyGain()
        updateButtons()
    }

    private fun applyKeepAlive() {
        setForegroundServiceTimeoutMs(settings.keepAliveAfterPauseMin.coerceIn(1, 10) * 60_000L)
    }

    // ---- ReplayGain & effects --------------------------------------------------------------------

    private fun applyGain(tagsFromStream: ReplayGainInfo? = null) {
        val item = exo.currentMediaItem
        val info = when {
            item == null || item.isRadio -> null
            tagsFromStream != null && !tagsFromStream.isEmpty -> tagsFromStream
            else -> queueInfo[item.mediaId]?.let { ReplayGainInfo(it.rgTrackGain, it.rgTrackPeak, it.rgAlbumGain, it.rgAlbumPeak) }
        }
        gain = if (info == null) GainDecision.NONE else ReplayGain.decide(info, settings.replayGainSettings())
        volume.setReplayGain(gain.attenuation)
        effects.apply(EffectsConfig.from(settings, gain.boostDb))
    }

    private fun refreshQueueInfo() {
        val ids = (0 until exo.mediaItemCount).mapNotNull { MediaIds.trackId(exo.getMediaItemAt(it).mediaId) }.distinct()
        scope.launch {
            queueInfo = app.library.tracks(ids).associateBy { MediaIds.TRACK + it.id }
            applyGain()
        }
    }

    // ---- History -----------------------------------------------------------------------------------

    private fun commitListen() {
        val item = listenItem ?: return
        val listened = listenAccumMs + if (listenStartedAt > 0) SystemClock.elapsedRealtime() - listenStartedAt else 0
        listenItem = null
        listenAccumMs = 0
        listenStartedAt = 0
        if (item.isRadio) return
        val row = queueInfo[item.mediaId]
        val key = item.trackKey ?: row?.trackKey ?: return
        val duration = row?.durationMs ?: item.mediaMetadata.durationMs ?: 0
        if (!HistoryRepository.countsAsPlay(listened, duration)) return
        val m = item.mediaMetadata
        scope.launch {
            app.history.record(key, m.title?.toString() ?: row?.title.orEmpty(), m.artist?.toString() ?: row?.artist.orEmpty(), m.albumTitle?.toString() ?: row?.album.orEmpty(), listened)
        }
    }

    private fun startListen(item: MediaItem?) {
        listenItem = item
        listenAccumMs = 0
        listenStartedAt = if (exo.isPlaying) SystemClock.elapsedRealtime() else 0
    }

    // ---- Persistence & recovery ----------------------------------------------------------------------

    private val saveTick = object : Runnable {
        override fun run() {
            if (exo.isPlaying) PlaybackStateStore.position(exo, wasPlaying = true)?.let { p -> scope.launch { app.playbackState.savePosition(p) } }
            handler.postDelayed(this, SAVE_INTERVAL_MS)
        }
    }

    private fun saveQueue() {
        val q = PlaybackStateStore.snapshot(exo, wasPlaying = exo.playWhenReady) ?: return
        scope.launch { app.playbackState.save(q) }
    }

    private fun savePosition() {
        val p = PlaybackStateStore.position(exo, wasPlaying = exo.playWhenReady) ?: return
        scope.launch { app.playbackState.savePosition(p) }
    }

    /** If the last saved state says "playing", the previous process ended without stopping: killed or crashed. */
    private suspend fun detectUncleanExit() {
        val saved = app.playbackState.load() ?: return
        if (!saved.wasPlaying) return
        val now = System.currentTimeMillis()
        if (now - saved.savedAt > 6 * 60 * 60 * 1000L) return
        val bootAt = now - SystemClock.elapsedRealtime()
        if (saved.savedAt < bootAt) {
            app.diagnostics.logOther(DiagnosticsLog.PROCESS_RESTARTED, Verdict.INFO, "The phone restarted while music was playing.", settings.focusMode)
        } else {
            app.diagnostics.logOther(
                DiagnosticsLog.PROCESS_RESTARTED, Verdict.UNEXPECTED,
                "Unpaused was stopped while playing (closed by Android to free memory, a battery manager, or a crash).",
                settings.focusMode, context = "last saved ${(now - saved.savedAt) / 1000}s before this start",
            )
        }
        val item = saved.items.getOrNull(saved.index) ?: return
        app.playbackState.savePosition(SavedPosition(item.mediaId, saved.index, saved.positionMs, wasPlaying = false, savedAt = now))
    }

    private suspend fun restoreQueue(play: Boolean): MediaSession.MediaItemsWithStartPosition? {
        val saved = app.playbackState.load() ?: return null
        val items = saved.items.map { it.toMediaItem() }
        val index = saved.index.coerceIn(0, items.size - 1)
        exo.shuffleModeEnabled = saved.shuffle
        exo.repeatMode = saved.repeatMode
        if (!play) {
            if (exo.mediaItemCount == 0) {
                exo.setMediaItems(items, index, if (saved.items[index].radio) C.TIME_UNSET else saved.positionMs)
                exo.prepare()
                app.diagnostics.logOther(DiagnosticsLog.QUEUE_RESTORED, Verdict.INFO, "Restored your queue (${items.size} items)", settings.focusMode)
            }
            return null
        }
        return MediaSession.MediaItemsWithStartPosition(items, index, if (saved.items[index].radio) C.TIME_UNSET else saved.positionMs)
    }

    // ---- Sleep timer ---------------------------------------------------------------------------

    private val sleepTick = object : Runnable {
        override fun run() {
            val t = sleep as? SleepTimer.At ?: return
            val now = System.currentTimeMillis()
            if (SleepTimerMath.isDue(t, now)) {
                focus.onUserPause()
                volume.setSleepFade(1f)
                setSleep(0, log = false)
                app.diagnostics.logOther(DiagnosticsLog.SLEEP_TIMER, Verdict.USER, "Sleep timer paused playback", settings.focusMode)
                return
            }
            volume.setSleepFade(SleepTimerMath.volumeAt(t, now))
            handler.postDelayed(this, 1_000)
        }
    }

    /** minutes: 0 = cancel, -1 = end of the current track. */
    private fun setSleep(minutes: Int, log: Boolean = true) {
        handler.removeCallbacks(sleepTick)
        volume.setSleepFade(1f)
        exo.pauseAtEndOfMediaItems = false
        sleep = when {
            minutes == -1 -> SleepTimer.EndOfTrack.also { exo.pauseAtEndOfMediaItems = true }
            minutes > 0 -> SleepTimerMath.start(System.currentTimeMillis(), minutes * 60_000L).also { handler.post(sleepTick) }
            else -> null
        }
        app.playbackStatus.setSleep(
            when (val s = sleep) {
                is SleepTimer.At -> SleepTimerUi(s.endAtMs, endOfTrack = false)
                SleepTimer.EndOfTrack -> SleepTimerUi(null, endOfTrack = true)
                null -> null
            },
        )
        if (log && minutes != 0) {
            val what = if (minutes == -1) "at the end of this track" else "in $minutes min"
            app.diagnostics.logOther(DiagnosticsLog.SLEEP_TIMER, Verdict.USER, "Sleep timer set: pause $what", settings.focusMode)
        }
    }

    // ---- Notification buttons ------------------------------------------------------------------

    private fun buttons(favorite: Boolean): ImmutableList<CommandButton> {
        val fav = CommandButton.Builder(if (favorite) CommandButton.ICON_HEART_FILLED else CommandButton.ICON_HEART_UNFILLED)
            .setDisplayName(getString(if (favorite) R.string.action_unfavorite else R.string.action_favorite))
            .setSessionCommand(SessionCommands.toggleFavorite)
            .build()
        val mix = settings.focusMode == FocusMode.MIX
        val mixButton = CommandButton.Builder(CommandButton.ICON_UNDEFINED)
            .setCustomIconResId(if (mix) R.drawable.ic_mix_on else R.drawable.ic_mix_off)
            .setDisplayName(getString(if (mix) R.string.action_mix_off else R.string.action_mix_on))
            .setSessionCommand(SessionCommands.toggleMix)
            .build()
        return ImmutableList.of(fav, mixButton)
    }

    private fun updateButtons() {
        val s = session ?: return
        val item = exo.currentMediaItem
        val key = item?.trackKey ?: item?.let { queueInfo[it.mediaId]?.trackKey }
        s.setMediaButtonPreferences(buttons(favorite = key != null && key in favoriteKeys))
    }

    // ---- Output devices ------------------------------------------------------------------------

    private fun refreshOutput() {
        app.playbackStatus.setOutput(runCatching { OutputInfoReader.current(audioManager, preferredDevice) }.getOrNull())
    }

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            refreshOutput()
            // The first callback lists the devices already connected at registration.
            if (!devicesPrimed) { devicesPrimed = true; return }
            val headset = addedDevices.any { it.isSink && it.type in HEADSET_TYPES }
            if (headset) {
                app.diagnostics.logOther(DiagnosticsLog.OUTPUT_CHANGED, Verdict.INFO, "Connected: " + addedDevices.filter { it.isSink }.joinToString { OutputInfoReader.label(it) }, settings.focusMode)
                if (settings.resumeOnHeadsetConnect && !exo.playWhenReady && exo.mediaItemCount > 0) {
                    app.diagnostics.logOther(DiagnosticsLog.HEADSET_RESUME, Verdict.APP_DECISION, "Resumed because headphones connected (setting is on)", settings.focusMode)
                    player.play()
                }
            }
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            if (preferredDevice != null && removedDevices.any { it.id == preferredDevice?.id }) {
                preferredDevice = null
                exo.setPreferredAudioDevice(null)
            }
            refreshOutput()
        }
    }

    // ---- Player events -------------------------------------------------------------------------

    private inner class PlayerEvents : Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            when (reason) {
                Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY -> focus.onNoisy()
                Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM -> {
                    // pauseAtEndOfMediaItems: the "end of track" sleep timer.
                    focus.onUserPause()
                    setSleep(0, log = false)
                    app.diagnostics.logOther(DiagnosticsLog.SLEEP_TIMER, Verdict.USER, "Sleep timer paused at the end of the track", settings.focusMode)
                }
            }
            if (!playWhenReady && reason != Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY &&
                reason != Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM
            ) {
                // Checked after the current focus dispatch has logged its own events.
                val at = System.currentTimeMillis()
                handler.post {
                    if (!InterruptionClassifier.isPauseExplained(at, app.diagnostics.recentFocusEvents())) {
                        app.diagnostics.logOther(DiagnosticsLog.UNEXPLAINED_PAUSE, Verdict.UNEXPECTED, "Playback paused without a recorded cause", settings.focusMode, context = "reason=$reason")
                    }
                }
            }
            saveQueue()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            // Queue finished: give audio focus back so other apps aren't kept waiting.
            if (playbackState == Player.STATE_ENDED) focus.onStopped()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                errorSkips = 0
                radioRetries = 0
                if (listenItem == null) startListen(exo.currentMediaItem)
                if (listenStartedAt == 0L) listenStartedAt = SystemClock.elapsedRealtime()
            } else if (listenStartedAt > 0) {
                listenAccumMs += SystemClock.elapsedRealtime() - listenStartedAt
                listenStartedAt = 0
                savePosition()
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            commitListen()
            startListen(mediaItem)
            app.playbackStatus.setIcyTitle(null)
            applyGain()
            updateButtons()
            if (mediaItem?.isRadio == true) MediaIds.radioUuid(mediaItem.mediaId)?.let { uuid -> scope.launch { runCatching { app.radio.markPlayed(uuid) } } }
            saveQueue()
        }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) {
                refreshQueueInfo()
                saveQueue()
            }
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = saveQueue()
        override fun onRepeatModeChanged(repeatMode: Int) = saveQueue()

        override fun onTracksChanged(tracks: Tracks) {
            val audio = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_AUDIO && it.isSelected } ?: return
            val tags = TagReader.read(audio.getTrackFormat(0).metadata)
            val fromStream = ReplayGain.fromTags(tags.values)
            if (!fromStream.isEmpty) applyGain(fromStream)
        }

        override fun onMetadata(metadata: Metadata) {
            for (i in 0 until metadata.length()) {
                val e = metadata.get(i)
                if (e is IcyInfo) app.playbackStatus.setIcyTitle(e.title?.takeIf { it.isNotBlank() })
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            val item = exo.currentMediaItem
            app.diagnostics.logOther(
                DiagnosticsLog.PLAYER_ERROR, Verdict.APP_DECISION,
                "Couldn't play “${item?.mediaMetadata?.title ?: "this item"}”: ${error.errorCodeName}",
                settings.focusMode, context = error.message.orEmpty(),
            )
            if (item?.isRadio == true) {
                // Streams drop; retry a few times with a pause in between.
                if (radioRetries++ < 3) handler.postDelayed({ exo.prepare() }, 3_000L * radioRetries)
                return
            }
            if (errorSkips++ < 3 && exo.hasNextMediaItem()) {
                exo.seekToNextMediaItem()
                exo.prepare()
            }
        }
    }

    private inner class AudioEvents : AnalyticsListener {
        override fun onAudioTrackInitialized(eventTime: AnalyticsListener.EventTime, audioTrackConfig: AudioSink.AudioTrackConfig) {
            app.playbackStatus.setAudioTrack(
                AudioTrackUi(
                    encoding = OutputInfoReader.encodingName(audioTrackConfig.encoding),
                    sampleRateHz = audioTrackConfig.sampleRate,
                    channels = Integer.bitCount(audioTrackConfig.channelConfig),
                    offload = audioTrackConfig.offload,
                ),
            )
            refreshOutput()
        }

        override fun onAudioInputFormatChanged(eventTime: AnalyticsListener.EventTime, format: Format, decoderReuseEvaluation: DecoderReuseEvaluation?) {
            val item = exo.currentMediaItem
            val row = item?.let { queueInfo[it.mediaId] }
            val codec = AudioQuality.codecFor(format.sampleMimeType, format.containerMimeType, row?.fileName)
            app.playbackStatus.setSource(
                SourceQuality(
                    codec = codec,
                    sampleRateHz = format.sampleRate.takeIf { it > 0 } ?: row?.sampleRate,
                    bitDepth = if (codec.lossless) MetadataEnricher.bitDepthOf(format.pcmEncoding) ?: row?.bitDepth else null,
                    channels = format.channelCount.takeIf { it > 0 } ?: row?.channels,
                    bitrateBps = listOf(format.averageBitrate, format.bitrate, format.peakBitrate).firstOrNull { it > 0 }?.toLong() ?: row?.bitrate,
                ),
            )
        }

        private var lastUnderrunLog = 0L

        override fun onAudioUnderrun(eventTime: AnalyticsListener.EventTime, bufferSize: Int, bufferSizeMs: Long, elapsedSinceLastFeedMs: Long) {
            val now = System.currentTimeMillis()
            if (now - lastUnderrunLog < 60_000) return
            lastUnderrunLog = now
            app.diagnostics.logOther("AUDIO_UNDERRUN", Verdict.INFO, "Audio buffer ran empty (a short glitch)", settings.focusMode, context = "buffer=${bufferSizeMs}ms sinceFeed=${elapsedSinceLastFeedMs}ms")
        }
    }

    // ---- Session callback --------------------------------------------------------------------

    private inner class LibraryCallback : MediaLibrarySession.Callback {

        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
            SessionCommands.all.forEach { commands.add(it) }
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(commands.build())
                .build()
        }

        override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo, customCommand: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                SessionCommands.TOGGLE_FAVORITE -> {
                    val item = exo.currentMediaItem ?: return error()
                    val row = queueInfo[item.mediaId]
                    val key = item.trackKey ?: row?.trackKey ?: return error()
                    return scope.future {
                        val now = app.favorites.toggle(key, item.mediaMetadata.title?.toString() ?: row?.title.orEmpty(), item.mediaMetadata.artist?.toString() ?: row?.artist.orEmpty())
                        SessionResult(SessionResult.RESULT_SUCCESS, Bundle().apply { putBoolean("favorite", now) })
                    }
                }
                SessionCommands.TOGGLE_MIX -> return scope.future {
                    app.settings.update { s ->
                        if (s.focusMode == FocusMode.MIX) s.copy(focusMode = s.lastNonMixMode) else s.copy(focusMode = FocusMode.MIX, lastNonMixMode = s.focusMode)
                    }
                    SessionResult(SessionResult.RESULT_SUCCESS)
                }
                SessionCommands.SLEEP_TIMER -> {
                    setSleep(args.getInt(SessionCommands.ARG_MINUTES, 0))
                    return ok()
                }
                SessionCommands.SET_OUTPUT -> {
                    val id = args.getInt(SessionCommands.ARG_DEVICE_ID, -1)
                    preferredDevice = if (id < 0) null else audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.id == id }
                    exo.setPreferredAudioDevice(preferredDevice)
                    refreshOutput()
                    app.diagnostics.logOther(DiagnosticsLog.OUTPUT_CHANGED, Verdict.USER, "Output set to " + (preferredDevice?.let(OutputInfoReader::label) ?: "automatic"), settings.focusMode)
                    return ok()
                }
                SessionCommands.RESTORE_QUEUE -> return scope.future {
                    restoreQueue(play = false)
                    SessionResult(SessionResult.RESULT_SUCCESS)
                }
            }
            return super.onCustomCommand(session, controller, customCommand, args)
        }

        override fun onAddMediaItems(mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>): ListenableFuture<MutableList<MediaItem>> =
            scope.future { resolve(mediaItems).toMutableList() }

        override fun onPlaybackResumption(mediaSession: MediaSession, controller: MediaSession.ControllerInfo, isForPlayback: Boolean): ListenableFuture<MediaSession.MediaItemsWithStartPosition> =
            scope.future {
                restoreQueue(play = true) ?: throw UnsupportedOperationException("Nothing to resume")
            }

        override fun onGetLibraryRoot(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, params: LibraryParams?): ListenableFuture<LibraryResult<MediaItem>> {
            val id = if (params?.isRecent == true) MediaIds.RECENT else MediaIds.ROOT
            return Futures.immediateFuture(LibraryResult.ofItem(browsableItem(id, getString(R.string.app_name)), params))
        }

        override fun onGetItem(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, mediaId: String): ListenableFuture<LibraryResult<MediaItem>> =
            scope.future {
                resolve(listOf(MediaItem.Builder().setMediaId(mediaId).build())).firstOrNull()
                    ?.let { LibraryResult.ofItem(it, null) } ?: LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
            }

        override fun onGetChildren(
            session: MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String, page: Int, pageSize: Int, params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
            val all: List<MediaItem> = when {
                parentId == MediaIds.ROOT -> listOf(
                    browsableItem(MediaIds.SONGS, getString(R.string.browse_songs)),
                    browsableItem(MediaIds.ALBUMS, getString(R.string.browse_albums)),
                    browsableItem(MediaIds.PLAYLISTS, getString(R.string.browse_playlists)),
                    browsableItem(MediaIds.FAVORITES, getString(R.string.browse_favorites)),
                )
                parentId == MediaIds.RECENT -> app.playbackState.load()?.let { q -> q.items.getOrNull(q.index)?.let { listOf(it.toMediaItem()) } } ?: emptyList()
                parentId == MediaIds.SONGS -> app.library.tracks().first().map { it.toMediaItem() }
                parentId == MediaIds.ALBUMS -> app.library.albums().first().map {
                    browsableItem(MediaIds.ALBUM + it.albumId, it.album, it.artist, it.artworkPath, MediaMetadata.MEDIA_TYPE_ALBUM)
                }
                parentId.startsWith(MediaIds.ALBUM) -> app.library.albumTracks(parentId.removePrefix(MediaIds.ALBUM)).first().map { it.toMediaItem() }
                parentId == MediaIds.PLAYLISTS -> app.playlists.playlists().first().map {
                    browsableItem(MediaIds.PLAYLIST + it.id, it.name, type = MediaMetadata.MEDIA_TYPE_PLAYLIST)
                }
                parentId.startsWith(MediaIds.PLAYLIST) -> parentId.removePrefix(MediaIds.PLAYLIST).toLongOrNull()?.let { id ->
                    app.library.resolveKeys(app.playlists.itemsNow(id).map { it.trackKey }).map { it.toMediaItem() }
                } ?: emptyList()
                parentId == MediaIds.FAVORITES -> app.library.resolveKeys(app.favorites.favorites().first().map { it.trackKey }).map { it.toMediaItem() }
                else -> emptyList()
            }
            val from = (page * pageSize).coerceAtMost(all.size)
            val to = if (pageSize <= 0) all.size else (from + pageSize).coerceAtMost(all.size)
            LibraryResult.ofItemList(ImmutableList.copyOf(all.subList(from, to)), params)
        }

        private fun ok(): ListenableFuture<SessionResult> = Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        private fun error(): ListenableFuture<SessionResult> = Futures.immediateFuture(SessionResult(SessionError.ERROR_BAD_VALUE))
    }

    /**
     * Turns the ids controllers send ("track:12", "radio:<uuid>") into playable items with URIs. Items the
     * UI built with a URI already are kept; a bare search query (voice) plays matching tracks.
     */
    private suspend fun resolve(items: List<MediaItem>): List<MediaItem> {
        val trackIds = items.mapNotNull { MediaIds.trackId(it.mediaId) }
        val rows = app.library.tracks(trackIds).associateBy { it.id }
        val out = ArrayList<MediaItem>(items.size)
        for (item in items) {
            val trackId = MediaIds.trackId(item.mediaId)
            val radio = MediaIds.radioUuid(item.mediaId)
            when {
                trackId != null -> rows[trackId]?.let { out += it.toMediaItem() }
                radio != null -> app.radio.station(radio)?.let { s ->
                    app.radio.remember(s)
                    out += s.toMediaItem().let { mi ->
                        if (s.streamUrl.contains(".m3u8", ignoreCase = true)) mi.buildUpon().setMimeType(MimeTypes.APPLICATION_M3U8).build() else mi
                    }
                }
                item.localConfiguration != null -> out += item
                !item.requestMetadata.searchQuery.isNullOrBlank() -> out += app.library.search(item.requestMetadata.searchQuery!!, 100).map { it.toMediaItem() }
            }
        }
        return out
    }

    companion object {
        private const val SAVE_INTERVAL_MS = 5_000L
        private val HEADSET_TYPES = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER,
        )
    }
}
