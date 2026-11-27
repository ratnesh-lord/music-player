package app.unpaused.playback

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.os.Build

/**
 * What the current output device *reports*, shown separately from the source quality. Nothing here
 * is assumed: if Android doesn't report a capability, the UI says "not reported".
 */
data class OutputInfo(
    val deviceId: Int,
    val type: String,
    val productName: String?,
    val isBluetooth: Boolean,
    val sampleRates: List<Int>,
    val encodings: List<String>,
    val channelCounts: List<Int>,
    /** null = Android version can't tell (before Android 14); empty = device offers no bit-perfect mode. */
    val bitPerfectFormats: List<String>?,
)

data class OutputDevice(val id: Int, val label: String, val info: AudioDeviceInfo)

object OutputInfoReader {

    private val mediaAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    /** The device media is routed to right now (best effort below Android 13). */
    fun current(am: AudioManager, preferred: AudioDeviceInfo?): OutputInfo? {
        val device = preferred ?: routedDevice(am) ?: return null
        return describe(am, device)
    }

    fun describe(am: AudioManager, d: AudioDeviceInfo): OutputInfo = OutputInfo(
        deviceId = d.id,
        type = typeName(d.type),
        productName = d.productName?.toString()?.takeIf { it.isNotBlank() && it != Build.MODEL },
        isBluetooth = d.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || isBle(d.type),
        sampleRates = d.sampleRates.toList().distinct().sorted(),
        encodings = d.encodings.toList().distinct().map(::encodingName),
        channelCounts = d.channelCounts.toList().distinct().sorted(),
        bitPerfectFormats = bitPerfect(am, d),
    )

    /** Output devices the user can choose between (ExoPlayer.setPreferredAudioDevice). */
    fun selectable(am: AudioManager): List<OutputDevice> =
        am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter { it.isSink && it.type in SELECTABLE }
            .distinctBy { it.type to it.productName?.toString() }
            .map { OutputDevice(it.id, label(it), it) }

    fun label(d: AudioDeviceInfo): String {
        val name = d.productName?.toString()?.takeIf { it.isNotBlank() && it != Build.MODEL }
        val type = typeName(d.type)
        return if (name != null && d.type != AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) "$type · $name" else type
    }

    private fun routedDevice(am: AudioManager): AudioDeviceInfo? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            am.getAudioDevicesForAttributes(mediaAttributes).firstOrNull()?.let { attr ->
                return am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.type == attr.type && it.address == attr.address }
                    ?: am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.type == attr.type }
            }
        }
        val outputs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        return PRIORITY.firstNotNullOfOrNull { t -> outputs.firstOrNull { it.type == t } }
    }

    private fun bitPerfect(am: AudioManager, d: AudioDeviceInfo): List<String>? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return null
        val attrs = runCatching { am.getSupportedMixerAttributes(d) }.getOrNull() ?: return emptyList()
        return attrs.filter { it.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT }
            .map { "${encodingName(it.format.encoding)} @ ${it.format.sampleRate} Hz" }
            .distinct()
    }

    private fun isBle(type: Int) = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        (type == AudioDeviceInfo.TYPE_BLE_HEADSET || type == AudioDeviceInfo.TYPE_BLE_SPEAKER)

    fun typeName(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Phone speaker"
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "Earpiece"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headset"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired headphones"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth (call)"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB headset"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB audio (DAC)"
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB accessory"
        AudioDeviceInfo.TYPE_HDMI -> "HDMI"
        AudioDeviceInfo.TYPE_DOCK -> "Dock"
        AudioDeviceInfo.TYPE_HEARING_AID -> "Hearing aid"
        AudioDeviceInfo.TYPE_BLE_HEADSET -> "Bluetooth LE headset"
        AudioDeviceInfo.TYPE_BLE_SPEAKER -> "Bluetooth LE speaker"
        else -> "Output type $type"
    }

    fun encodingName(encoding: Int): String = when (encoding) {
        AudioFormat.ENCODING_PCM_8BIT -> "PCM 8-bit"
        AudioFormat.ENCODING_PCM_16BIT -> "PCM 16-bit"
        AudioFormat.ENCODING_PCM_24BIT_PACKED -> "PCM 24-bit"
        AudioFormat.ENCODING_PCM_32BIT -> "PCM 32-bit"
        AudioFormat.ENCODING_PCM_FLOAT -> "PCM float"
        AudioFormat.ENCODING_AC3 -> "AC-3"
        AudioFormat.ENCODING_E_AC3 -> "E-AC-3"
        AudioFormat.ENCODING_DTS -> "DTS"
        AudioFormat.ENCODING_AAC_LC -> "AAC"
        AudioFormat.ENCODING_MP3 -> "MP3"
        AudioFormat.ENCODING_OPUS -> "Opus"
        else -> "encoding $encoding"
    }

    private val PRIORITY = listOf(
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
    )

    private val SELECTABLE = setOf(
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER,
    )
}
