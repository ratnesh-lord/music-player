-- Unpaused optional sync schema for a free Supabase project (Postgres).
--
-- * Every row belongs to the signed-in user; row-level security (RLS) hides everyone else's rows.
-- * Only playlists, favourites, listening history and (opt-in) library metadata are stored.
--   Audio files, artwork and file paths are never uploaded by the apps.
-- * Timestamps are client epoch milliseconds (bigint). Both apps merge with last-writer-wins on
--   updated_at (ties: deletion wins, then the larger device_id). See shared/sync-schema.md.
-- * Column names are snake_case; the apps' JSON backup uses the same records in camelCase.

create table if not exists public.favorites (
  user_id    uuid    not null default auth.uid() references auth.users (id) on delete cascade,
  track_key  text    not null check (char_length(track_key) <= 1000),
  title      text    not null default '' check (char_length(title) <= 1000),
  artist     text    not null default '' check (char_length(artist) <= 1000),
  updated_at bigint  not null,
  deleted    boolean not null default false,
  device_id  text    not null default '' check (char_length(device_id) <= 100),
  primary key (user_id, track_key)
);

create table if not exists public.playlists (
  user_id    uuid    not null default auth.uid() references auth.users (id) on delete cascade,
  id         text    not null check (char_length(id) <= 100),
  name       text    not null check (char_length(name) <= 500),
  -- [{ "track_key": "...", "title": "...", "artist": "...", "added_at": 0 }, ...] in play order
  items      jsonb   not null default '[]'::jsonb check (jsonb_typeof(items) = 'array' and jsonb_array_length(items) <= 20000),
  created_at bigint  not null default 0,
  updated_at bigint  not null,
  deleted    boolean not null default false,
  device_id  text    not null default '' check (char_length(device_id) <= 100),
  primary key (user_id, id)
);

create table if not exists public.play_history (
  user_id     uuid   not null default auth.uid() references auth.users (id) on delete cascade,
  id          text   not null check (char_length(id) <= 100),
  track_key   text   not null check (char_length(track_key) <= 1000),
  title       text   not null default '' check (char_length(title) <= 1000),
  artist      text   not null default '' check (char_length(artist) <= 1000),
  album       text   not null default '' check (char_length(album) <= 1000),
  played_at   bigint not null,
  listened_ms bigint not null default 0,
  device_id   text   not null default '' check (char_length(device_id) <= 100),
  primary key (user_id, id)
);
create index if not exists play_history_user_played_at on public.play_history (user_id, played_at);

-- Opt-in, per device: what music each device has (metadata only), so the web app can show it.
create table if not exists public.library_tracks (
  user_id     uuid    not null default auth.uid() references auth.users (id) on delete cascade,
  device_id   text    not null check (char_length(device_id) <= 100),
  track_key   text    not null check (char_length(track_key) <= 1000),
  title       text    not null default '' check (char_length(title) <= 1000),
  artist      text    not null default '' check (char_length(artist) <= 1000),
  album       text    not null default '' check (char_length(album) <= 1000),
  duration_ms bigint  not null default 0,
  codec       text    check (char_length(codec) <= 20),
  sample_rate integer,
  bit_depth   integer,
  updated_at  bigint  not null,
  primary key (user_id, device_id, track_key)
);

-- Row-level security: each table only ever exposes and accepts the caller's own rows.
alter table public.favorites      enable row level security;
alter table public.playlists      enable row level security;
alter table public.play_history   enable row level security;
alter table public.library_tracks enable row level security;

drop policy if exists "own rows" on public.favorites;
create policy "own rows" on public.favorites for all to authenticated
  using ((select auth.uid()) = user_id) with check ((select auth.uid()) = user_id);

drop policy if exists "own rows" on public.playlists;
create policy "own rows" on public.playlists for all to authenticated
  using ((select auth.uid()) = user_id) with check ((select auth.uid()) = user_id);

drop policy if exists "own rows" on public.play_history;
create policy "own rows" on public.play_history for all to authenticated
  using ((select auth.uid()) = user_id) with check ((select auth.uid()) = user_id);

drop policy if exists "own rows" on public.library_tracks;
create policy "own rows" on public.library_tracks for all to authenticated
  using ((select auth.uid()) = user_id) with check ((select auth.uid()) = user_id);

-- Signed-out (anon) requests get nothing; signed-in users get only what RLS allows.
revoke all on public.favorites, public.playlists, public.play_history, public.library_tracks from anon;
grant select, insert, update, delete on public.favorites, public.playlists, public.play_history, public.library_tracks to authenticated;
