# 03 — Lossless and high-resolution audio on Android (and Nothing Phone (2a))

Research date: 27 Nov 2026.
Legend: **[V]** verified source, **[V-src]** verified in source code, **[A]** assumption.

## 1. Format support matrix

| Format | Container(s) | Android platform decoder | Media3 1.11 | This app |
|---|---|---|---|---|
| FLAC | .flac, Ogg, MP4, MKV | Yes, since Android 3.1 [V] | FLAC extractor + platform MediaCodec (needs API 27+) [V] | ✅ Media3 |
| WAV (PCM 8/16/24/32-bit int, 32/64-bit float) | .wav | Platform docs list 8/16-bit only [V] | WAV extractor; 64-bit float added in 1.11 [V] | ✅ Media3 (PCM goes straight to the audio sink, so no decoder is needed) |
| AIFF / AIFF-C (PCM, `sowt`, `fl32`, `fl64`) | .aif/.aiff/.aifc | **Not listed** [V] | **Not listed** [V] | ✅ **Our own extractor** (`core/aiff` + `AiffExtractor`), outputting big-endian PCM, which Media3's processors accept [V-src `ToInt16PcmAudioProcessor`] |
| ALAC | .m4a (MP4), .caf, .mkv | **Not listed** [V] | Needs the FFmpeg extension, which you must build yourself, or a device decoder [V] | ⚠️ Plays if the phone ships an ALAC decoder; the app detects this at runtime and says so. A guaranteed path is on the roadmap (see §4) |
| MP3 | .mp3, MP4, MKV | Yes [V] | Yes; gapless via Xing/LAME headers [V] | ✅ |
| AAC (LC / HE / xHE) | .m4a/.mp4, .aac (ADTS) | Yes; xHE from Android 9 [V] | Yes; gapless via iTunes metadata / edit lists [V] | ✅ |
| Ogg Vorbis | .ogg | Yes [V] | Yes [V] | ✅ |
| Opus | .opus/.ogg, MP4, MKV | Yes, from Android 5 [V] | Yes; end-padding trim added in 1.12-alpha [V] | ✅ |

## 2. What "high resolution" can actually mean on a phone

1. **Decoding.** A 24-bit/96 kHz FLAC is decoded at full resolution.
2. **App → system.** By default Media3 converts PCM to 16-bit before it reaches
   `AudioTrack`. With `setEnableAudioFloatOutput(true)`, high-resolution PCM
   (24/32-bit) is sent as **32-bit float** instead. In that mode Media3 skips
   custom `AudioProcessor`s, because the float path only does trimming, channel
   mapping and float conversion. **[V-src]** `DefaultAudioSink`
3. **System mixer → hardware.** Android's mixer normally resamples everything to
   the output's native rate, often 48 kHz. Android's own format table warns
   that its 48→44.1 kHz downsampler has no low-pass filter **[V]**. Whether the
   Nothing Phone (2a)'s speaker, USB or Bluetooth paths run above 48 kHz is
   **not documented**. The app reports what the output device advertises
   (`AudioDeviceInfo.getSampleRates()` and `getEncodings()`), and it never
   assumes. **[A]**
4. **Bluetooth** re-encodes everything with a lossy Bluetooth codec (SBC, AAC,
   LDAC or aptX), so a lossless source over Bluetooth is not lossless at the
   ear. **[A]** (general Bluetooth A2DP behaviour) Android does not expose the
   active A2DP codec to normal apps. You can see it in Developer options →
   Bluetooth audio codec. **[A]**
5. **USB DAC, bit-perfect.** Android 14 added *preferred mixer attributes*
   for USB output. The `BIT_PERFECT` behaviour is optional for vendors and needs
   the AIDL audio HAL plus a vendor output flag **[V]**
   ([AOSP](https://source.android.com/docs/core/audio/preferred-mixer-attr)).
   The app queries `AudioManager.getSupportedMixerAttributes(usbDevice)` and
   shows whether bit-perfect is offered. Setting it is experimental (v1.1).

## 3. Nothing Phone (2a): what is and isn't known

| Item | Finding | Confidence |
|---|---|---|
| SoC | MediaTek Dimensity 7200 Pro | [V] [GSMArena](https://m.gsmarena.com/_phone_(2a)-12760.php) |
| Headphone jack | None; USB-C 2.0 with OTG | [V] GSMArena |
| Speakers | Stereo | [V] GSMArena / Beebom |
| Bluetooth | 5.3, A2DP, LE | [V] GSMArena |
| Bluetooth codecs | GSMArena lists none. One retailer spec sheet (hardwareluxx) lists aptX Adaptive. **Not verified** | [A] |
| Hi-Res Audio certification / LDAC | No evidence found | [A] |
| Bit-perfect USB | No evidence found. The app checks at runtime | [A] |
| Android version | Shipped Android 14 (Nothing OS 2.5); 3 major upgrades; Nothing OS 5.0 (Android 17) scheduled for mid-Nov 2026 | [V] GSMArena, 91mobiles |

**So the app makes no claim about bit-perfect or hi-res output on this phone.**
It shows two separate panels:

* **Source:** codec, container, sample rate, bit depth (for PCM, FLAC and
  ALAC), channels, bitrate, and a **Lossless / Lossy** label. The label comes
  from the codec only. MP3, AAC, Vorbis and Opus are always labelled *lossy*,
  even at 320 kbps.
* **Output:** route (speaker, USB, Bluetooth A2DP/LE, wired), the sample rates
  and encodings the device reports, the PCM format our `AudioTrack` actually
  opened with (Media3 `onAudioTrackInitialized`), and whether bit-perfect is
  available.

## 4. DSP and the "pure" path

| Feature | Implementation | Works with float hi-res path? |
|---|---|---|
| Equalizer, bass, treble | Platform **DynamicsProcessing** (API 28+) attached to the player's audio session: 10-band pre-EQ with bass and treble mapped to the low and high bands, plus a limiter. Falls back to `android.media.audiofx.Equalizer` + `BassBoost`. | Yes. Session effects run in the system mixer, after `AudioTrack`. [A — standard AudioEffect behaviour] |
| ReplayGain (track/album, pre-amp, clipping guard) | Tags read from Media3 metadata: Vorbis comments, ID3 `TXXX`, MP4 freeform atoms and Opus `R128_*_GAIN`. Attenuation goes through player volume; boost goes through DynamicsProcessing input gain with the limiter. | Yes |
| Normalisation (no tags) | DynamicsProcessing limiter plus a gentle compressor preset | Yes |
| "Pure" mode | All effects off, float output on, volume 1.0 | — |
| Gapless | Media3 trims encoder delay/padding; consecutive same-format tracks share one `AudioTrack` | A format change between tracks (for example 16/44.1 → 24/96) re-opens the track and can leave a small gap. [A] |

Media3's release notes contain no ReplayGain support, so the app implements it
itself.

ALAC options, for the record:

* **(a)** Media3 FFmpeg extension, which you must build with the NDK.
* **(b)** Jellyfin's prebuilt `media3-ffmpeg-decoder`. It is GPL-3.0 and its
  newest build matches Media3 **1.9.0**, while we use 1.11.1. Extension and core
  versions must match [V], so this is unusable now.
* **(c)** A Kotlin port of Apple's Apache-2.0 ALAC decoder.

The roadmap picks **(c)**.

## 5. Sources

* Android supported media formats: https://developer.android.com/media/platform/supported-formats
* Media3 ExoPlayer supported formats: https://developer.android.com/media/media3/exoplayer/supported-formats
* Media3 release notes: https://developer.android.com/jetpack/androidx/releases/media3
* Media3 source (`DefaultAudioSink`, `ToInt16PcmAudioProcessor`, `Util.isEncodingHighResolutionPcm`): https://github.com/androidx/media
* AOSP preferred mixer attributes (USB, bit-perfect): https://source.android.com/docs/core/audio/preferred-mixer-attr
* Jellyfin Media3 FFmpeg decoder versions: https://repo1.maven.org/maven2/org/jellyfin/media3/media3-ffmpeg-decoder/maven-metadata.xml
* Nothing Phone (2a) specs: https://m.gsmarena.com/_phone_(2a)-12760.php
* Nothing OS 5.0 rollout: https://www.91mobiles.com/hub/nothing-os-5-update-roundup-release-timeline-eligible-phones-features/
