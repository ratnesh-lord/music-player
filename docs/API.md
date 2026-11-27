# Interfaces

Unpaused has no server of its own. These are the interfaces its parts expose or call.

## Android playback service (Media3 `MediaLibraryService`)

Service: `app.unpaused.playback.PlaybackService`, exported with the
`androidx.media3.session.MediaLibraryService` and `android.media.browse.MediaBrowserService` intent
filters, `foregroundServiceType="mediaPlayback"`. Any Media3 `MediaController` or legacy media
browser (Bluetooth, Wear, System UI) can connect; all standard player commands work.

### Media ids

| Id | Meaning |
|---|---|
| `track:<row id>` | A library track. Controllers may send just the id; the service resolves the file. |
| `radio:<station uuid>` | A Radio Browser station (stored locally once played or favourited) |
| `uri:<content uri>` + `RequestMetadata.mediaUri` | A file opened from another app ("Open with") |
| empty id + `RequestMetadata.searchQuery` | Voice search: plays matching library tracks |

### Custom session commands

| Action | Arguments | Effect |
|---|---|---|
| `app.unpaused.TOGGLE_FAVORITE` | — | Toggles the current track; result extra `favorite` (Boolean) |
| `app.unpaused.TOGGLE_MIX` | — | Switches Mix mode on, or back to the previous mode |
| `app.unpaused.SLEEP_TIMER` | `minutes` (Int): `0` cancel, `-1` end of track, `>0` minutes | Fades out over the last 15 s, then pauses |
| `app.unpaused.SET_OUTPUT` | `deviceId` (Int): an `AudioDeviceInfo` id, `-1` automatic | Preferred output device |
| `app.unpaused.RESTORE_QUEUE` | — | Loads the saved queue (paused) if the player is empty |

The notification shows a heart and a Mix toggle as media button preferences.

### Browse tree

`root` → `songs`, `albums` (→ `album:<albumId>`), `playlists` (→ `playlist:<id>`), `favorites`.
`recent` (requested with the "recent" root hint) holds the last played item, for System UI playback
resumption. Children are paged with the standard `page` / `pageSize`.

### Playback resumption

`androidx.media3.session.MediaButtonReceiver` is declared, and `onPlaybackResumption` rebuilds the
saved queue, so a Bluetooth play button or the System UI resume card restarts playback after the
app was closed or the phone restarted.

### Activity

`MainActivity` accepts `ACTION_VIEW` for `content://` URIs with an `audio/*` type and plays the file.

## External APIs called

### Radio Browser (both apps)

Free, keyless, community-run directory: <https://api.radio-browser.info>. Android finds mirrors by
DNS (`all.api.radio-browser.info`, as the project recommends) and falls back to the round-robin name;
the web app uses the round-robin name. A descriptive `User-Agent` is sent from Android (browsers set
their own).

| Call | Used for |
|---|---|
| `GET /json/stations/topclick/60?hidebroken=true` | Popular stations |
| `GET /json/stations/search?name=&tag=&countrycode=&hidebroken=true&order=clickcount&reverse=true&limit=60` | Search |
| `GET /json/stations/byuuid?uuids=<uuid>` | Resolving a saved id (Android) |
| `GET /json/url/<uuid>` | Reporting a play ("click"), as the directory asks |

Fields read: `stationuuid`, `name`, `url`, `url_resolved`, `homepage`, `favicon`, `tags`,
`countrycode`, `codec`, `bitrate`, `hls`, `lastcheckok`. Streams are played from each station's own
server; Unpaused adds no adverts and doesn't re-host streams.

### Supabase (optional sync)

Auth and REST calls, headers and record formats: [`shared/sync-schema.md`](../shared/sync-schema.md).

## Files

* Backup JSON (`format: "unpaused-backup"`, `version: 1`): [`shared/sync-schema.md`](../shared/sync-schema.md#backup-file).
* LRC lyrics: `[mm:ss.xx]` time tags (several per line allowed), `[offset:±ms]`, `[ti:]`, `[ar:]`;
  word-level `<mm:ss.xx>` tags are ignored; untimed text is shown as plain lyrics.
