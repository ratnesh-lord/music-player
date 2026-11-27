# Design system

Original design in a monochrome, dot-matrix style: black (or paper-white) surfaces, white type, one
red accent, and a dot grid as the visual motif. It uses no third-party brand names, logos, icons or
typefaces that aren't openly licensed. Tokens: [`shared/design-tokens.json`](../shared/design-tokens.json);
Android: `ui/theme/Theme.kt`, `ui/components/Components.kt`; web: `web/src/styles.css`, `web/src/ui/bits.tsx`.

## Type

| Role | Face | Where |
|---|---|---|
| Display | **Doto** Bold (dot-matrix, rounded dots) | Screen titles ("LIBRARY"), the wordmark. Avoid colons: the glyph reads poorly at large sizes. |
| Body | **Geist** 400 / 500 / 600 | Titles, lists, explanations |
| Technical | **Geist Mono** 400 / 500, upper-case, tracked | Labels, codec and rate lines, times, verdict tags |

All three are under the SIL Open Font License 1.1 (licence texts ship in the app's About screen and
`web/public/fonts`). Android uses static instances cut from the variable fonts with fontTools; the
web uses WOFF2.

## Colour

| Token | Dark | Light | Use |
|---|---|---|---|
| background | `#000000` | `#F3F3F1` | Screens |
| surface container | `#0E0E0E`–`#1C1C1C` | `#FFFFFF`–`#E4E4E1` | Cards, sheets |
| outline | `#2A2A2A` | `#CFCFCB` | Borders, inactive dots |
| text | `#FFFFFF` | `#0A0A0A` | Primary text |
| dim | `#9A9A9A` | `#5E5E5E` | Secondary text |
| accent | `#E5322D` | `#E5322D` | Play head, selected dot, now-playing title, warnings, quality line |

The accent is used sparingly: one red element per area at most.

## Components

* **Dot seek bar**: dots every 7 dp; played dots solid, buffered dots dim, the rest outline; the
  play head is a larger red dot. Tap or drag to seek. Disabled for live radio.
* **Chips**: pill outline; selected = filled with a red dot. Used for focus modes, tabs and filters.
* **Pill buttons**: filled (primary) or outlined (secondary), Mono upper-case label.
* **Section header**: red dot + Mono label.
* **Artwork**: rounded square over a dot grid, so missing art still looks intentional.
* **Info cards**: rounded, hairline border; key/value rows in Mono.
* **Badges**: only for lossless files (`FLAC`, `FLAC HI-RES`). Lossy files carry no badge, and
  radio streams show what the station reports.

## Honesty rules in the UI

* Source quality and output path are separate panels. "Hi-Res" only appears for lossless files with
  more than 16 bits or more than 48 kHz.
* "Bit-perfect" is only stated as what the output device *offers* (Android 14+); Unpaused v1 never
  claims to use it.
* Bluetooth output is described as re-encoded and lossy.
