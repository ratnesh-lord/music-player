package app.unpaused.data.library

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.Size
import app.unpaused.data.db.TrackEntity
import app.unpaused.data.db.TrackSource
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * Extracts one cover per album into the app's cache (never uploaded anywhere). An empty path in the
 * database means "looked, found nothing", so albums without art aren't re-read on every scan.
 */
class ArtworkCache(private val context: Context) {

    private val dir: File get() = File(context.cacheDir, "artwork").apply { mkdirs() }

    fun fileFor(albumId: String): File = File(dir, sha1(albumId) + ".jpg")

    /** Returns the written path, or "" when the album has no artwork we can read. */
    fun extract(track: TrackEntity, folderCover: Uri?): String {
        val target = fileFor(track.albumId)
        if (target.exists() && target.length() > 0) return target.absolutePath
        val bitmap = embedded(track.uri)
            ?: folderCover?.let(::decodeUri)
            ?: if (track.source == TrackSource.MEDIA_STORE) thumbnail(track.uri) else null
        bitmap ?: return ""
        return runCatching {
            val scaled = scale(bitmap)
            FileOutputStream(target).use { scaled.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            if (scaled !== bitmap) scaled.recycle()
            bitmap.recycle()
            target.absolutePath
        }.getOrDefault("")
    }

    fun exists(path: String): Boolean = path.isEmpty() || File(path).exists()

    private fun embedded(uri: String): Bitmap? = runCatching {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(context, Uri.parse(uri))
            r.embeddedPicture?.let(::decodeBytes)
        } finally {
            r.release()
        }
    }.getOrNull()

    private fun thumbnail(uri: String): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return runCatching { context.contentResolver.loadThumbnail(Uri.parse(uri), Size(MAX_PX, MAX_PX), null) }.getOrNull()
    }

    private fun decodeUri(uri: Uri): Bitmap? = runCatching {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        decodeBytes(bytes)
    }.getOrNull()

    private fun decodeBytes(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= MAX_PX && bounds.outHeight / (sample * 2) >= MAX_PX) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun scale(b: Bitmap): Bitmap {
        val longest = maxOf(b.width, b.height)
        if (longest <= MAX_PX) return b
        val f = MAX_PX.toFloat() / longest
        return Bitmap.createScaledBitmap(b, (b.width * f).toInt().coerceAtLeast(1), (b.height * f).toInt().coerceAtLeast(1), true)
    }

    companion object {
        private const val MAX_PX = 512

        fun sha1(s: String): String = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
