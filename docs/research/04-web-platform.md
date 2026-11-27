# 04 — Web companion: browser capabilities and limits

Research date: 27 Nov 2026. Legend: **[V]** verified, **[A]** assumption.

## Framework choice

The brief suggested Next.js. This app has **no server-side work**: your files
never leave the device, and the optional sync talks to Supabase straight from
the browser. Next.js's strengths are server rendering, routing on a server and
API routes, and none of them help here. **Vite + React + TypeScript** builds a
static single-page app that deploys free to GitHub Pages or Cloudflare Pages.
It also avoids server-rendering problems with browser-only APIs such as
IndexedDB, `AudioContext` and File System Access. Tailwind CSS v4 and IndexedDB
(via Dexie) are kept as requested.

## Capability matrix

| Capability | Chrome/Edge (desktop, Android) | Firefox | Safari (macOS/iOS) | Notes |
|---|---|---|---|---|
| FLAC | ✅ | ✅ (51+ desktop, 58+ Android) | ✅ (11+) | [V] [MDN](https://developer.mozilla.org/en-US/docs/Web/Media/Guides/Formats/Audio_codecs) |
| ALAC | ❌ | ❌ | ✅ | [V] MDN. The app shows "not supported in this browser" |
| AIFF | ❌ [A] | ❌ [A] | ✅ [A] | MDN does not cover AIFF. The app converts AIFF to an in-memory WAV, which every browser plays |
| MP3 | ✅ | ✅ | ✅ | [V] MDN |
| AAC (.m4a) | ✅ on Google Chrome, but not in plain Chromium builds | Uses the OS decoder | ✅ | [V] MDN |
| Opus (Ogg/WebM) | ✅ | ✅ | Limited | [V] MDN |
| Vorbis | ✅ | ✅ | ❌ | [V] MDN |
| Folder picker (`showDirectoryPicker`) | ✅ desktop; Chrome Android 132+ | ❌ | ❌ | [V] [web-features](https://web-platform-dx.github.io/web-features-explorer/features/file-system-access). Fallback: `<input webkitdirectory>` or multi-file select |
| Persisted file handles | ✅ (stored in IndexedDB) | — | — | Otherwise, re-import or copy into OPFS |
| Background audio | Generally continues with an `<audio>` element plus Media Session. Android battery settings can still freeze the tab | Similar | iOS Safari keeps audio playing in the background, but **installed home-screen web apps** have long-standing bugs where audio stops [V] ([WebKit 198277](https://bugs.webkit.org/show_bug.cgi?id=198277)) | A web app **cannot** match a native foreground media service |
| Autoplay | Audio needs a user gesture; an `AudioContext` created before one starts suspended | Same | Same | [V] [MDN autoplay](https://developer.mozilla.org/en-US/docs/Web/Media/Guides/Autoplay) |
| Equalizer | Web Audio `BiquadFilterNode` chain | ✅ | ✅ | Needs CORS for cross-origin streams (see below) |
| Hi-res output | `AudioContext` runs at the OS mixer rate | Same | Same | No bit-perfect path in browsers [A] |

## Consequences for the design

1. **Local files** play through `<audio>` elements wired into a Web Audio graph
   (EQ, ReplayGain gain node, limiter).
2. **Internet radio** streams usually lack CORS headers. Routing a cross-origin
   element through Web Audio without CORS produces silence **[A — Web Audio spec
   behaviour for tainted media]**. Radio therefore plays on a separate element
   that is **not** connected to the EQ, and the UI says so.
3. **Near-gapless playback.** The next track is preloaded on a second element
   and started on `ended`. True sample-accurate gapless would need whole-file
   decoding into memory (about 100 MB for a 5-minute 24/96 track), so it is not
   attempted. The UI says "gapless: best effort".
4. **Media Session API** gives lock-screen and notification controls on
   Android Chrome.
5. **The library stays on the device.** Metadata goes in IndexedDB. Audio is
   either referenced by file handle (Chromium) or copied into the Origin
   Private File System when you choose "keep a copy in this browser". Nothing
   is uploaded.

## Sources

* MDN audio codecs guide: https://developer.mozilla.org/en-US/docs/Web/Media/Guides/Formats/Audio_codecs
* MDN autoplay guide: https://developer.mozilla.org/en-US/docs/Web/Media/Guides/Autoplay
* File System Access support (web-features): https://web-platform-dx.github.io/web-features-explorer/features/file-system-access
* WebKit bug 198277 (audio stops in standalone web apps): https://bugs.webkit.org/show_bug.cgi?id=198277
