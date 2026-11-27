# Unpaused

An ad-free music player for Android, with a web companion, built for one job first: **keep the
music playing** when you switch apps, lock the phone, open a payment app or start a game, as far as
Android allows.

**Android APK (latest build):** https://github.com/ratnesh-lord/music-player/releases/download/latest/unpaused.apk
· **Web app:** https://ratnesh-lord.github.io/music-player/ (after GitHub Pages is switched on, see
[DEPLOYMENT](docs/DEPLOYMENT.md#web-app-github-pages))

## Why your music stops, and what Unpaused does

Android lets one app at a time hold *audio focus*. When a game, a video or a payment app's sound
takes it, music apps are told to pause, and since Android 12 the system also fades out the app that
loses focus. No app can override that. Unpaused lets you choose how it takes part:

| Mode | What happens when another app plays sound |
|---|---|
| Standard | Pauses and waits for you (Android's guidance) |
| **Smart** (default) | Pauses, then resumes by itself once the other app has been quiet for 3 s (within up to 10 min) |
| Mix | Never takes focus, so other apps can't pause or fade it; both play together. Calls still pause. |

Switching apps and locking the screen don't take focus at all: Unpaused keeps playing through a
Media3 foreground service. Every pause is logged with its cause (Settings → Diagnostics), so you can
tell Android's behaviour from a bug. Details and sources: [research/01](docs/research/01-android-interruptions.md).

## Features

* **Your files**: FLAC, WAV, AIFF, MP3, AAC, Ogg Vorbis, Opus (ALAC where the phone can decode it),
  from Android's media index or folders you pick. Nothing is uploaded.
* **Internet radio** from the community Radio Browser directory.
* **Sound**: gapless, 10-band EQ with bass/treble, ReplayGain (track/album), optional normaliser,
  32-bit float output for hi-res files. Source quality and output path are shown separately, and
  nothing is called lossless, hi-res or bit-perfect unless it is.
* **Library**: albums, artists, genres, folders, search, playlists, favourites, history, lyrics
  (embedded and `.lrc`), sleep timer, queue editing, output device choice.
* **Web companion**: same library ideas in the browser (files stay in the browser), EQ, ReplayGain,
  radio, installable.
* **Sync (optional)**: JSON backup, or your own free Supabase project.

What it can't do: play Amazon Music, Spotify, YouTube Music, Apple Music, TIDAL, Qobuz or Deezer.
None offers an official way for an independent player to stream its catalogue
([research/02](docs/research/02-music-providers.md)).

## Repository

| Path | What |
|---|---|
| `android/` | Kotlin, Jetpack Compose, Media3, Room, DataStore. `core/` holds the pure, tested logic. |
| `web/` | Vite, React, TypeScript, Tailwind, Dexie, music-metadata |
| `backend/` | Optional Supabase schema with row-level security |
| `shared/` | Test vectors both apps must pass, sync format, design tokens |
| `docs/` | Research, architecture, setup, testing, deployment |

## Docs

[Setup](docs/SETUP.md) · [Testing (incl. the 12-scenario device protocol)](docs/TESTING.md) ·
[Deployment](docs/DEPLOYMENT.md) · [Architecture](docs/ARCHITECTURE.md) ·
[Interfaces](docs/API.md) · [Data](docs/DATABASE.md) · [Design system](docs/DESIGN-SYSTEM.md) ·
[Roadmap and limitations](docs/ROADMAP.md) · [Research](docs/research/README.md)

## Licence

GPL-3.0-or-later. Fonts: Geist, Geist Mono and Doto under the SIL Open Font License 1.1.
