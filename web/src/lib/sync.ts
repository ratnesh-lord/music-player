// Supabase sync + JSON backup. Same tables, rules and backup format as the Android app
// (shared/sync-schema.md). Only playlists, favourites, history (and opt-in library metadata) leave the
// browser; audio files never do.
import { db, deviceId, getSetting, setSetting, type Favorite, type Play, type Playlist } from "./db";
import { plan } from "./core/merge";

export interface SyncConfig { url: string; key: string }
interface Session { accessToken: string; refreshToken: string; userId: string }

const snake = (o: Record<string, unknown>) => Object.fromEntries(Object.entries(o).map(([k, v]) => [k.replace(/[A-Z]/g, (c) => "_" + c.toLowerCase()), v]));
const camel = (o: Record<string, unknown>) => Object.fromEntries(Object.entries(o).map(([k, v]) => [k.replace(/_([a-z])/g, (_, c: string) => c.toUpperCase()), v]));

export async function getConfig(): Promise<SyncConfig> {
  return { url: await getSetting("supabaseUrl", ""), key: await getSetting("supabaseKey", "") };
}

function check(cfg: SyncConfig): SyncConfig {
  const url = cfg.url.trim().replace(/\/+$/, "");
  if (!url.startsWith("https://")) throw new Error("Enter your Supabase project URL (https://…)");
  if (!cfg.key.trim()) throw new Error("Enter your Supabase publishable key");
  if (cfg.key.startsWith("sb_secret_")) throw new Error("That's a secret key. Use the publishable key (sb_publishable_…)");
  return { url, key: cfg.key.trim() };
}

async function auth(cfg: SyncConfig, path: string, body: unknown): Promise<Record<string, unknown>> {
  const res = await fetch(`${cfg.url}/auth/v1/${path}`, { method: "POST", headers: { apikey: cfg.key, "Content-Type": "application/json" }, body: JSON.stringify(body) });
  const json = (await res.json().catch(() => ({}))) as Record<string, unknown>;
  if (!res.ok) throw new Error(String(json.msg ?? json.message ?? json.error_description ?? json.error ?? `HTTP ${res.status}`));
  return json;
}

function toSession(j: Record<string, unknown>): Session | null {
  const user = j.user as { id?: string } | undefined;
  return typeof j.access_token === "string" && typeof j.refresh_token === "string" && user?.id ? { accessToken: j.access_token, refreshToken: j.refresh_token, userId: user.id } : null;
}

export async function signIn(email: string, password: string) {
  const cfg = check(await getConfig());
  const s = toSession(await auth(cfg, "token?grant_type=password", { email, password }));
  if (!s) throw new Error("Sign-in failed");
  await setSetting("syncSession", s);
}

export async function signUp(email: string, password: string): Promise<string> {
  const cfg = check(await getConfig());
  const s = toSession(await auth(cfg, "signup", { email, password }));
  if (s) { await setSetting("syncSession", s); return "Signed in."; }
  return "Check your email and confirm the address, then sign in.";
}

export async function signOut() { await setSetting("syncSession", null); }
export const isSignedIn = async () => (await getSetting<Session | null>("syncSession", null)) != null;

class Api {
  constructor(private cfg: SyncConfig, private session: Session) {}
  get userId() { return this.session.userId; }

  private async call(path: string, init: RequestInit = {}, retry = true): Promise<Response> {
    const res = await fetch(`${this.cfg.url}/rest/v1/${path}`, { ...init, headers: { apikey: this.cfg.key, Authorization: `Bearer ${this.session.accessToken}`, "Content-Type": "application/json", ...(init.headers ?? {}) } });
    if (res.status === 401 && retry) {
      const s = toSession(await auth(this.cfg, "token?grant_type=refresh_token", { refresh_token: this.session.refreshToken }).catch(() => ({})));
      if (!s) { await setSetting("syncSession", null); throw new Error("Session expired. Sign in again."); }
      this.session = s;
      await setSetting("syncSession", s);
      return this.call(path, init, false);
    }
    if (!res.ok) {
      const j = (await res.json().catch(() => ({}))) as Record<string, unknown>;
      throw new Error(`${path.split("?")[0]}: ${String(j.message ?? `HTTP ${res.status}`)}`);
    }
    return res;
  }

  async getAll<T>(table: string, query: string): Promise<T[]> {
    const out: T[] = [];
    for (let offset = 0; ; offset += 1000) {
      const page = (await (await this.call(`${table}?${query}&limit=1000&offset=${offset}`)).json()) as Record<string, unknown>[];
      out.push(...page.map((r) => camel(r) as T));
      if (page.length < 1000) return out;
    }
  }

  async upsert(table: string, conflict: string, rows: Record<string, unknown>[]) {
    for (let i = 0; i < rows.length; i += 500) {
      const body = rows.slice(i, i + 500).map((r) => snake({ ...r, userId: this.userId }));
      await this.call(`${table}?on_conflict=${conflict}`, { method: "POST", headers: { Prefer: "resolution=merge-duplicates,return=minimal" }, body: JSON.stringify(body) });
    }
  }

  async remove(table: string, filter: string) { await this.call(`${table}?${filter}`, { method: "DELETE", headers: { Prefer: "return=minimal" } }); }
}

const stamp = (x: { updatedAt: number; deleted: boolean; deviceId: string }, id: string) => ({ id, updatedAt: x.updatedAt, deleted: x.deleted, deviceId: x.deviceId });

/** Applies remote winners locally and returns the local winners to upload. */
async function mergeFavorites(remote: Favorite[]): Promise<{ pulled: number; push: Favorite[] }> {
  const local = await db.favorites.toArray();
  const p = plan(local.map((f) => stamp(f, f.trackKey)), remote.map((f) => stamp(f, f.trackKey)));
  const r = new Map(remote.map((f) => [f.trackKey, f]));
  const l = new Map(local.map((f) => [f.trackKey, f]));
  await db.favorites.bulkPut(p.pull.map((k) => r.get(k)!).map((f) => ({ trackKey: f.trackKey, title: f.title, artist: f.artist, updatedAt: f.updatedAt, deleted: f.deleted, deviceId: f.deviceId })));
  return { pulled: p.pull.length, push: p.push.map((k) => l.get(k)!) };
}

async function mergePlaylists(remote: Playlist[]): Promise<{ pulled: number; push: Playlist[] }> {
  const local = await db.playlists.toArray();
  const p = plan(local.map((x) => stamp(x, x.id)), remote.map((x) => stamp(x, x.id)));
  const r = new Map(remote.map((x) => [x.id, x]));
  const l = new Map(local.map((x) => [x.id, x]));
  await db.playlists.bulkPut(p.pull.map((id) => r.get(id)!).map((x) => ({ id: x.id, name: x.name, items: x.deleted ? [] : (x.items ?? []).map((i) => camel(i as unknown as Record<string, unknown>) as unknown as Playlist["items"][number]), createdAt: x.createdAt || x.updatedAt, updatedAt: x.updatedAt, deleted: x.deleted, deviceId: x.deviceId })));
  return { pulled: p.pull.length, push: p.push.map((id) => l.get(id)!) };
}

export async function syncNow(includeLibrary: boolean): Promise<{ pulled: number; pushed: number }> {
  const cfg = check(await getConfig());
  const session = await getSetting<Session | null>("syncSession", null);
  if (!session) throw new Error("Not signed in");
  const api = new Api(cfg, session);
  const me = await deviceId();
  const startedAt = Date.now();
  let pulled = 0, pushed = 0;

  const favs = await mergeFavorites(await api.getAll<Favorite>("favorites", "select=track_key,title,artist,updated_at,deleted,device_id"));
  await api.upsert("favorites", "user_id,track_key", favs.push as unknown as Record<string, unknown>[]);
  pulled += favs.pulled; pushed += favs.push.length;

  const lists = await mergePlaylists(await api.getAll<Playlist>("playlists", "select=id,name,items,created_at,updated_at,deleted,device_id"));
  await api.upsert("playlists", "user_id,id", lists.push.map((x) => ({ ...x, items: x.items.map((i) => snake(i as unknown as Record<string, unknown>)) })));
  pulled += lists.pulled; pushed += lists.push.length;

  const unsynced = await db.plays.where("synced").equals(0).toArray();
  await api.upsert("play_history", "user_id,id", unsynced.map(({ synced: _s, ...rest }) => rest));
  await db.plays.bulkPut(unsynced.map((x) => ({ ...x, synced: 1 as const })));
  pushed += unsynced.length;
  const since = Math.max(0, (await getSetting("lastSyncAt", 0)) - 86_400_000);
  const remotePlays = await api.getAll<Play>("play_history", `select=id,track_key,title,artist,album,played_at,listened_ms,device_id&played_at=gt.${since}&device_id=neq.${encodeURIComponent(me)}`);
  await db.plays.bulkPut(remotePlays.map((x) => ({ ...x, synced: 1 as const })));
  pulled += remotePlays.length;

  await api.remove("library_tracks", `device_id=eq.${encodeURIComponent(me)}`);
  if (includeLibrary) {
    const seen = new Set<string>();
    const rows = (await db.tracks.toArray()).filter((t) => !seen.has(t.trackKey) && seen.add(t.trackKey)).map((t) => ({
      trackKey: t.trackKey, title: t.title, artist: t.artist, album: t.album, durationMs: t.durationMs, codec: t.codec,
      sampleRate: t.sampleRate ?? null, bitDepth: t.bitDepth ?? null, updatedAt: startedAt, deviceId: me,
    }));
    await api.upsert("library_tracks", "user_id,device_id,track_key", rows);
    pushed += rows.length;
  }
  await setSetting("lastSyncAt", startedAt);
  return { pulled, pushed };
}

// ---- JSON backup (no account) ------------------------------------------------------------------------

export async function exportBackup(): Promise<string> {
  return JSON.stringify({
    format: "unpaused-backup", version: 1, exportedAt: Date.now(), deviceId: await deviceId(),
    playlists: await db.playlists.toArray(),
    favorites: await db.favorites.toArray(),
    history: (await db.plays.toArray()).map(({ synced: _s, ...rest }) => rest),
  }, null, 2);
}

export async function importBackup(text: string): Promise<number> {
  const b = JSON.parse(text) as { format?: string; version?: number; playlists?: Playlist[]; favorites?: Favorite[]; history?: Omit<Play, "synced">[] };
  if (b.format !== "unpaused-backup") throw new Error("Not an Unpaused backup");
  if ((b.version ?? 1) > 1) throw new Error("This backup is from a newer version of Unpaused");
  const favs = await mergeFavorites((b.favorites ?? []).map((f) => ({ ...f, deleted: !!f.deleted })));
  const lists = await mergePlaylists((b.playlists ?? []).map((p) => ({ ...p, deleted: !!p.deleted, items: p.items ?? [] })));
  const plays = (b.history ?? []).map((p) => ({ ...p, synced: 0 as const }));
  await db.plays.bulkPut(plays);
  return favs.pulled + lists.pulled + plays.length;
}
