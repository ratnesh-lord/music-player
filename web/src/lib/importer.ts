import { parseBlob, type IAudioMetadata } from "music-metadata";
import { db, type Storage, type Track } from "./db";
import { codecFromMetadata } from "./core/quality";
import { fromTags } from "./core/replayGain";
import { normalize, trackKey } from "./core/trackKey";
import { isAiff, parseAiffHeader } from "./core/aiff";

export const AUDIO_EXT = ["mp3", "flac", "m4a", "mp4", "aac", "ogg", "oga", "opus", "wav", "wave", "aif", "aiff", "aifc", "webm"];
const ext = (name: string) => (name.includes(".") ? name.substring(name.lastIndexOf(".") + 1).toLowerCase() : "");
export const isAudioFile = (f: File) => AUDIO_EXT.includes(ext(f.name)) || f.type.startsWith("audio/");

/** Files picked this session (not persisted). Keyed by track id. */
export const sessionFiles = new Map<number, File>();

const fileKeyOf = (f: File, path: string) => `${path}|${f.size}|${f.lastModified}`;

/** Flattens tag values from every tag format into upper-case key → string, for ReplayGain. */
function flatTags(meta: IAudioMetadata): Record<string, string> {
  const out: Record<string, string> = {};
  for (const tags of Object.values(meta.native)) {
    for (const t of tags) {
      const key = t.id.replace(/^TXXX:/, "").replace(/^----:com\.apple\.iTunes:/, "").toUpperCase();
      const v = t.value as unknown;
      const text = typeof v === "string" || typeof v === "number" ? String(v) : Array.isArray(v) ? String(v[0]) : v && typeof v === "object" && "text" in v ? String((v as { text: unknown }).text) : undefined;
      if (text !== undefined && out[key] === undefined) out[key] = text;
    }
  }
  return out;
}

function lyricsOf(meta: IAudioMetadata): string | undefined {
  for (const l of meta.common.lyrics ?? []) {
    if (l.syncText && l.syncText.length > 0) {
      return l.syncText.map((s) => {
        const t = s.timestamp ?? 0;
        const mm = Math.floor(t / 60000), ss = Math.floor((t % 60000) / 1000), cs = Math.floor((t % 1000) / 10);
        return `[${String(mm).padStart(2, "0")}:${String(ss).padStart(2, "0")}.${String(cs).padStart(2, "0")}]${s.text}`;
      }).join("\n");
    }
    if (l.text) return l.text;
  }
  return undefined;
}

async function thumbnail(meta: IAudioMetadata): Promise<Blob | undefined> {
  const pic = meta.common.picture?.[0];
  if (!pic) return undefined;
  try {
    const bitmap = await createImageBitmap(new Blob([pic.data as BlobPart], { type: pic.format }));
    const scale = Math.min(1, 512 / Math.max(bitmap.width, bitmap.height));
    const canvas = new OffscreenCanvas(Math.round(bitmap.width * scale), Math.round(bitmap.height * scale));
    canvas.getContext("2d")!.drawImage(bitmap, 0, 0, canvas.width, canvas.height);
    return await canvas.convertToBlob({ type: "image/jpeg", quality: 0.85 });
  } catch {
    return undefined;
  }
}

export interface ImportOptions { storage: Storage; handleId?: number; onProgress?: (done: number, total: number) => void }

/**
 * Reads tags in the browser and stores metadata in IndexedDB. With storage "opfs" the file is also copied
 * into this browser's private storage (Origin Private File System) so it plays after a reload. Nothing is
 * sent over the network.
 */
export async function importFiles(entries: { file: File; path: string }[], opts: ImportOptions): Promise<{ added: number; skipped: number; failed: string[] }> {
  let added = 0, skipped = 0;
  const failed: string[] = [];
  const lrc = new Map<string, File>();
  for (const e of entries) if (ext(e.file.name) === "lrc") lrc.set(e.path.replace(/\.lrc$/i, "").toLowerCase(), e.file);
  const audio = entries.filter((e) => isAudioFile(e.file));
  const opfs = opts.storage === "opfs" ? await navigator.storage.getDirectory() : null;
  for (let i = 0; i < audio.length; i++) {
    const { file, path } = audio[i];
    opts.onProgress?.(i, audio.length);
    const fileKey = fileKeyOf(file, path);
    const existing = await db.tracks.where("fileKey").equals(fileKey).first();
    if (existing?.id != null) {
      if (opts.storage === "session") sessionFiles.set(existing.id, file);
      skipped++;
      continue;
    }
    try {
      const meta = await parseBlob(file, { duration: true });
      const f = meta.format;
      const c = meta.common;
      let durationMs = Math.round((f.duration ?? 0) * 1000);
      let sampleRate = f.sampleRate, bitDepth = f.bitsPerSample, channels = f.numberOfChannels;
      if (isAiff(new Uint8Array(await file.slice(0, 12).arrayBuffer()))) {
        const h = parseAiffHeader(new Uint8Array(await file.slice(0, 65536).arrayBuffer()));
        sampleRate = Math.round(h.sampleRate); bitDepth = h.bitsPerSample; channels = h.channels;
        if (!durationMs) durationMs = Math.round((h.sampleFrames / h.sampleRate) * 1000);
      }
      const title = c.title?.trim() || file.name.replace(/\.[^.]+$/, "");
      const artist = c.artist?.trim() || c.albumArtist?.trim() || "Unknown artist";
      const folder = path.includes("/") ? path.substring(0, path.lastIndexOf("/")) : "";
      const album = c.album?.trim() || folder.split("/").pop() || "Unknown album";
      const codec = codecFromMetadata(f.codec, f.container, file.name);
      const lyricFile = lrc.get(path.replace(/\.[^.]+$/, "").toLowerCase());
      const track: Track = {
        fileKey, fileName: file.name, path, title, artist, album,
        albumArtist: c.albumArtist?.trim() || undefined, genre: c.genres?.[0], year: c.year,
        trackNo: c.trackNumber ?? undefined, discNo: c.discNumber ?? undefined, durationMs,
        trackKey: trackKey(title, artist, album, durationMs),
        albumKey: normalize(album) + "|" + (c.albumArtist ? normalize(c.albumArtist) : "folder:" + folder.toLowerCase()),
        codec, container: f.container, sampleRate, bitDepth: f.lossless || codec === "AIFF" || codec === "WAV" ? bitDepth : undefined, channels,
        bitrate: f.bitrate ? Math.round(f.bitrate) : undefined, sizeBytes: file.size,
        rg: fromTags(flatTags(meta)),
        lyrics: lyricFile ? await lyricFile.text() : lyricsOf(meta),
        artwork: await thumbnail(meta),
        storage: opts.storage, handleId: opts.handleId, addedAt: Date.now(),
      };
      if (opfs) {
        const name = crypto.randomUUID() + "." + ext(file.name);
        const handle = await opfs.getFileHandle(name, { create: true });
        const w = await handle.createWritable();
        await w.write(file);
        await w.close();
        track.opfsName = name;
      }
      const id = await db.tracks.add(track);
      if (opts.storage === "session") sessionFiles.set(id, file);
      added++;
    } catch (err) {
      failed.push(`${file.name}: ${(err as Error).message}`);
    }
  }
  opts.onProgress?.(audio.length, audio.length);
  return { added, skipped, failed };
}

/** Recursively lists a directory picked with the File System Access API. */
export async function walkDirectory(dir: FileSystemDirectoryHandle, prefix = ""): Promise<{ file: File; path: string }[]> {
  const out: { file: File; path: string }[] = [];
  for await (const [name, handle] of (dir as unknown as { entries(): AsyncIterable<[string, FileSystemHandle]> }).entries()) {
    if (name.startsWith(".")) continue;
    const path = prefix ? `${prefix}/${name}` : name;
    if (handle.kind === "directory") out.push(...(await walkDirectory(handle as FileSystemDirectoryHandle, path)));
    else if (AUDIO_EXT.includes(ext(name)) || ext(name) === "lrc") out.push({ file: await (handle as FileSystemFileHandle).getFile(), path });
  }
  return out;
}

/** The bytes of a track, wherever they are stored; null when the file must be re-added (session files after a reload). */
export async function fileFor(t: Track): Promise<File | null> {
  if (t.id != null && sessionFiles.has(t.id)) return sessionFiles.get(t.id)!;
  if (t.storage === "opfs" && t.opfsName) {
    const dir = await navigator.storage.getDirectory();
    return (await dir.getFileHandle(t.opfsName)).getFile();
  }
  if (t.storage === "handle" && t.handleId != null) {
    const h = await db.handles.get(t.handleId);
    if (!h) return null;
    const perm = h.handle as unknown as { queryPermission?: (o: { mode: string }) => Promise<string> };
    if (perm.queryPermission && (await perm.queryPermission({ mode: "read" })) !== "granted") return null;
    let cur: FileSystemDirectoryHandle = h.handle;
    const parts = t.path.split("/");
    for (const p of parts.slice(0, -1)) cur = await cur.getDirectoryHandle(p);
    return (await cur.getFileHandle(parts[parts.length - 1])).getFile();
  }
  return null;
}

export async function removeTrackData(t: Track): Promise<void> {
  if (t.opfsName) {
    try { await (await navigator.storage.getDirectory()).removeEntry(t.opfsName); } catch { /* already gone */ }
  }
  if (t.id != null) { sessionFiles.delete(t.id); await db.tracks.delete(t.id); }
}
