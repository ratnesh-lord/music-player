# Setup

## Install on your phone (Nothing Phone (2a) or any Android 8+ phone)

1. On the phone, open
   **https://github.com/ratnesh-lord/music-player/releases/download/latest/unpaused.apk**
   (always the newest build from `main`).
2. Open the downloaded file. Android asks you to allow "Install unknown apps" for your browser or
   file manager the first time; allow it, then install.
3. Open Unpaused and choose **Allow access to music** (Android 13+ asks for "Music and audio"), or
   **Pick a music folder** to use only one folder.
4. Settings → Interruptions → **Battery**: if it says *Optimised*, tap it and set Unpaused to
   *Unrestricted*. Music keeps playing either way; this mainly lets Smart mode wait while paused.

Updating: install the newer APK over the old one. If Android says the app "conflicts with an
existing package", the signing key changed (see [DEPLOYMENT.md](DEPLOYMENT.md#signing)); export a
backup (Settings → Sync & backup → Export), uninstall, install, import.

### Choosing how interruptions are handled

Home and the player have three chips. Pick per situation:

| Mode | Use it when | What happens when a game, video or payment app plays sound |
|---|---|---|
| Standard | You want Android's normal behaviour | Pauses and waits for you |
| **Smart** (default) | Everyday use | Pauses, then resumes by itself once the other app has been quiet for 3 s (within the window you set, max 10 min) |
| Mix | Gaming, or apps that keep stealing audio | Keeps playing under the other app; calls still pause |

The notification also has a Mix button. Why these limits exist: [research/01](research/01-android-interruptions.md).

### Your music

* Android's music index (MediaStore) is used when music access is allowed.
* Folders added in Settings → Library folders are read through Android's file picker; that is also
  how `.lrc` lyrics and `cover.jpg` files next to songs are found.
* Files never leave the phone. Formats: FLAC, WAV, AIFF, MP3, AAC (M4A), Ogg Vorbis, Opus; ALAC
  plays if the phone has a decoder for it.

## Use the web app

* Hosted: **https://ratnesh-lord.github.io/music-player/** once GitHub Pages is switched on
  ([DEPLOYMENT.md](DEPLOYMENT.md#web-app-github-pages)).
* Locally: `cd web && npm ci && npm run dev`, then open the printed address.

Add files or a folder; tick "Keep a copy in this browser" to keep them playable after a reload
(stored in the browser's private storage on your computer, not uploaded). Chrome and Edge can also
remember a folder without copying it (untick the box, then Add folder).

## Optional: sync with Supabase

Only needed if you want playlists, favourites and history on both the phone and the web app. The
JSON backup (Settings → Sync & backup) moves the same data with no account.

1. Create a free project at supabase.com.
2. SQL Editor → paste `backend/supabase/migrations/20261127000000_init.sql` → Run.
3. In the dashboard's Authentication settings, keep the Email provider enabled. If "Confirm email"
   is on (the default), you confirm the address once from your inbox before signing in; for a
   personal project you can turn it off.
4. From the project's API settings, copy the **Project URL** and the **publishable key**
   (`sb_publishable_…`). Never use the secret key in an app. (Dashboard menus change; the
   Supabase docs search finds both quickly.)
5. In both apps: Settings → Sync & backup → enable sync, paste URL and key, **Create account** on
   the first device, **Sign in** on the others, then **Sync now**.

Free projects pause after about a week without activity; restore them from the dashboard. The apps
keep working offline meanwhile.

## Build from source

Android (JDK 21, Android SDK with platform 37.1):

```bash
cd android
./gradlew :app:assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew :core:test :app:testDebugUnitTest
```

Web (Node 22+): `cd web && npm ci && npm run build`.
