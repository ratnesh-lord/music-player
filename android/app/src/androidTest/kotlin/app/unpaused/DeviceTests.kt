package app.unpaused

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.unpaused.core.focus.FocusMode
import app.unpaused.playback.PlaybackService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

/** 20 s, 16-bit stereo 44.1 kHz sine as a WAV file in the app's cache. */
private fun toneFile(): File {
    val rate = 44_100
    val frames = rate * 20
    val buf = ByteBuffer.allocate(44 + frames * 4).order(ByteOrder.LITTLE_ENDIAN)
    buf.put("RIFF".toByteArray()).putInt(36 + frames * 4).put("WAVE".toByteArray()).put("fmt ".toByteArray())
        .putInt(16).putShort(1).putShort(2).putInt(rate).putInt(rate * 4).putShort(4).putShort(16)
        .put("data".toByteArray()).putInt(frames * 4)
    for (i in 0 until frames) {
        val s = (sin(2 * PI * 440 * i / rate) * 6000).toInt().toShort()
        buf.putShort(s).putShort(s)
    }
    return File(context.cacheDir, "tone.wav").apply { writeBytes(buf.array()) }
}

private suspend fun controller(): MediaController = withContext(Dispatchers.Main) {
    MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync().await()
}

private suspend fun MediaController.playFile(file: File) = withContext(Dispatchers.Main) {
    setMediaItem(
        MediaItem.Builder().setMediaId("uri:test")
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(Uri.fromFile(file)).build())
            .setMediaMetadata(MediaMetadata.Builder().setTitle("Test tone").setArtist("Unpaused").build())
            .build(),
    )
    prepare()
    play()
}

private suspend fun MediaController.awaitPlaying(expected: Boolean, timeoutMs: Long) {
    val ok = withTimeoutOrNull(timeoutMs) {
        while (withContext(Dispatchers.Main) { isPlaying } != expected) delay(100)
        true
    }
    if (ok == null) {
        val state = withContext(Dispatchers.Main) {
            "state=$playbackState playWhenReady=$playWhenReady items=$mediaItemCount error=${playerError?.errorCodeName} ${playerError?.message}"
        }
        throw AssertionError("isPlaying never became $expected within $timeoutMs ms ($state)")
    }
}

/** Acts as "another app" taking permanent audio focus, as a game or video app does. */
private class OtherApp {
    private val am = context.getSystemService(AudioManager::class.java)
    private val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
        .setOnAudioFocusChangeListener { }
        .build()
    fun takeFocus() = am.requestAudioFocus(request)
    fun release() = am.abandonAudioFocusRequest(request)
}

private suspend fun setMode(mode: FocusMode) {
    val app = context.applicationContext as UnpausedApp
    app.container.settings.update { it.copy(focusMode = mode) }
    delay(500)
}

/**
 * Runs on a real Android audio stack (emulator in CI): the playback service, our focus handling and
 * ExoPlayer together. "Another app" is a second audio-focus client in the test.
 */
@RunWith(AndroidJUnit4::class)
class PlaybackDeviceTest {

    @Test
    fun smartModePausesForAnotherAppAndResumesWhenItIsDone() = runBlocking {
        setMode(FocusMode.SMART)
        val c = controller()
        try {
            c.playFile(toneFile())
            c.awaitPlaying(true, 15_000)
            val other = OtherApp()
            withContext(Dispatchers.Main) { other.takeFocus() }
            c.awaitPlaying(false, 10_000)
            withContext(Dispatchers.Main) { other.release() }
            // Smart mode: quiet for 3 s → asks for focus again and resumes.
            c.awaitPlaying(true, 20_000)
        } finally {
            withContext(Dispatchers.Main) { c.stop(); c.release() }
        }
    }

    @Test
    fun mixModeKeepsPlayingWhenAnotherAppTakesFocus() = runBlocking {
        setMode(FocusMode.MIX)
        val c = controller()
        try {
            c.playFile(toneFile())
            c.awaitPlaying(true, 15_000)
            val other = OtherApp()
            withContext(Dispatchers.Main) { other.takeFocus() }
            delay(5_000)
            assertTrue("Mix mode must keep playing", withContext(Dispatchers.Main) { c.isPlaying })
            withContext(Dispatchers.Main) { other.release() }
        } finally {
            withContext(Dispatchers.Main) { c.stop(); c.release() }
            setMode(FocusMode.SMART)
        }
    }

    @Test
    fun standardModeStaysPausedAfterAnotherAppTakesFocus() = runBlocking {
        setMode(FocusMode.STANDARD)
        val c = controller()
        try {
            c.playFile(toneFile())
            c.awaitPlaying(true, 15_000)
            val other = OtherApp()
            withContext(Dispatchers.Main) { other.takeFocus() }
            c.awaitPlaying(false, 10_000)
            withContext(Dispatchers.Main) { other.release() }
            delay(8_000)
            assertFalse(withContext(Dispatchers.Main) { c.isPlaying })
        } finally {
            withContext(Dispatchers.Main) { c.stop(); c.release() }
            setMode(FocusMode.SMART)
        }
    }
}

/** Launches the UI and saves screenshots (pulled by CI) so the design can be checked without a phone. */
@RunWith(AndroidJUnit4::class)
class UiDeviceTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private fun shot(name: String) {
        compose.waitForIdle()
        val full = compose.onRoot().captureToImage().asAndroidBitmap()
        val small = Bitmap.createScaledBitmap(full, 540, full.height * 540 / full.width, true)
        val dir = File(context.filesDir, "screens").apply { mkdirs() }
        FileOutputStream(File(dir, "$name.jpg")).use { small.compress(Bitmap.CompressFormat.JPEG, 80, it) }
    }

    @Test
    fun launchesOnboardingHomePlayerAndSettings() {
        compose.waitUntil(10_000) { compose.onAllNodesWithText("A music player that keeps playing.").fetchSemanticsNodes().isNotEmpty() }
        shot("01-onboarding")
        compose.onNodeWithText("SKIP FOR NOW").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("UNPAUSED").fetchSemanticsNodes().isNotEmpty() }
        shot("02-home")

        runBlocking { controller().playFile(toneFile()) }
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Test tone").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText("Test tone")[0].performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("NOW PLAYING").fetchSemanticsNodes().isNotEmpty() }
        Thread.sleep(1_500)
        shot("03-now-playing")
        compose.onNodeWithText("AUDIO").performClick()
        Thread.sleep(500)
        shot("04-audio-panel")
    }
}
