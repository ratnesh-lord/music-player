package app.unpaused.playback

import android.app.Application
import android.content.Context
import android.media.AudioManager
import android.os.Looper
import android.os.SystemClock
import androidx.media3.exoplayer.ExoPlayer
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.unpaused.core.focus.FocusMode
import app.unpaused.core.focus.FocusSettings
import app.unpaused.data.db.AppDatabase
import app.unpaused.data.diagnostics.DiagnosticsLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAudioManager
import java.time.Duration

/**
 * Drives the real Android glue (AudioManager focus requests and callbacks, main-looper timers,
 * ExoPlayer play/pause) through Robolectric. The pure policy has its own tests in :core.
 * SDK 30 takes the audio-mode polling path instead of the API 31 listener.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30], application = Application::class)
class AudioFocusControllerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var db: AppDatabase
    private lateinit var exo: ExoPlayer
    private lateinit var status: PlaybackStatus
    private lateinit var shadowAudio: ShadowAudioManager
    private lateinit var controller: AudioFocusController

    private fun start(mode: FocusMode) {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        exo = ExoPlayer.Builder(context).build()
        status = PlaybackStatus()
        shadowAudio = shadowOf(context.getSystemService(AudioManager::class.java))
        controller = AudioFocusController(
            context, exo, VolumeMixer(exo), DiagnosticsLog(db.diagnostics(), scope), status,
            FocusSettings(mode = mode, autoResumeWindowMs = 300_000, quietPeriodMs = 3_000),
            clock = { SystemClock.uptimeMillis() },
        )
        controller.start()
    }

    @Before
    fun setUp() = Unit

    @After
    fun tearDown() {
        controller.release()
        exo.release()
        scope.cancel()
        db.close()
    }

    private fun idle(seconds: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(seconds))
    private fun lastListener() = shadowAudio.lastAudioFocusRequest!!.listener!!
    private fun lastGain() = shadowAudio.lastAudioFocusRequest!!.durationHint

    @Test
    fun smartModeResumesOnceTheOtherAppIsQuiet() {
        start(FocusMode.SMART)
        controller.onUserPlay()
        assertEquals(AudioManager.AUDIOFOCUS_GAIN, lastGain())
        assertTrue(exo.playWhenReady)
        val firstRequest = shadowAudio.lastAudioFocusRequest

        // A game takes permanent focus and keeps playing sound.
        shadowAudio.setIsMusicActive(true)
        lastListener().onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
        assertFalse(exo.playWhenReady)
        idle(10)
        assertFalse("must not resume while the other app is still playing", exo.playWhenReady)

        // The game goes quiet: after the quiet period Smart mode asks for focus again and resumes.
        shadowAudio.setIsMusicActive(false)
        idle(5)
        assertTrue(shadowAudio.lastAudioFocusRequest !== firstRequest)
        assertTrue(exo.playWhenReady)
    }

    @Test
    fun standardModeWaitsForTheUserAfterPermanentLoss() {
        start(FocusMode.STANDARD)
        controller.onUserPlay()
        lastListener().onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
        shadowAudio.setIsMusicActive(false)
        idle(30)
        assertFalse(exo.playWhenReady)
    }

    @Test
    fun transientLossPausesAndGainResumes() {
        start(FocusMode.SMART)
        controller.onUserPlay()
        val listener = lastListener()
        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        assertFalse(exo.playWhenReady)
        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        assertTrue(exo.playWhenReady)
    }

    @Test
    fun duckingLowersAndRestoresVolume() {
        start(FocusMode.SMART)
        controller.onUserPlay()
        val listener = lastListener()
        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
        assertTrue(exo.playWhenReady)
        assertTrue(exo.volume < 1f)
        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        assertEquals(1f, exo.volume, 0.001f)
    }

    @Test
    fun mixModePlaysWithoutHoldingFocus() {
        start(FocusMode.MIX)
        controller.onUserPlay()
        assertTrue(exo.playWhenReady)
        // Only the brief "unmute" ping is requested, never lasting focus.
        assertEquals(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK, lastGain())
        idle(5)
        assertTrue(exo.playWhenReady)
        assertFalse(status.focus.value.holdsFocus)
    }

    @Test
    fun userPauseIsNotUndoneBySmartMode() {
        start(FocusMode.SMART)
        controller.onUserPlay()
        controller.onUserPause()
        shadowAudio.setIsMusicActive(false)
        idle(30)
        assertFalse(exo.playWhenReady)
    }
}
