# 01 — Why Android music pauses, and what an app can legally do about it

Research date: 27 Nov 2026. Target device: Nothing Phone (2a).

Legend: **[V]** verified against the linked source. **[V-src]** verified by reading
AOSP or Media3 source code. **[A]** assumption or inference that still needs
on-device confirmation.

## 1. Summary

The pauses you see in Amazon Music when you open a game, Google Pay, PhonePe or
another app are almost certainly Android's **audio focus** system doing its job.
They are not a bug in Android. Another app asks for audio focus, Android tells
the music app it has lost focus, and a well-behaved player pauses. After a
*permanent* loss, Android's own guidance says the player must not resume by
itself. So the music stays paused until you press play again. **[V]**
([Android: Manage audio focus](https://developer.android.com/media/optimize/audio-focus))

An app cannot "win" audio focus against another app that legitimately takes it.
Since Android 12, if a focus-holding media app keeps playing after losing focus,
the system fades it out. **[V]** What an app *can* do:

1. **Not hold focus at all** (mix mode). Then other apps' focus requests never
   pause or fade it. **[V-src]**
2. **Resume intelligently** after the other app has gone quiet, instead of
   staying paused for ever.
3. **Keep its foreground service alive**, so Android does not kill it while it
   is paused for a moment. Media3 already does this for up to 10 minutes.
   **[V-src]**
4. **Recover its queue and position** when the process is killed.
5. **Record every interruption**, so you can see which ones were Android doing
   its job and which ones were app bugs.

The app built in this repo does all five, with the behaviour selectable in
Settings → Playback → *When other apps play audio*.

## 2. How audio focus works (the facts that matter)

| # | Fact | Source |
|---|------|--------|
| F1 | Apps request focus with a gain type. A permanent request (`AUDIOFOCUS_GAIN`) sends `AUDIOFOCUS_LOSS` to the current holder. A transient request sends `AUDIOFOCUS_LOSS_TRANSIENT`, and a duckable one sends `..._CAN_DUCK`. | [V] [docs](https://developer.android.com/media/optimize/audio-focus) |
| F2 | Guidance: after a permanent loss, pause and wait for an explicit user action. After a transient loss, pause or duck, then resume on `AUDIOFOCUS_GAIN`. | [V] docs |
| F3 | Android 8+: for `..._MAY_DUCK` requests the system ducks the other app automatically, unless that app set `setWillPauseWhenDucked(true)` or plays speech. | [V] docs |
| F4 | Android 12+: when a second app requests `AUDIOFOCUS_GAIN`, the system fades out a first app that holds `AUDIOFOCUS_GAIN` with `USAGE_MEDIA` or `USAGE_GAME`. Loss is dispatched after the fade. | [V] docs; [V-src] `FocusRequester.frameworkHandleFocusLoss` |
| F5 | The fade only targets the players of the **focus loser**, which is an app that held focus. An app that never requested focus is not a `FocusRequester`, so it is never faded on another app's request. | [V-src] `PlaybackActivityMonitor.fadeOutPlayers` (AOSP frameworks/base, Mar 2025 snapshot) |
| F6 | The docs say faded players stay muted until the app requests focus again. In the AOSP source, the system "forgets" the faded app about 2 s after dispatching the loss and fades it back in. The constant is `DEFAULT_DELAY_FADE_IN_OFFENDERS_MS = 2000`, commented as covering apps that did not respect audio focus. An app that ignores a loss is therefore silent for about 4 s and then audible again. **We do not rely on this.** It is undocumented, OEMs can change it through `FadeManagerConfiguration`, and it is exactly the misbehaviour Android is trying to stop. | [V-src] `FadeConfigurations`, `MediaFocusControl.postForgetUidLater` |
| F7 | During ringing and calls, the system mutes existing `USAGE_MEDIA`/`USAGE_GAME` players. A muted app keeps "playing" silently, so it loses your place in the song unless it pauses itself. | [V] docs; [V-src] `mutePlayersForCall`, `USAGES_TO_MUTE_IN_RING_OR_CALL` |
| F8 | Android 15+ (target 35+): an app can request focus only if it is the top app or is running a foreground service. Otherwise it gets `AUDIOFOCUS_REQUEST_FAILED`. | [V] docs |
| F9 | Android 17 "background audio hardening" applies to **all apps**: playback, focus requests and volume changes from the background need a visible activity or a non-`shortService` foreground service. Apps targeting API 37 also need "while-in-use" capability. Failures are silent: playback is silenced and focus returns `REQUEST_FAILED`. Google says Media3 `MediaSessionService` apps are unlikely to be affected. It advises keeping the FGS through transient failures under 10 minutes, and ending it on a permanent end signal such as `AUDIOFOCUS_LOSS`. | [V] [Android 17 bg-audio](https://developer.android.com/about/versions/17/changes/bg-audio) |
| F10 | Media3 `MediaSessionService` keeps the service in the foreground for up to 10 min after a pause, stop or error (`DEFAULT_FOREGROUND_SERVICE_TIMEOUT_MS = 600_000`, which is also the maximum). After that the service leaves the foreground and can be killed. | [V-src] Media3 1.11 `MediaSessionService.setForegroundServiceTimeoutMs`; [V] [background playback guide](https://developer.android.com/media/media3/session/background-playback) |
| F11 | Media3's built-in focus handling pauses and abandons focus on a permanent loss, pauses and waits for the callback on a transient loss, and ducks to 0.2 volume on a duckable loss (non-speech). | [V-src] `androidx.media3.common.audio.AudioFocusManager` |
| F12 | Media3 supports **playback resumption**. A `MediaButtonReceiver` plus `onPlaybackResumption(session, controller, isForPlayback)` let Bluetooth play buttons and System UI restart playback after the process died or the device rebooted. | [V] [Media3 docs](https://developer.android.com/media/media3/session/background-playback) |

## 3. What each of your scenarios most likely is

| Scenario | Most likely cause | Expected Android behaviour or app bug? | What this app does |
|---|---|---|---|
| Launch a game | Game requests permanent focus. Unity has a *Mute Other Audio Sources* player setting that stops background audio **[V]** ([Unity docs](https://docs.unity3d.com/ScriptReference/PlayerSettings-muteOtherAudioSources.html)). Other engines do similar things. | Expected. A player that follows the rules pauses. | **Mix mode**: we never hold focus, so the game cannot pause or fade us (F5). **Smart mode**: we pause, then resume once no other app is playing media. |
| Open Google Pay / PhonePe | **[A]** I found no public documentation. The likely cause is a sound, such as the payment "ding", an animation or a video, requested with focus. Same mechanism as Spotify users report for social and news apps **[V]** ([Spotify community](https://community.spotify.com/t5/Android/Music-stops-playing-when-I-open-another-app/td-p/5041218)). | Expected *if* the app requests focus. The **diagnostics log** will show exactly which loss type arrived and when. | Smart mode resumes after the sound ends. Mix mode ignores it. |
| Switch between apps | Apps that autoplay audio or video (feeds, news, YouTube) request focus. | Expected. | Same as above. |
| Lock the screen | Locking does **not** cause a focus loss. A correct `mediaPlayback` foreground service plus a wake lock keeps playing **[V]**. If Amazon Music stops on lock, the cause is probably its streaming or network handling, a "Restricted" battery setting, or the process being killed **[A, unverified for Amazon]**. | If *our* app stops on lock, that is **a bug** (or a Restricted battery setting). | FGS with `foregroundServiceType="mediaPlayback"`, a partial wake lock while playing, a battery-optimisation status check, and process-death recovery. |
| Apps that use audio | Same as above. | Expected. | Same. |
| Phone call | The dialer takes transient focus, and the system also mutes media during calls (F7). | Expected: pause, then resume afterwards. | Pause on call and resume afterwards in **all** modes. In mix mode we watch `AudioManager` mode changes (API 31+, no permission needed). |
| Headphones unplug or Bluetooth disconnect | `ACTION_AUDIO_BECOMING_NOISY`. | Expected and desirable: pause. | Pause. Optional "resume when headphones reconnect" while the service is still alive. |
| Android kills the app or the phone reboots | Process death or reboot. | Expected occasionally under memory pressure; a reboot always ends playback. | Queue and position saved continuously. Media3 playback resumption from Bluetooth play, the System UI media card, or opening the app. |

Nothing OS: Nothing's support pages describe Game Mode as notification and
call management, accidental-touch prevention, visual enhancements and DND
**[V]** ([Nothing support](https://support.nothing.tech/hc/en-us/articles/43114754585873-How-do-I-enable-Game-Mode-on-the-Phone)).
I found no evidence that it stops background media **[A]**. DontKillMyApp has
no Nothing-specific page I could find **[A]**. Nothing OS 5.0 (Android 17) is
scheduled to reach Phone (2a) in mid-November 2026
([TechRepublic](https://www.techrepublic.com/article/news-nothing-os-5-eligible-phones-rollout/),
[91mobiles](https://www.91mobiles.com/hub/nothing-os-5-update-roundup-release-timeline-eligible-phones-features/)),
so this app is designed for Android 17's rules (F9).

## 4. Focus modes implemented

| Mode | Holds focus? | Permanent loss (game, video, payment sound) | Transient loss (assistant, voice note) | Duck request (navigation) | Call |
|---|---|---|---|---|---|
| **Standard** (Android guidance) | Yes | Pause, no auto-resume | Pause, resume on gain | System ducks us | Pause and resume |
| **Smart** (default) | Yes | Pause. Then, for up to N min (≤10, the FGS window), resume automatically once `AudioManager.isMusicActive()` has been false for 3 s. A manual pause cancels this. | Pause and resume. Option: duck instead of pause. | System ducks us | Pause and resume |
| **Mix** (never pause for other apps) | **No** | Not applicable. Nothing is dispatched to us, so we keep playing over the game. | Not applicable | Not ducked; we lower our own volume while navigation or assistant audio is active (optional) | Pause on `MODE_RINGTONE`, `IN_CALL` or `IN_COMMUNICATION`, resume on `MODE_NORMAL` |

Why Smart only resumes within 10 minutes: Media3 keeps the foreground service
that long after a pause (F10). Android 15+ refuses focus requests from apps
without one (F8), and Android 17 silences background playback without one (F9).
Resuming later would fail silently, so the app gives up and logs it instead.

Trade-offs to be honest about:

* **Mix mode** plays *over* videos and voice notes, because those apps cannot
  pause us. It is meant to be switched on for gaming sessions. There is a
  one-tap toggle in the player.
* **Smart mode** can resume music after you deliberately watched a video and
  then closed it. That is the point of the mode, but it is not what Android's
  guidance says, so it can be switched off.
* Games that keep their own audio engine running while silent (common with
  engines that keep an output stream open) look "active" to
  `isMusicActive()`. In that case Smart mode waits, and **Mix** is the mode
  that works for games. **[A]**

## 5. Diagnostics: telling Android behaviour from bugs

Every one of these is written to an on-device log (Settings → Diagnostics),
with a timestamp and the focus mode in use:

* focus requests and results (`GRANTED`, `DELAYED`, `FAILED`);
* every focus change, with the raw code;
* the playback configurations active at that moment. These are anonymised, so
  we see usage types such as `GAME` or `MEDIA` but not package names;
* noisy and headset events, audio-mode changes (call state), and output device
  changes;
* auto-resume attempts and their outcome;
* process start, with a flag when the previous session ended while playing
  (meaning the process was killed);
* errors from the player.

Rule of thumb, also written up in `docs/TESTING.md`:

* A pause logged immediately after a `LOSS` or `LOSS_TRANSIENT` is **expected
  Android behaviour**.
* A pause with no focus, noisy or call event next to it is **an app bug**.
* "Process restarted while playing" is a kill by the system or the OEM. Check
  the battery setting.

## 6. Sources

* Android — Manage audio focus: https://developer.android.com/media/optimize/audio-focus
* Android 17 — Background audio hardening: https://developer.android.com/about/versions/17/changes/bg-audio
* Media3 — Background playback with MediaSessionService: https://developer.android.com/media/media3/session/background-playback
* Media3 source (release branch, Sep 2026): https://github.com/androidx/media — `AudioFocusManager.java`, `MediaSessionService.java`, `MediaNotificationManager.java`
* AOSP audio service source (aosp-mirror, Mar 2025): https://github.com/aosp-mirror/platform_frameworks_base/tree/main/services/core/java/com/android/server/audio — `FocusRequester.java`, `PlaybackActivityMonitor.java`, `FadeConfigurations.java`, `MediaFocusControl.java`
* Unity — PlayerSettings.muteOtherAudioSources: https://docs.unity3d.com/ScriptReference/PlayerSettings-muteOtherAudioSources.html
* Spotify Community — "Music stops playing when I open another app": https://community.spotify.com/t5/Android/Music-stops-playing-when-I-open-another-app/td-p/5041218
* Nothing — Game Mode support article: https://support.nothing.tech/hc/en-us/articles/43114754585873-How-do-I-enable-Game-Mode-on-the-Phone
* Foreground service timeouts (types affected): https://developer.android.com/develop/background-work/services/fgs/timeout
