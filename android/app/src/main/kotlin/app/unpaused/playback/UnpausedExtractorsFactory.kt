package app.unpaused.playback

import android.net.Uri
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorsFactory

/** Media3's default extractors plus [AiffExtractor] (tried first; its sniff only peeks 12 bytes). */
class UnpausedExtractorsFactory : ExtractorsFactory {

    private val defaults = DefaultExtractorsFactory()
        .setConstantBitrateSeekingEnabled(true)

    override fun createExtractors(): Array<Extractor> = arrayOf(AiffExtractor(), *defaults.createExtractors())

    override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> =
        arrayOf(AiffExtractor(), *defaults.createExtractors(uri, responseHeaders))
}
