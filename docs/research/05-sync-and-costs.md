# 05 — Sync backend options and infrastructure cost

Research date: 27 Nov 2026. Legend: **[V]** verified, **[A]** assumption.

## What gets synced (and what never does)

Synced: playlists, favourites, listening history, playback preferences, and
library *metadata* (title, artist, album, duration, a content key).
**Never synced: audio files, artwork or file paths.** Each device plays its own
copies. A track is matched across devices by a normalised key
(`artist|album|title|duration rounded to 2 s`), documented in
`shared/sync-schema.md`.

## Options

| Option | Free tier | Gotchas | Offline / local-first | Open source | Fit |
|---|---|---|---|---|---|
| **Supabase** | 500 MB database per project, 50k MAU, 5 GB egress, 2 active projects; **paused after 1 week of inactivity** (one click to restore) [V] ([pricing](https://supabase.com/pricing)) | The pause. Free projects have no backups [V] | We provide it (Room / IndexedDB are the source of truth) | ✅ (self-hostable later) | ✅ **Chosen**: Postgres + row-level security, standard REST, no vendor SDK needed on Android |
| **Firebase (Spark)** | Firestore 1 GiB stored, 50k reads/day, 20k writes/day, 10 GiB/month egress; Auth 50k MAU [V] ([pricing](https://firebase.google.com/pricing)) | Proprietary; Android SDK pulls in Play-services-based auth | Built into the Firestore SDKs | ❌ | Good runner-up |
| **Self-hosted** (PocketBase / Supabase on your PC or a Raspberry Pi) | €0 software; hardware and electricity are yours | Must stay online and be reachable (tunnel or VPN) | We provide it | ✅ | Possible later (same schema works on self-hosted Supabase) |
| **Local-first file export** | €0 | Manual: export a JSON bundle and import it on the other device | ✅ | ✅ | ✅ **Also shipped**: always available, no account |

Why Supabase over Firebase: open source (no lock-in, because the same SQL runs
self-hosted), plain HTTPS REST that the Android app calls with OkHttp (no Google
SDK), and row-level security. Supabase's **publishable key** (`sb_publishable_…`)
replaces the legacy `anon` key and is documented as safe to ship in web and
mobile apps, *because* RLS limits what it can reach. Secret keys must never be
shipped. The legacy `anon`/`service_role` keys are planned for deprecation in
early 2027 [V] ([Supabase API keys](https://supabase.com/docs/guides/api/api-keys)).
This app only ever uses the publishable key, and you enter it in the app's
settings. It is never committed.
The inactivity pause does not matter for daily use, and if it happens the app
keeps working offline and syncs once you restore the project. **[A]**

## Cost estimate

| Item | Cost |
|---|---|
| Android app (sideloaded APK built by GitHub Actions) | €0 (Actions minutes are free on public repos [A]) |
| Web app hosting (GitHub Pages or Cloudflare Pages) | €0 |
| Sync (Supabase free tier; a playlist/favourites/history database for one person is a few MB) | €0 |
| Music: local files, internet radio, Internet Archive, Jamendo, Audius | €0 |
| Optional: Google Play publishing (not needed for personal use) | US$25 one-off [A] |
| **Total mandatory** | **€0** |

## Sources

* Supabase pricing: https://supabase.com/pricing ; pausing FAQ: https://supabase.com/kb/faqs/platform/free-project-pausing
* Supabase API keys: https://supabase.com/docs/guides/api/api-keys
* Firebase pricing: https://firebase.google.com/pricing
