# Roadmap, v1 scope and limitations

## v1: what is built

Status key: **built** = implemented, compiled and unit-tested in CI; **device check** = still to be
confirmed on a real phone with the protocol in [TESTING.md](TESTING.md).

### Android
* **Playback reliability (primary goal)**: built, device check pending
  * Media3 `MediaLibraryService` foreground service, media notification, lock-screen controls
  * Focus modes **Standard / Smart (default) / Mix**, with Mix toggles on Home, in the player and in the notification
  * Smart auto-resume after a permanent loss (window up to 10 min); duck-instead-of-pause option for short interruptions
  * Calls handled in every mode (focus plus audio-mode changes; polling below Android 12)
  * Headset, wired and Bluetooth buttons; pause on unplug; optional resume on reconnect
  * Process-death recovery (queue + position), Bluetooth and System UI playback resumption
  * Diagnostics log of every interruption, labelled ANDROID / YOU / APP / UNEXPECTED, shareable
  * Battery-optimisation status with a shortcut to the system setting; a banner after Android kills playback
* **Library**: MediaStore scan plus folders added through the system picker, incremental rescans, tags and technical details read with Media3's own extractors, album artwork cache, albums, artists, genres, folders, FTS search, playlists, favourites, history, recently added and played
* **Player UI**: artwork, dot-matrix seek bar, shuffle, repeat, queue (move, remove, play next), sleep timer (minutes or end of track, with fade), favourite, lyrics (embedded + `.lrc`, synced highlighting), output device picker
* **Audio**: Media3 gapless, 32-bit float output for >16-bit files (toggle), 10-band EQ + bass/treble (DynamicsProcessing, Equalizer fallback, or hand-off to an external EQ app), ReplayGain track/album with pre-amp and clipping guard, optional loudness normaliser, skip silence, separate *Source* / *Output* / *Processing* panels
* **Formats**: FLAC, WAV, AIFF/AIFF-C (own extractor), MP3, AAC, Ogg Vorbis, Opus; ALAC if the phone's decoder handles it (otherwise the error is shown)
* **Sources**: internet radio through Radio Browser (popular, search by name/tag/country, favourites, recent, stream title from ICY metadata)
* **Home**: interruption-mode card, recently added albums, recently played, shuffle all

### Web
* Built and tested in CI (unit + Playwright), hosting needs Pages switched on
* Add files, folders (folder handle in Chrome/Edge) or drag-and-drop; tags, artwork and lyrics parsed in the browser; library in IndexedDB; optional copies in browser storage (OPFS)
* Songs, albums, playlists, favourites, history, search, queue, shuffle/repeat
* Web Audio EQ + bass/treble + ReplayGain + limiter, near-gapless two-deck engine, Media Session controls, AIFF via in-browser WAV conversion, internet radio, honest quality panel
* Installable (manifest + service worker for the app shell)

### Shared
* JSON backup of playlists, favourites and history, importable in either app (no account)
* Optional Supabase sync with your own free project (schema and RLS policies included)

## Milestones

| # | Milestone | Output | Status |
|---|---|---|---|
| M0 | Research + architecture | `docs/research/*`, `ARCHITECTURE.md` | done |
| M1 | Playback core + CI | Service, focus modes, diagnostics, recovery, APK from CI | built; device check pending |
| M2 | Library + data | Scanners, Room, metadata, artwork, AIFF | built; device check pending |
| M3 | UI + design system | All Android screens | built; not yet seen on a device |
| M4 | Audio quality + DSP | EQ, ReplayGain, quality/output panels | built; device check pending |
| M5 | Web companion | Static SPA + tests + Pages deploy | built; Pages needs enabling |
| M6 | Sync | JSON backup + Supabase | built; not yet run against a live Supabase project |

## After v1

* v1.1: Internet Archive / Live Music Archive and Jamendo (your own client ID); Audius
* v1.1: **ALAC everywhere**, by porting Apple's Apache-2.0 ALAC decoder to Kotlin as a Media3 decoder
* v1.1: experimental **bit-perfect USB** (Android 14+ mixer attributes) where the device offers it
* v1.1: in Mix mode, lower our own volume while navigation or assistant prompts play
* v1.1: Home sections for favourites, top albums/artists and "rediscover"; web artists view; settings in the backup file
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
   especially for installed web apps on iOS. Gapless is best-effort. Radio in
   the browser bypasses EQ and ReplayGain (most stations send no CORS headers). Files are only
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
14. **Signing:** until release-key secrets are added, CI signs with a cached
    debug key. If GitHub evicts that cache (7 days unused), the next APK can't
    be installed over the old one ([DEPLOYMENT.md](DEPLOYMENT.md#signing)).
15. **Hi-res output toggle** applies the next time the player starts (the audio
    pipeline is built once per playback session).
16. **Lyrics** come only from your files: embedded tags and `.lrc` files in
    folders you added through the picker (Android's media index doesn't expose
    `.lrc` files to apps).
