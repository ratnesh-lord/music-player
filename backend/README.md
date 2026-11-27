# Backend (optional)

Unpaused works fully without a backend. This folder only matters if you want playlists, favourites
and listening history to sync between your phone and the web app.

Sync uses **your own free Supabase project**. There is no Unpaused server, no shared database and
no secret key in either app: each app talks to your project with its *publishable* key, and
row-level security (RLS) ensures every request can only read or write the signed-in user's rows.

| What | Where |
|---|---|
| Schema, RLS policies, grants | [`supabase/migrations/20261127000000_init.sql`](supabase/migrations/20261127000000_init.sql) |
| Record formats, merge rules, REST calls | [`../shared/sync-schema.md`](../shared/sync-schema.md) |
| Step-by-step setup | [`../docs/SETUP.md`](../docs/SETUP.md#optional-sync-with-supabase) |
| Costs and alternatives | [`../docs/research/05-sync-and-costs.md`](../docs/research/05-sync-and-costs.md) |

Apply the migration in the Supabase dashboard (SQL Editor → paste the file → Run) or with the
Supabase CLI (`supabase link` then `supabase db push`). It is idempotent: running it twice is safe.

Never put the `service_role` / `sb_secret_…` key in an app or in this repository.
