# Roadmap, v1 scope and limitations

## v1: what ships first (realistic)

### Android
* **Playback reliability (primary goal)**
  * Media3 `MediaLibraryService` foreground service, media notification, lock-screen controls
  * Focus modes: **Standard / Smart (default) / Mix**, with one-tap Mix toggle in the player
  * Smart auto-resume after permanent focus loss (window up to 10 min); duck-instead-of-pause option for short interruptions
  * Calls handled in every mode (focus and audio-mode listener)
  * Headset, wired and Bluetooth buttons; pause on unplug; optional resume on reconnect (while the service is alive)
  * Process-death recovery (queue + position), Bluetooth and System UI playback resumption, reboot resume card
  * Diagnostics log of every interruption, labelled *expected Android behaviour* or *unexpected*
  * Battery-optimisation status check with a shortcut to the system setting (no extra permission)
* **Library:** MediaStore scan plus folders added with the Storage Access Framework, embedded metadata, artwork, albums, artists, genres, folders, search (FTS), playlists, favourites, history, recently added
* **Player UI:** large artwork, dot-matrix seek bar, shuffle, repeat, queue (reorder, remove, play next), sleep timer (time or end of track), favourite, lyrics (embedded + `.lrc`)
* **Audio:** gapless (Media3), hi-res float output toggle, 10-band EQ + bass/treble (DynamicsProcessing, falling back to Equalizer), ReplayGain track/album with pre-amp and clipping guard, optional normalisation, separate *Source quality* and *Output* panels, output-device selection (`setPreferredAudioDevice`)
* **Formats:** FLAC, WAV, AIFF (own extractor), MP3, AAC, Ogg Vorbis, Opus; ALAC where the phone has a decoder (detected and shown)
* **Sources:** internet radio through Radio Browser (search, favourites, now-playing title from ICY metadata)
* **Home:** recently played, recently added, favourites, top albums/artists, "rediscover" picks from the local library

### Web
* Import files or folders (drag-and-drop, file picker, folder picker where supported); metadata and artwork parsing; library in IndexedDB; optional copy into browser storage (OPFS)
* Albums, artists, playlists, favourites, history, search, queue, shuffle/repeat
* Web Audio EQ + ReplayGain, Media Session controls, AIFF via in-browser WAV conversion, internet radio
* Responsive desktop and mobile layout, installable PWA

### Shared
* JSON export/import of playlists, favourites, history and settings (works with no account)
* Optional Supabase sync (you bring your own free project; schema and RLS policies included)

## Milestones

| # | Milestone | Output | Status |
|---|---|---|---|
| M0 | Research + architecture | `docs/research/*`, `ARCHITECTURE.md` | done |
| M1 | Playback core + CI | Service, focus modes, diagnostics, recovery, APK from CI | in progress |
| M2 | Library + data | Scanners, Room, metadata enrichment, AIFF | planned |
| M3 | UI + design system | All Android screens | planned |
| M4 | Audio quality + DSP | EQ, ReplayGain, quality/output panels | planned |
| M5 | Web companion | Static SPA + tests + Pages deploy | planned |
| M6 | Sync | JSON bundles + Supabase | planned |

## After v1

* v1.1: Internet Archive / Live Music Archive and Jamendo (your own client ID); Audius
* v1.1: **ALAC everywhere**, by porting Apple's Apache-2.0 ALAC decoder to Kotlin as a Media3 decoder
* v1.1: experimental **bit-perfect USB** (Android 14+ mixer attributes) where the device offers it
* v1.2: Android Auto browsing (the library service already exposes a tree), home-screen widget, crossfade, tag editor, smart playlists
* Later: self-hosted sync recipe (PocketBase or self-hosted Supabase)

## Legal and technical limitations (read this)

1. **Amazon Music, Spotify, YouTube Music, Apple Music, TIDAL, Qobuz and Deezer
   cannot be played inside this app.** There is no official path for an
   independent player without partner approval, paid plans or DRM SDKs. See
   [research/02](research/02-music-providers.md).
2. **No app can override another app's legitimate audio focus.** Mix mode
   avoids being paused by never taking focus. Smart mode resumes afterwards. A
   game or video that you start will still pause music in Standard and Smart
   modes. That is Android working as designed.
3. **Smart auto-resume only works within 10 minutes** of an interruption,
   because Media3 keeps the foreground service that long. Android 15+ and 17
   forbid background focus requests and playback without one.
4. **In Mix mode, other apps can't pause *or duck* us.** Navigation prompts and
   videos play over the music unless you pause.
5. **Calls always pause music.** Android mutes media during calls anyway.
6. **Bluetooth is always lossy** (the Bluetooth codec re-encodes). Lossless and
   hi-res matter for wired USB DACs and the speaker path only.
7. **Hi-res output is limited by the phone's mixer.** Bit-perfect is
   unconfirmed for Nothing Phone (2a). The app reports what the phone
   advertises and claims nothing more.
8. **ALAC** depends on a device decoder in v1, and on the web only Safari plays
   it.
9. **Web app limits:** background playback is weaker than the native service,
   especially for installed web apps on iOS. Gapless is best-effort. Radio
   bypasses the EQ when stations send no CORS headers. Files are only
   re-readable if the browser keeps permission or you chose "keep a copy".
10. **Radio streams** may contain the stations' own adverts. This app inserts
    none.
11. **Jamendo** content is free for non-commercial use only, and its API terms
    make credentials personal, so you enter your own client ID.
12. **Supabase free projects pause** after a week without activity. The app
    keeps working offline and syncs after you restore the project from the
    dashboard.
13. **Sideloading:** the APK is installed outside the Play Store. Android will
    ask you to allow "Install unknown apps" for your browser or file manager.
