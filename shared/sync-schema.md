# Sync and backup format

One set of records, used three ways: the Supabase tables (snake_case columns), the JSON backup
file (camelCase keys) and the local databases of both apps. Implementations:
Android `data/sync/SyncRepository.kt`, web `src/lib/sync.ts`, SQL
`backend/supabase/migrations/20261127000000_init.sql`.

## What is synced

| Record | Key | Synced | Never synced |
|---|---|---|---|
| Favourite | `trackKey` | title, artist, updatedAt, deleted, deviceId | — |
| Playlist | `id` (UUID made on the device that created it) | name, ordered items, timestamps, deleted | file paths |
| Play (history) | `id` (UUID per play) | trackKey, title, artist, album, playedAt, listenedMs | — |
| Library track (opt-in, per device) | `deviceId` + `trackKey` | title, artist, album, durationMs, codec, sampleRate, bitDepth | the file, artwork, path |

Audio files are never uploaded by either app. Playlists and favourites reference songs by
**track key**, so they match the same song on another device without sharing files.

## Track key

`normalize(artist) | normalize(album) | normalize(title) | round(durationMs / 2000)`

`normalize` = Unicode NFKD, remove combining marks U+0300–U+036F (Latin accents; other scripts'
vowel signs are kept), lower-case, replace every run of non-letter/mark/digit characters with one
space, trim. Two-second duration buckets tolerate re-encodes. Vectors: `test-vectors/track-keys.json`.

## Merge rule (favourites, playlists)

Last writer wins per record, compared in this order:

1. larger `updatedAt` (client epoch ms) wins;
2. on a tie, a deletion (`deleted = true`) wins;
3. on a tie, the larger `deviceId` (string comparison) wins.

Deletions are tombstones (`deleted = true`, playlist items emptied) so they propagate. A sync:
fetch the remote stamps, apply remote winners locally, upload local winners. History is
append-only: upload local plays not yet synced; download other devices' plays newer than the last
sync minus 24 h (ids make re-downloads harmless). Vectors: `test-vectors/sync-merge.json`.

Clock skew between devices can make an older edit win; acceptable for personal data.

## Supabase REST calls (both apps)

All with headers `apikey: <publishable key>` and, after sign-in, `Authorization: Bearer <access token>`.

| Purpose | Request |
|---|---|
| Create account | `POST /auth/v1/signup` `{email, password}` |
| Sign in | `POST /auth/v1/token?grant_type=password` `{email, password}` |
| Refresh (on HTTP 401, once) | `POST /auth/v1/token?grant_type=refresh_token` `{refresh_token}` |
| Read | `GET /rest/v1/<table>?select=…&limit=1000&offset=N` (paged) |
| Upsert | `POST /rest/v1/<table>?on_conflict=<primary key>` with `Prefer: resolution=merge-duplicates,missing=default,return=minimal`, batches of 500 |
| Replace this device's library rows | `DELETE /rest/v1/library_tracks?device_id=eq.<id>` then upsert |

Every row carries `user_id`; RLS rejects rows whose `user_id` isn't the caller's.

## Backup file

```json
{
  "format": "unpaused-backup",
  "version": 1,
  "exportedAt": 1764250000000,
  "deviceId": "android-1a2b3c4d",
  "playlists": [{ "id": "…uuid…", "name": "Drive", "items": [{ "trackKey": "…", "title": "…", "artist": "…", "addedAt": 0 }],
                  "createdAt": 0, "updatedAt": 0, "deleted": false, "deviceId": "…" }],
  "favorites": [{ "trackKey": "…", "title": "…", "artist": "…", "updatedAt": 0, "deleted": false, "deviceId": "…" }],
  "history":   [{ "id": "…uuid…", "trackKey": "…", "title": "…", "artist": "…", "album": "…", "playedAt": 0, "listenedMs": 0, "deviceId": "…" }]
}
```

Importing merges with the rule above, so importing the same file twice changes nothing. Readers
reject other `format` values and versions newer than they understand; unknown keys are ignored.
