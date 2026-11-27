# Testing

## Automated (runs in CI on every push)

| Suite | What it proves | Where | Count |
|---|---|---|---|
| Core policy and logic (JVM) | Focus policy for all three modes, ReplayGain, quality labels, AIFF headers, LRC, track keys, sync merge, sleep timer, diagnostics verdicts | `android/core/src/test` | 46 tests |
| Shared vectors | Kotlin and TypeScript produce identical results for the same inputs | `shared/test-vectors/*.json`, run by both suites above and below | 5 files |
| Android glue (Robolectric) | Real `AudioManager` focus requests and callbacks, main-looper timers and ExoPlayer play/pause: Smart resumes once the other app is quiet, Standard waits, transient loss and gain, ducking, Mix never holds focus, a manual pause sticks | `android/app/src/test/.../AudioFocusControllerTest.kt` | 6 tests |
| Android device (Android 15 emulator in CI) | The real playback service plays a file; a second audio-focus client ("another app") takes focus: Smart pauses then resumes after it lets go, Standard stays paused, Mix keeps playing. A UI run saves screenshots of onboarding, home and the player (printed in the job log) | `android/app/src/androidTest/.../DeviceTests.kt`, `android/scripts/run-instrumented.sh` | 4 tests |
| Android lint | API-level guards, Compose rules, manifest | `./gradlew :app:lintDebug` | — |
| Web unit | Same vectors in TypeScript, AIFF→WAV conversion | `web/tests` | 46 tests |
| Web end-to-end (Playwright, Chromium) | Import files, honest quality panel, WAV playback, AIFF converted and played, library survives a reload | `web/e2e` | 2 tests |

Run locally:

```bash
cd android && ./gradlew :core:test :app:testDebugUnitTest :app:lintDebug
cd web && npm ci && npm test && npm run build && npx playwright install chromium && npx playwright test
```

Device tests need an emulator or phone: `cd android && ./scripts/run-instrumented.sh`.

What the automated tests can't show: how a real game, payment app or Nothing OS behaves (the
emulator's "other app" is a test focus client). That is what the device protocol below is for.

## Device protocol (Nothing Phone (2a) or any Android phone)

Setup once:

* Install the APK, allow music access, play an album.
* Turn on USB debugging if you want logs: `adb logcat -s Unpaused UnpausedLibrary` shows the same
  events as Settings → Diagnostics.
* Two system views help separate Android's behaviour from ours:
  `adb shell dumpsys audio` (look for "Audio Focus stack entries" and the player list) and
  `adb shell dumpsys media_session`.

Run each scenario in the mode named, then open **Settings → Diagnostics**. Every pause is
labelled **ANDROID** (another app or the system asked for it), **YOU**, **APP** (an Unpaused
setting did it, e.g. sleep timer) or **UNEXPECTED**. The rule:

* a pause right after a focus loss, call or unplug event is **expected Android behaviour**;
* a pause with nothing next to it ("Playback paused without a recorded cause") is **an Unpaused bug**;
* "Unpaused was stopped while playing" means the process was killed; check the battery setting
  (Settings → Interruptions → Battery) before calling it a bug.

| # | Scenario | Steps | Expected (Standard / Smart / Mix) | If it differs |
|---|---|---|---|---|
| 1 | Switch apps | Play, go Home, open Settings, Camera, Chrome (no video) for 10 min | Keeps playing in all modes | Any pause is a bug unless the log shows a focus loss from one of those apps |
| 2 | Lock the screen | Play, lock, wait 30 min (Doze) | Keeps playing in all modes | Stopping is a bug, or a Restricted battery setting; the log tells which |
| 3 | Launch a game with sound | Play, start a game, play 5 min, close it | Standard: pauses, stays paused · Smart: pauses, resumes about 3 s after the game is closed (within the window) · Mix: keeps playing under the game | Smart not resuming while the game is open is expected when the game keeps an audio stream open; use Mix for games |
| 4 | Google Pay / PhonePe payment | Play, open the app, complete or cancel a payment | Standard: pauses if the app takes permanent focus, resumes if transient · Smart: resumes about 3 s after the sound · Mix: keeps playing | The log shows which focus type the app requested |
| 5 | Short video (Reels, Shorts, YouTube) | Play, watch a video 30 s, leave the app | Standard: pauses · Smart: resumes after you leave and it's quiet for 3 s · Mix: both play at once | Same as 3 |
| 6 | Notification or navigation prompt | Play, receive a notification with sound / start Maps navigation | Standard and Smart: Android lowers the volume briefly (or pauses for voice prompts that ask for it), then restores · Mix: not lowered | A long pause after a short sound is a bug |
| 7 | Phone call | Play, receive a call, answer, hang up | All modes: pause when it rings, resume after the call (if "Resume after calls" is on) | Not resuming within a few seconds of hang-up is a bug |
| 8 | Internet call / voice chat | Play, start a WhatsApp call or game voice chat | Pauses if "Pause for voice chat" is on, resumes after | — |
| 9 | Unplug wired headphones / disconnect Bluetooth | Play, unplug or switch off the headphones | All modes: pause (Android's "becoming noisy" signal); no surprise resume on the speaker | Playing through the speaker after unplugging is a bug |
| 10 | Headset buttons | Single press, double press (next), from the lock screen; then swipe Unpaused from Recents while paused, wait 15 min, press play on Bluetooth headphones | Play/pause/next work; the last step restarts the saved queue (Media3 playback resumption) | — |
| 11 | Swipe from Recents | While playing, swipe Unpaused away | Keeps playing; notification stays | Stopping is a bug |
| 12 | Process kill and reboot | Pause, wait past the keep-alive time, `adb shell am kill app.unpaused`; separately, reboot while playing | Kill: reopening restores the queue and position. Reboot: Android 11+ shows the resume card in quick settings; the log shows "The phone restarted while music was playing" (INFO) | A kill while playing with "Unrestricted" battery use is worth reporting with the log |

Notes:

* `am kill` only kills apps that are safe to kill (not a playing foreground service). Force stop
  (Settings → App info) is a user action: Android stops the app and won't restart it until you do.
* Smart mode stops waiting after the window you set (max 10 min), because after that Android no
  longer lets a paused app take audio focus from the background (Android 15+, and Android 17
  background audio rules). The log says "gave up" when that happens.
* Now Playing → AUDIO shows the source file, what was handed to Android (encoding, rate), the output
  device's reported rates and Android's mixer rate. Unpaused can't read which Bluetooth codec is in
  use (Developer options shows it). Include that panel with any audio-quality question.
