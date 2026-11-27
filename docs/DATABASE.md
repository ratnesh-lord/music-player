# Data and storage

All data lives on the device unless the user turns on sync. Nothing here holds audio: files stay
where they are (Android) or in the browser's own storage (web).

## Android (Room, `unpaused.db`, version 1)

| Table | Key | Contents |
|---|---|---|
| `tracks` | `id` (auto); unique `uri` | One row per audio file: tags, sort keys, `trackKey`, `albumId`, source (MediaStore or folder), file size and modified time; filled in later: codec, sample rate, bit depth, channels, bitrate, ReplayGain, has-lyrics, artwork path |
| `tracks_fts` | FTS4 (unicode61) over `tracks` | Title, artist, album, album artist, genre, file name — prefix search |
| `playlists` | `id`; unique `syncId` | Name, timestamps, tombstone, device id |
| `playlist_items` | (`playlistId`, `position`) | Track key plus cached title/artist |
| `favorites` | `trackKey` | Tombstone-able, for sync |
| `play_history` | UUID | One per counted play (30 s or half the track) |
| `diagnostics` | auto | Interruption log, 14-day retention |
| `folders` | tree URI | Folders added through the system picker |
| `radio_stations` | Radio Browser UUID | Favourite and recently played stations |
| `lyrics_sidecars` | track URI | `.lrc` file found next to the track |

Indexes: `uri` (unique), `albumId`, `artistKey`, `trackKey`, `dateAdded`, `folderPath`, `genre`.
Room exports the schema JSON to `android/app/schemas/` at build time; commit it before the first
schema change and add a migration (v1 only falls back to a rebuild on downgrade).

Scanning is incremental: a file whose size and modified time are unchanged keeps its row (and the
details already read); changed files are re-read with the same row id, so the queue and playlists
stay valid; missing files are removed.

Other on-device files: `files/playback_state.json` (queue), `files/playback_position.json`
(position, written every 5 s while playing), DataStore `settings` (preferences, sync session),
`cache/artwork/*.jpg` (one cover per album, recreated if cleared).

## Web (IndexedDB via Dexie, database `unpaused`, version 1)

| Store | Key | Contents |
|---|---|---|
| `tracks` | auto `id`; unique `fileKey` | Tags, quality, ReplayGain, lyrics text, small artwork blob, where the bytes are (`opfs`, `handle`, `session`) |
| `playlists` | `id` (UUID) | Name and items (track keys) |
| `favorites` | `trackKey` | — |
| `plays` | UUID | History, `synced` flag |
| `stations` | Radio Browser UUID | Favourites, last played |
| `settings` | key | EQ/ReplayGain settings, Supabase URL and key, session, device id |
| `handles` | auto | Folder handles (Chrome/Edge) |

Audio copies ("keep a copy") are files in the Origin Private File System, named by UUID.

## Supabase (optional)

`backend/supabase/migrations/20261127000000_init.sql`: `favorites`, `playlists`, `play_history`,
`library_tracks`, each keyed by `user_id` plus the record id, with row-level security so a user
only ever sees their own rows. Record formats and the merge rule: [`shared/sync-schema.md`](../shared/sync-schema.md).
