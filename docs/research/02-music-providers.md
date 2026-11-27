# 02 — Music sources: what can legally play inside a third-party player

Research date: 27 Nov 2026. Rules applied throughout: no scraping, no DRM
circumvention, no extracted credentials, no unofficial APIs.

Legend: **[V]** verified against the linked source, **[A]** assumption or
inference.

## 1. Bottom line

* **None of the big subscription services lets an independent app play its
  catalogue in that app's own player for free.** Amazon Music, Spotify,
  YouTube Music, Apple Music, Tidal, Qobuz and Deezer either have no public
  playback API, restrict it to approved partners, return previews only, or
  require both a paid subscription and DRM playback inside their own SDK.
* **Amazon Music specifically:** the Web API exists, but it is a closed beta
  for already-approved developers. Its playback endpoints return DRM-protected
  streams (`ENCRYPTED_OPUS_FLAC` over DASH with a licence server), and Amazon
  keeps control of the queue. **[V]**
  ([API overview](https://www.developer.amazon.com/docs/music/API_web_overview.html),
  [Playback V2](https://developer.amazon.com/docs/music/API_web_playback_v2.html))
  An official Amazon Music integration is therefore impossible for this
  project. I have not built a fake one.
* The legal, free and ad-free sources are: **your own files**, **internet
  radio** (Radio Browser directory), and **openly licensed catalogues**
  (Internet Archive / Live Music Archive, Jamendo, Audius).

## 2. Comparison table

"3rd-party playback" means full-length audio played by *our* player.

| Provider | Official API | Official Android SDK | 3rd-party playback | Auth | Background OK | Lossless | Free account legal access | DRM vs external player | Verdict |
|---|---|---|---|---|---|---|---|---|---|
| **Amazon Music** | Web API, **closed beta, approved developers only** [V] | None public [A] | Only for approved partners; DRM streams; Amazon controls the queue [V] | OAuth with an Amazon-approved security profile [V] | n/a | Catalogue supports HD/UHD; DRM [V] | Free tier exists, with ads in the queue [V] | Encrypted DASH with a licence server [V] | ❌ Not possible |
| **Apple Music** | Apple Music API (MusicKit) [V] | **MusicKit for Android**: auth and playback SDK [V] | Only through Apple's SDK; needs an Apple Music subscriber [V] | Developer token from the **Apple Developer Program** plus a Music User Token [V] | SDK supports it [V] | In Apple's apps; SDK quality not documented [A] | No: subscription required [V] | DRM inside Apple's SDK [A] | ❌ Needs paid developer membership + subscription |
| **Spotify** | Web API (Feb 2026: development mode needs a Premium owner, 5 test users, fewer endpoints; extended quota only for orgs with ~250k MAU) [V] | App Remote SDK controls the **Spotify app**; it does not hand audio to us [A] | ❌ No audio stream access for third-party players [A] | OAuth PKCE [V] | Plays in the Spotify app | n/a | n/a | n/a | ❌ Not possible |
| **YouTube Music** | No YouTube Music API. The YouTube Data API plus embedded player forbids background play and blocking ads [V] ([policies](https://developers.google.com/youtube/terms/developer-policies-guide)) | Android Player API deprecated in 2023 [V] | Only a visible embedded video player, with ads [V] | API key / OAuth | ❌ Prohibited [V] | No | Yes, with ads | n/a | ❌ Not viable (ads, no background) |
| **Deezer** | Public catalogue API [V] | Native SDK deprecated [V] | **30-second previews only** [V] ([FAQ](https://support.deezer.com/hc/en-gb/articles/360011538897-Deezer-FAQs-For-Developers)) | OAuth | n/a | n/a | Previews only | n/a | ❌ Previews only |
| **TIDAL** | TIDAL API (JSON:API) [V] | `com.tidal.sdk:player` (an ExoPlayer fork) [V] | Client-credentials flow (the only publicly supported one) gives **30-s previews**; full length needs device-login or auth-code flows [V] ([SDK docs](https://tidal-music.github.io/tidal-sdk-android/player/index.html)) | OAuth 2.0 | Probably, via ExoPlayer [A] | Yes in TIDAL's own apps [A] | No: paid tier needed for full tracks in third-party apps (Roon reports 30-s limits on free accounts) [V] | Inside TIDAL's player [A] | ❌ Previews only (today) |
| **Qobuz** | Partner-only API; app_id/secret by request to api@qobuz.com [V] | None public | Partners only | App ID + secret + user token | n/a | Up to 24/192 for partners [A] | No | n/a | ❌ Partner only; most "integrations" reuse extracted web-player credentials (prohibited) |
| **SoundCloud** | Public API; registering an app requires **Artist Pro** (paid) [V] ([docs](https://developers.soundcloud.com/docs/api)) | None | Yes for streamable tracks; AAC HLS 160/96 kbps [V] ([streams change](https://developers.soundcloud.com/blog/api-streaming-urls)) | OAuth: client credentials or authorization code [V] | Yes [A] | No (AAC) [V] | Listening is free; **API access needs a paid plan** [V] | HLS AAC, no DRM for public tracks [A] | ⚠️ Possible later, but needs paid Artist Pro; lossy |
| **Internet radio** (Radio Browser) | Free, open-source directory API, no key; send a descriptive User-Agent and report clicks [V] ([docs](https://api.radio-browser.info)) | n/a (plain HTTP) | Yes: stations publish their own stream URLs | None | Yes | Rare (some FLAC/Opus streams) | Yes | None | ✅ **v1** |
| **Internet Archive / Live Music Archive** | Keyless Advanced Search + Metadata APIs [V] | n/a | Yes; LMA concerts come from bands that allow taping and trading [V] | None | Yes | **Yes: FLAC/SHN originals**, plus MP3/Ogg derivatives [V] ([LMA help](https://help.archive.org/help/live-music-archive-etree-org/)) | Yes | None | ✅ v1.1 |
| **Jamendo** | API v3; `client_id` from devportal; free for **non-commercial** use [V] ([terms](https://devportal.jamendo.com/api_terms_of_use)) | n/a | Yes, under each track's CC licence; credit the artist and link the track page [V] | client_id | Yes | `audioformat=flac` offered [V] ([tracks](https://developer.jamendo.com/v3.0/tracks)) | Yes | None | ✅ v1.1: **you supply your own client_id**, because the terms say credentials are personal [V] |
| **Audius** | Free REST API; free plan 10 req/s, 500k req/month; API key safe in client code [V] ([docs](https://docs.audius.org/api)) | n/a | Yes; artists upload their own music [V] | API key (optional for basic use) [A] | Yes | Mostly MP3 streams; lossless only where an artist enables downloads [A] | Yes | None | ✅ v1.1 |
| **Free Music Archive** | API shut down (server load) [V] | — | — | — | — | — | — | — | ❌ |
| **Public domain / CC (Wikimedia Commons, ccMixter)** | MediaWiki API / ccMixter query API [A] | — | Yes | None | Yes | Ogg/FLAC on Commons [A] | Yes | None | ⚪ Later |
| **JioSaavn / Gaana / Wynk** (India) | JioSaavn's 2026 API policy says its API is private and restricted to JioSaavn and licensed affiliates [V] ([policy](https://www.jiosaavn.com/corporate/api-policy)). The "JioSaavn APIs" on GitHub are unofficial scrapers [V] | — | ❌ | — | — | — | — | — | ❌ Not allowed |
| **Your own files** | Android MediaStore + Storage Access Framework; browser File API | Platform | ✅ | Local permission | ✅ | ✅ FLAC/WAV/AIFF*, ALAC* | ✅ | DRM-free files only | ✅ **v1 core** |

\* AIFF needs our own extractor. ALAC needs a device decoder; see `03-lossless-audio.md`.

A legal way to get DRM-free files without a subscription is to **buy** them
(Bandcamp, Qobuz store, 7digital and similar) and add them to the library like
any other local music. This is optional, and the app does not integrate with
any store.

## 3. Strategy comparison

Scores are 1 (worst) to 5 (best), judged against your constraints.

| Criterion | A. Independent (local + free legal) | B. Universal via official SDKs | C. Hybrid (A + supported premium) |
|---|---|---|---|
| Cost | 5 — €0 | 1 — subscriptions + Apple dev fee | 3 — only if you pay |
| Audio quality | 5 — your FLAC files, bit-depth preserved | 2 — previews / DRM paths | 4 |
| UX | 4 — one fast app, no ads | 2 — logins, previews, gaps | 3 |
| Technical feasibility | 5 | 1 — Amazon/Qobuz closed, Spotify/YouTube no audio access | 3 |
| Legal compliance | 5 | 4 (if partner-approved) | 5 |
| Maintainability | 5 — no partner APIs to break | 1 — Spotify changed developer access twice in 2026 [V] | 3 |
| **Total** | **29** | **11** | **21** |

**Decision: A, built so it can grow into C.** The app is an independent player
for local files plus free legal streaming. A provider is a pluggable `Source`,
so a premium integration can be added later *if* one ever offers an official
path. Two things would change this decision: Amazon opening its Web API beyond
approved partners, or TIDAL publicly supporting full-length playback for
subscribers. Neither is true today.

## 4. Lyrics

* **Embedded lyrics** in your own files (ID3 `USLT`, Vorbis `LYRICS`, MP4
  `©lyr`) and **`.lrc` sidecar files** in folders you add: supported. They are
  your files, so they are legal to display.
* Online lyric databases: crowd-sourced databases with no publisher licence
  are excluded. Their licensing status is unclear, and the brief says "where
  legally available". Licensed lyric APIs require commercial agreements. **[A]**

## 5. Sources

* Amazon Music Web API overview (closed beta): https://www.developer.amazon.com/docs/music/API_web_overview.html
* Amazon Music Web API Playback V2: https://developer.amazon.com/docs/music/API_web_playback_v2.html
* Apple MusicKit: https://developer.apple.com/musickit ; Apple Music API developer tokens: https://developer.apple.com/documentation/applemusicapi/generating-developer-tokens
* Spotify — Feb 2026 developer access update: https://developer.spotify.com/blog/2026-02-06-update-on-developer-access-and-platform-security ; migration guide: https://developer.spotify.com/documentation/web-api/tutorials/february-2026-migration-guide ; July 2026 quota update: https://developer.spotify.com/blog/2026-07-23-web-api-quota-updates
* YouTube API Services developer policies guide: https://developers.google.com/youtube/terms/developer-policies-guide
* Deezer developer FAQ: https://support.deezer.com/hc/en-gb/articles/360011538897-Deezer-FAQs-For-Developers
* TIDAL Android SDK — player module: https://tidal-music.github.io/tidal-sdk-android/player/index.html ; Roon community on 30-s limits: https://community.roonlabs.com/t/tidal-tracks-limited-to-30-seconds-despite-successful-link-ref-cd7pn7/323814
* Qobuz API access (partner-only): https://apis.io/providers/qobuz/
* SoundCloud API: https://developers.soundcloud.com/docs/api ; AAC HLS change: https://developers.soundcloud.com/blog/api-streaming-urls
* Radio Browser API: https://api.radio-browser.info
* Internet Archive Live Music Archive help: https://help.archive.org/help/live-music-archive-etree-org/
* Jamendo API: https://developer.jamendo.com/v3.0/tracks ; terms: https://devportal.jamendo.com/api_terms_of_use
* Audius API: https://docs.audius.org/api
* JioSaavn API policy: https://www.jiosaavn.com/corporate/api-policy
