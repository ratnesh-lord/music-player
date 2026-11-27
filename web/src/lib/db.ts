import Dexie, { type Table } from "dexie";
import type { Codec } from "./core/quality";
import type { ReplayGainInfo } from "./core/replayGain";

/** Where a track's bytes live. Nothing is ever uploaded: these are all on this device. */
export type Storage = "opfs" | "handle" | "session";

export interface Track {
  id?: number;
  fileKey: string;
  fileName: string;
  path: string;
  title: string;
  artist: string;
  album: string;
  albumArtist?: string;
  genre?: string;
  year?: number;
  trackNo?: number;
  discNo?: number;
  durationMs: number;
  trackKey: string;
  albumKey: string;
  codec: Codec;
  container?: string;
  sampleRate?: number;
  bitDepth?: number;
  channels?: number;
  bitrate?: number;
  sizeBytes: number;
  rg: ReplayGainInfo;
  lyrics?: string;
  artwork?: Blob;
  storage: Storage;
  opfsName?: string;
  handleId?: number;
  addedAt: number;
}

export interface PlaylistItem { trackKey: string; title: string; artist: string; addedAt: number }
export interface Playlist { id: string; name: string; items: PlaylistItem[]; createdAt: number; updatedAt: number; deleted: boolean; deviceId: string }
export interface Favorite { trackKey: string; title: string; artist: string; updatedAt: number; deleted: boolean; deviceId: string }
export interface Play { id: string; trackKey: string; title: string; artist: string; album: string; playedAt: number; listenedMs: number; deviceId: string; synced: 0 | 1 }
export interface Station { stationUuid: string; name: string; streamUrl: string; homepage?: string; faviconUrl?: string; tags?: string; countryCode?: string; codec?: string; bitrateKbps?: number; favoritedAt: number; lastPlayedAt: number }
export interface Setting { key: string; value: unknown }
export interface DirHandle { id?: number; name: string; handle: FileSystemDirectoryHandle; addedAt: number }

class UnpausedDb extends Dexie {
  tracks!: Table<Track, number>;
  playlists!: Table<Playlist, string>;
  favorites!: Table<Favorite, string>;
  plays!: Table<Play, string>;
  stations!: Table<Station, string>;
  settings!: Table<Setting, string>;
  handles!: Table<DirHandle, number>;

  constructor() {
    super("unpaused");
    this.version(1).stores({
      tracks: "++id, &fileKey, trackKey, albumKey, artist, album, addedAt",
      playlists: "id, updatedAt",
      favorites: "trackKey, updatedAt",
      plays: "id, playedAt, trackKey, synced",
      stations: "stationUuid, favoritedAt, lastPlayedAt",
      settings: "key",
      handles: "++id",
    });
  }
}

export const db = new UnpausedDb();

export async function getSetting<T>(key: string, fallback: T): Promise<T> {
  const row = await db.settings.get(key);
  return row === undefined ? fallback : (row.value as T);
}

export async function setSetting(key: string, value: unknown): Promise<void> {
  await db.settings.put({ key, value });
}

export async function deviceId(): Promise<string> {
  const existing = await getSetting<string | null>("deviceId", null);
  if (existing) return existing;
  const id = "web-" + crypto.randomUUID().slice(0, 8);
  await setSetting("deviceId", id);
  return id;
}
