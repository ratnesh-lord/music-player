import { useEffect, useMemo, useRef, useState } from "react";
import { db, deviceId, getSetting, setSetting, type Playlist, type Station, type Track } from "../lib/db";
import { engine, EQ_CENTERS, defaultEngineSettings, type EngineSettings } from "../lib/engine";
import { importFiles, isAudioFile, removeTrackData, walkDirectory } from "../lib/importer";
import { describe as describeQuality, formatSampleRate, tier, tierLabel, CODECS } from "../lib/core/quality";
import { lineIndexAt, parseLrc } from "../lib/core/lrc";
import { normalize } from "../lib/core/trackKey";
import { reportClick, searchStations, toggleFavoriteStation, topStations } from "../lib/radio";
import { exportBackup, importBackup, isSignedIn, signIn, signOut, signUp, syncNow } from "../lib/sync";
import { Art, Badge, Card, Chip, Pill, SectionTitle } from "./bits";
import { fmt, useEngine, useLive } from "./hooks";

type View = "library" | "radio" | "settings";
type LibTab = "songs" | "albums" | "playlists" | "favourites" | "history";

export function App() {
  const [view, setView] = useState<View>("library");
  const [query, setQuery] = useState("");
  const [toast, setToast] = useState<string | null>(null);
  const [panel, setPanel] = useState(false);
  const mobile = typeof navigator !== "undefined" && /Android|iPhone|iPad/i.test(navigator.userAgent);
  useEffect(() => { void loadEngineSettings(); }, []);
  useEffect(() => { if (toast) { const t = setTimeout(() => setToast(null), 4000); return () => clearTimeout(t); } }, [toast]);

  return (
    <div className="flex h-full flex-col">
      <header className="flex flex-wrap items-center gap-4 border-b border-grey-800 px-5 py-4">
        <h1 className="font-dot text-3xl tracking-wider">UNPAUSED</h1>
        <nav className="flex gap-2">
          {(["library", "radio", "settings"] as View[]).map((v) => <Chip key={v} label={v} selected={view === v} onClick={() => setView(v)} />)}
        </nav>
        {view === "library" && (
          <input value={query} onChange={(e) => setQuery(e.target.value)} placeholder="Search songs, albums, artists" aria-label="Search library"
            className="ml-auto w-full max-w-sm rounded-full border border-grey-700 bg-transparent px-4 py-2 text-sm outline-none focus:border-paper sm:w-80" />
        )}
      </header>
      {mobile && (
        <div className="border-b border-grey-800 px-5 py-2 text-xs text-grey-400">
          Browsers may pause audio when the tab is in the background or the screen locks. For playback that keeps going, use the Android app.
        </div>
      )}
      <main className="min-h-0 flex-1 overflow-y-auto px-5 pb-8">
        {view === "library" && <Library query={query} toast={setToast} />}
        {view === "radio" && <Radio />}
        {view === "settings" && <Settings toast={setToast} />}
      </main>
      {panel && <NowPanel onClose={() => setPanel(false)} />}
      <PlayerBar onOpen={() => setPanel((p) => !p)} />
      {toast && <div role="status" className="fixed bottom-28 left-1/2 -translate-x-1/2 rounded-full bg-paper px-4 py-2 text-sm text-ink">{toast}</div>}
    </div>
  );
}

async function loadEngineSettings() {
  engine.applySettings(await getSetting<EngineSettings>("engine", defaultEngineSettings));
}

// ---- Library ---------------------------------------------------------------------------------------

function Library({ query, toast }: { query: string; toast: (s: string) => void }) {
  const [tab, setTab] = useState<LibTab>("songs");
  const [keepCopy, setKeepCopy] = useState(true);
  const [progress, setProgress] = useState<string | null>(null);
  const [dragging, setDragging] = useState(false);
  const filesInput = useRef<HTMLInputElement>(null);
  const folderInput = useRef<HTMLInputElement>(null);
  const tracks = useLive(() => db.tracks.orderBy("addedAt").reverse().toArray(), [], [] as Track[]);
  const favs = useLive(() => db.favorites.filter((f) => !f.deleted).toArray(), [], []);
  const favKeys = useMemo(() => new Set(favs.map((f) => f.trackKey)), [favs]);
  const playlists = useLive(() => db.playlists.filter((p) => !p.deleted).toArray(), [], [] as Playlist[]);

  const needle = normalize(query);
  const shown = needle ? tracks.filter((t) => normalize(`${t.title} ${t.artist} ${t.album} ${t.genre ?? ""}`).includes(needle)) : tracks;

  async function add(entries: { file: File; path: string }[], storage: "opfs" | "session" | "handle", handleId?: number) {
    if (entries.length === 0) return;
    if (storage === "opfs") await navigator.storage.persist?.().catch(() => false);
    const r = await importFiles(entries, { storage, handleId, onProgress: (d, n) => setProgress(`Reading ${d}/${n}`) });
    setProgress(null);
    toast(`Added ${r.added}` + (r.skipped ? `, ${r.skipped} already in library` : "") + (r.failed.length ? `, ${r.failed.length} unreadable` : ""));
  }

  async function addFolder() {
    const picker = (window as unknown as { showDirectoryPicker?: () => Promise<FileSystemDirectoryHandle> }).showDirectoryPicker;
    if (picker && !keepCopy) {
      try {
        const dir = await picker();
        const id = await db.handles.add({ name: dir.name, handle: dir, addedAt: Date.now() });
        await add(await walkDirectory(dir), "handle", id);
      } catch { /* cancelled */ }
    } else folderInput.current?.click();
  }

  const onPicked = (list: FileList | null) => {
    const entries = [...(list ?? [])].map((f) => ({ file: f, path: (f as File & { webkitRelativePath?: string }).webkitRelativePath || f.name }));
    void add(entries.filter((e) => isAudioFile(e.file) || /\.lrc$/i.test(e.file.name)), keepCopy ? "opfs" : "session");
  };

  return (
    <div onDragOver={(e) => { e.preventDefault(); setDragging(true); }} onDragLeave={() => setDragging(false)}
      onDrop={(e) => { e.preventDefault(); setDragging(false); onPicked(e.dataTransfer.files); }}
      className={dragging ? "outline outline-2 outline-accent" : ""}>
      <div className="mt-5 flex flex-wrap items-center gap-3">
        <Pill onClick={() => filesInput.current?.click()}>Add files</Pill>
        <Pill filled={false} onClick={() => void addFolder()}>Add folder</Pill>
        <label className="flex items-center gap-2 text-sm text-grey-400">
          <input type="checkbox" checked={keepCopy} onChange={(e) => setKeepCopy(e.target.checked)} />
          Keep a copy in this browser (stays on this device, works offline)
        </label>
        {progress && <span className="label">{progress}</span>}
        <input ref={filesInput} type="file" multiple accept="audio/*,.flac,.aif,.aiff,.aifc,.opus,.ogg,.m4a,.lrc" hidden onChange={(e) => { onPicked(e.target.files); e.target.value = ""; }} />
        <input ref={folderInput} type="file" multiple hidden {...{ webkitdirectory: "" }} onChange={(e) => { onPicked(e.target.files); e.target.value = ""; }} />
      </div>
      <p className="mt-2 text-xs text-grey-500">Files are read in your browser and never uploaded. You can also drop files or folders here.</p>
      <div className="mt-5 flex flex-wrap gap-2">
        {(["songs", "albums", "playlists", "favourites", "history"] as LibTab[]).map((t) => <Chip key={t} label={t} selected={tab === t} onClick={() => setTab(t)} />)}
      </div>
      {tab === "songs" && <SongList tracks={shown} favKeys={favKeys} playlists={playlists} toast={toast} />}
      {tab === "albums" && <Albums tracks={shown} />}
      {tab === "playlists" && <Playlists playlists={playlists} tracks={tracks} />}
      {tab === "favourites" && <KeyList keys={favs.map((f) => ({ trackKey: f.trackKey, title: f.title, artist: f.artist }))} tracks={tracks} empty="Tap ♡ on a song to add it here." />}
      {tab === "history" && <History tracks={tracks} />}
    </div>
  );
}

function SongList({ tracks, favKeys, playlists, toast }: { tracks: Track[]; favKeys: Set<string>; playlists: Playlist[]; toast: (s: string) => void }) {
  const st = useEngine();
  if (tracks.length === 0) return <Empty title="No music yet" body="Add files or a folder. Everything stays in this browser." />;
  return (
    <>
      <div className="mt-4 flex items-center gap-3">
        <Pill onClick={() => void engine.playQueue(tracks, 0)}>Play</Pill>
        <Pill filled={false} onClick={() => void engine.playQueue(tracks, Math.floor(Math.random() * tracks.length), true)}>Shuffle</Pill>
        <span className="label ml-auto">{tracks.length} songs</span>
      </div>
      <ul className="mt-3 divide-y divide-grey-850">
        {tracks.map((t, i) => (
          <li key={t.id} className="group flex items-center gap-3 py-2">
            <button className="flex min-w-0 flex-1 items-center gap-3 text-left" onClick={() => void engine.playQueue(tracks, i)}>
              <Art blob={t.artwork} size={44} />
              <span className="min-w-0 flex-1">
                <span className={`block truncate text-sm font-medium ${engine.current?.id === t.id && !st.station ? "text-accent" : ""}`}>{t.title}</span>
                <span className="flex items-center gap-2 truncate text-xs text-grey-400"><Badge t={t} />{t.artist} · {t.album}</span>
              </span>
              <span className="label hidden sm:block">{fmt(t.durationMs)}</span>
            </button>
            <button aria-label={favKeys.has(t.trackKey) ? "Remove from favourites" : "Add to favourites"} className={favKeys.has(t.trackKey) ? "text-accent" : "text-grey-500 hover:text-paper"} onClick={() => void toggleFav(t)}>
              {favKeys.has(t.trackKey) ? "♥" : "♡"}
            </button>
            <select aria-label="More actions" className="w-8 bg-transparent text-grey-500" value="" onChange={(e) => { void songAction(e.target.value, t, playlists, toast); e.target.value = ""; }}>
              <option value="">⋮</option>
              <option value="next">Play next</option>
              <option value="queue">Add to queue</option>
              <option value="new">Add to new playlist…</option>
              {playlists.map((p) => <option key={p.id} value={`pl:${p.id}`}>Add to {p.name}</option>)}
              <option value="remove">Remove from library</option>
            </select>
          </li>
        ))}
      </ul>
    </>
  );
}

async function toggleFav(t: Track) {
  const cur = await db.favorites.get(t.trackKey);
  await db.favorites.put({ trackKey: t.trackKey, title: t.title, artist: t.artist, updatedAt: Date.now(), deleted: cur ? !cur.deleted : false, deviceId: await deviceId() });
}

async function songAction(action: string, t: Track, playlists: Playlist[], toast: (s: string) => void) {
  const item = { trackKey: t.trackKey, title: t.title, artist: t.artist, addedAt: Date.now() };
  if (action === "next") engine.addToQueue([t], true);
  else if (action === "queue") engine.addToQueue([t], false);
  else if (action === "remove") await removeTrackData(t);
  else if (action === "new") {
    const name = prompt("Playlist name")?.trim();
    if (!name) return;
    await db.playlists.put({ id: crypto.randomUUID(), name, items: [item], createdAt: Date.now(), updatedAt: Date.now(), deleted: false, deviceId: await deviceId() });
    toast(`Created “${name}”`);
  } else if (action.startsWith("pl:")) {
    const p = playlists.find((x) => x.id === action.slice(3));
    if (!p) return;
    await db.playlists.put({ ...p, items: [...p.items, item], updatedAt: Date.now(), deviceId: await deviceId() });
    toast(`Added to “${p.name}”`);
  }
}

function Albums({ tracks }: { tracks: Track[] }) {
  const albums = useMemo(() => {
    const m = new Map<string, Track[]>();
    for (const t of tracks) m.set(t.albumKey, [...(m.get(t.albumKey) ?? []), t]);
    return [...m.values()].map((list) => list.sort((a, b) => (a.discNo ?? 1) - (b.discNo ?? 1) || (a.trackNo ?? 999) - (b.trackNo ?? 999)));
  }, [tracks]);
  if (albums.length === 0) return <Empty title="No albums" body="Albums appear when you add music." />;
  return (
    <div className="mt-4 grid grid-cols-[repeat(auto-fill,minmax(150px,1fr))] gap-4">
      {albums.map((list) => (
        <button key={list[0].albumKey} className="text-left" onClick={() => void engine.playQueue(list, 0)}>
          <Art blob={list.find((t) => t.artwork)?.artwork} size={150} className="!h-auto !w-full aspect-square" />
          <div className="mt-1.5 truncate text-sm font-medium">{list[0].album}</div>
          <div className="truncate text-xs text-grey-400">{list[0].albumArtist ?? list[0].artist}</div>
        </button>
      ))}
    </div>
  );
}

function KeyList({ keys, tracks, empty }: { keys: { trackKey: string; title: string; artist: string }[]; tracks: Track[]; empty: string }) {
  const byKey = useMemo(() => new Map(tracks.map((t) => [t.trackKey, t])), [tracks]);
  const local = keys.map((k) => byKey.get(k.trackKey)).filter((t): t is Track => !!t);
  if (keys.length === 0) return <Empty title="Nothing here yet" body={empty} />;
  return (
    <>
      <div className="mt-4 flex gap-3"><Pill disabled={local.length === 0} onClick={() => void engine.playQueue(local, 0)}>Play</Pill></div>
      <ul className="mt-3 divide-y divide-grey-850">
        {keys.map((k, i) => {
          const t = byKey.get(k.trackKey);
          return (
            <li key={`${k.trackKey}-${i}`}>
              <button disabled={!t} className="flex w-full items-center gap-3 py-2 text-left disabled:opacity-50" onClick={() => t && void engine.playQueue(local, local.indexOf(t))}>
                <Art blob={t?.artwork} size={40} />
                <span className="min-w-0 flex-1">
                  <span className="block truncate text-sm">{k.title}</span>
                  <span className="block truncate text-xs text-grey-400">{k.artist}{t ? "" : " · not in this browser"}</span>
                </span>
              </button>
            </li>
          );
        })}
      </ul>
    </>
  );
}

function Playlists({ playlists, tracks }: { playlists: Playlist[]; tracks: Track[] }) {
  const [open, setOpen] = useState<string | null>(null);
  const p = playlists.find((x) => x.id === open);
  if (p) {
    return (
      <div className="mt-4">
        <button className="label" onClick={() => setOpen(null)}>← Playlists</button>
        <div className="mt-2 flex items-center gap-3">
          <h2 className="text-xl font-semibold">{p.name}</h2>
          <Pill filled={false} onClick={async () => { if (confirm(`Delete “${p.name}”?`)) { await db.playlists.put({ ...p, items: [], deleted: true, updatedAt: Date.now(), deviceId: await deviceId() }); setOpen(null); } }}>Delete</Pill>
        </div>
        <KeyList keys={p.items} tracks={tracks} empty="Add songs from the ⋮ menu." />
      </div>
    );
  }
  if (playlists.length === 0) return <Empty title="No playlists" body="Create one from any song's ⋮ menu." />;
  return (
    <ul className="mt-4 divide-y divide-grey-850">
      {playlists.map((x) => (
        <li key={x.id}><button className="flex w-full justify-between py-3 text-left" onClick={() => setOpen(x.id)}><span>{x.name}</span><span className="label">{x.items.length} songs</span></button></li>
      ))}
    </ul>
  );
}

function History({ tracks }: { tracks: Track[] }) {
  const plays = useLive(() => db.plays.orderBy("playedAt").reverse().limit(200).toArray(), [], []);
  return <KeyList keys={plays} tracks={tracks} empty="Songs you listen to for 30 seconds (or half their length) show up here." />;
}

function Empty({ title, body }: { title: string; body: string }) {
  return (
    <div className="mt-12 flex flex-col items-center gap-3 text-center">
      <div className="dot-bg h-16 w-16 rounded-xl" />
      <div className="font-medium">{title}</div>
      <div className="max-w-sm text-sm text-grey-400">{body}</div>
    </div>
  );
}

// ---- Radio -------------------------------------------------------------------------------------------

const TAGS = ["lofi", "jazz", "classical", "bollywood", "hindi", "rock", "electronic", "ambient", "news"];

function Radio() {
  const [q, setQ] = useState("");
  const [tag, setTag] = useState<string | null>(null);
  const [results, setResults] = useState<Station[]>([]);
  const [state, setState] = useState<"idle" | "loading" | "error">("idle");
  const favs = useLive(() => db.stations.where("favoritedAt").above(0).toArray(), [], [] as Station[]);
  useEffect(() => {
    const t = setTimeout(async () => {
      setState("loading");
      try { setResults(q.trim() || tag ? await searchStations(q, tag ?? undefined) : await topStations()); setState("idle"); } catch { setState("error"); }
    }, 350);
    return () => clearTimeout(t);
  }, [q, tag]);
  const play = (s: Station) => { void engine.playStation(s); void reportClick(s.stationUuid); };
  const row = (s: Station) => {
    const fav = favs.some((f) => f.stationUuid === s.stationUuid);
    return (
      <li key={s.stationUuid} className="flex items-center gap-3 py-2">
        <button className="flex min-w-0 flex-1 items-center gap-3 text-left" onClick={() => play(s)}>
          <Art src={s.faviconUrl?.startsWith("https://") ? s.faviconUrl : undefined} size={40} />
          <span className="min-w-0">
            <span className="block truncate text-sm">{s.name}</span>
            <span className="block truncate text-xs text-grey-400">{[s.codec?.toUpperCase(), s.bitrateKbps ? `${s.bitrateKbps} kbps` : null, s.countryCode, s.streamUrl.startsWith("http://") ? "HTTP only" : null].filter(Boolean).join(" · ")}</span>
          </span>
        </button>
        <button aria-label={fav ? "Remove favourite" : "Add favourite"} className={fav ? "text-accent" : "text-grey-500"} onClick={() => void toggleFavoriteStation(s)}>{fav ? "♥" : "♡"}</button>
      </li>
    );
  };
  return (
    <div>
      <input value={q} onChange={(e) => setQ(e.target.value)} placeholder="Station name" aria-label="Search stations" className="mt-5 w-full max-w-md rounded-full border border-grey-700 bg-transparent px-4 py-2 text-sm outline-none focus:border-paper" />
      <div className="mt-3 flex flex-wrap gap-2">{TAGS.map((t) => <Chip key={t} label={t} selected={tag === t} onClick={() => setTag(tag === t ? null : t)} />)}</div>
      {favs.length > 0 && !q && !tag && (<><SectionTitle>Favourite stations</SectionTitle><ul className="divide-y divide-grey-850">{favs.map(row)}</ul></>)}
      <SectionTitle>{state === "loading" ? "Searching…" : q || tag ? `${results.length} stations` : "Popular"}</SectionTitle>
      {state === "error" && <p className="text-sm text-accent">Couldn't reach the radio directory.</p>}
      <ul className="divide-y divide-grey-850">{results.map(row)}</ul>
      <p className="mt-6 text-xs text-grey-500">Stations come from the community-run Radio Browser directory and stream from each station's own server. EQ and ReplayGain don't apply to radio in the browser, because most stations don't allow cross-origin audio processing.</p>
    </div>
  );
}

// ---- Settings ------------------------------------------------------------------------------------------

const PRESETS: Record<string, number[]> = {
  Flat: EQ_CENTERS.map(() => 0), Bass: [6, 5, 4, 2, 0, 0, 0, 0, 0, 0], Vocal: [-2, -1, 0, 1, 3, 3, 2, 1, 0, -1],
  Treble: [0, 0, 0, 0, 0, 1, 2, 4, 5, 6], Loudness: [5, 4, 2, 0, -1, 0, 0, 2, 4, 5],
};

function Settings({ toast }: { toast: (s: string) => void }) {
  const [s, setS] = useState<EngineSettings>(engine.settings);
  const save = (next: EngineSettings) => { setS(next); engine.applySettings(next); void setSetting("engine", next); };
  const [url, setUrl] = useState(""), [key, setKey] = useState(""), [email, setEmail] = useState(""), [password, setPassword] = useState("");
  const [signedIn, setSignedIn] = useState(false), [syncLibrary, setSyncLibrary] = useState(false), [busy, setBusy] = useState(false);
  const [usage, setUsage] = useState<string>("");
  useEffect(() => { void (async () => {
    setUrl(await getSetting("supabaseUrl", "")); setKey(await getSetting("supabaseKey", "")); setEmail(await getSetting("syncEmail", ""));
    setSignedIn(await isSignedIn()); setSyncLibrary(await getSetting("syncLibrary", false));
    const est = await navigator.storage?.estimate?.();
    if (est?.usage != null) setUsage(`${(est.usage / 1048576).toFixed(0)} MB used of about ${((est.quota ?? 0) / 1073741824).toFixed(1)} GB available to this site`);
  })(); }, []);
  const saveConfig = async () => { await setSetting("supabaseUrl", url.trim()); await setSetting("supabaseKey", key.trim()); await setSetting("syncEmail", email.trim()); };
  const run = async (f: () => Promise<string | void>) => { setBusy(true); try { const m = await f(); if (m) toast(m); } catch (e) { toast((e as Error).message); } finally { setBusy(false); setSignedIn(await isSignedIn()); } };
  const fileInput = useRef<HTMLInputElement>(null);

  return (
    <div className="max-w-2xl">
      <SectionTitle>ReplayGain</SectionTitle>
      <div className="flex gap-2">{(["OFF", "TRACK", "ALBUM"] as const).map((m) => <Chip key={m} label={m} selected={s.rg.mode === m} onClick={() => save({ ...s, rg: { ...s.rg, mode: m } })} />)}</div>
      <Range label="Pre-amp (tagged files)" value={s.rg.preampDb} min={-12} max={12} step={0.5} unit="dB" onChange={(v) => save({ ...s, rg: { ...s.rg, preampDb: v } })} />
      <Range label="Files without tags" value={s.rg.untaggedPreampDb} min={-12} max={12} step={0.5} unit="dB" onChange={(v) => save({ ...s, rg: { ...s.rg, untaggedPreampDb: v } })} />
      <label className="mt-2 flex items-center gap-2 text-sm"><input type="checkbox" checked={s.rg.preventClipping} onChange={(e) => save({ ...s, rg: { ...s.rg, preventClipping: e.target.checked } })} />Prevent clipping</label>

      <SectionTitle>Equaliser</SectionTitle>
      <label className="flex items-center gap-2 text-sm"><input type="checkbox" checked={s.eqEnabled} onChange={(e) => save({ ...s, eqEnabled: e.target.checked })} />Enabled</label>
      <div className="mt-3 flex flex-wrap gap-2">{Object.entries(PRESETS).map(([n, b]) => <Chip key={n} label={n} selected={JSON.stringify(s.eqBands) === JSON.stringify(b)} onClick={() => save({ ...s, eqEnabled: true, eqBands: b })} />)}</div>
      <Range label="Bass" value={s.bassDb} min={-12} max={12} step={1} unit="dB" onChange={(v) => save({ ...s, bassDb: v })} />
      <Range label="Treble" value={s.trebleDb} min={-12} max={12} step={1} unit="dB" onChange={(v) => save({ ...s, trebleDb: v })} />
      {EQ_CENTERS.map((hz, i) => (
        <Range key={hz} label={hz >= 1000 ? `${hz / 1000} kHz` : `${hz} Hz`} value={s.eqBands[i]} min={-12} max={12} step={1} unit="dB" onChange={(v) => save({ ...s, eqBands: s.eqBands.map((x, j) => (j === i ? v : x)) })} />
      ))}

      <SectionTitle>Backup file (no account)</SectionTitle>
      <p className="text-sm text-grey-400">Playlists, favourites and history as a JSON file. Works with the Android app's backup too.</p>
      <div className="mt-3 flex gap-3">
        <Pill onClick={() => void run(async () => { const blob = new Blob([await exportBackup()], { type: "application/json" }); const a = document.createElement("a"); a.href = URL.createObjectURL(blob); a.download = "unpaused-backup.json"; a.click(); URL.revokeObjectURL(a.href); })}>Export</Pill>
        <Pill filled={false} onClick={() => fileInput.current?.click()}>Import</Pill>
        <input ref={fileInput} type="file" accept="application/json,.json" hidden onChange={(e) => { const f = e.target.files?.[0]; e.target.value = ""; if (f) void run(async () => `Imported ${await importBackup(await f.text())} items`); }} />
      </div>

      <SectionTitle>Sync with your own Supabase project</SectionTitle>
      <p className="text-sm text-grey-400">Optional and free. Set up the project with backend/supabase (see docs/SETUP.md). Only playlists, favourites and history are synced; audio files never leave your device.</p>
      <div className="mt-3 grid gap-2">
        <input className="rounded-lg border border-grey-700 bg-transparent px-3 py-2 text-sm" placeholder="https://xyz.supabase.co" value={url} onChange={(e) => setUrl(e.target.value)} aria-label="Supabase URL" />
        <input className="rounded-lg border border-grey-700 bg-transparent px-3 py-2 text-sm" placeholder="sb_publishable_…" value={key} onChange={(e) => setKey(e.target.value)} aria-label="Publishable key" />
        <input className="rounded-lg border border-grey-700 bg-transparent px-3 py-2 text-sm" placeholder="Email" value={email} onChange={(e) => setEmail(e.target.value)} aria-label="Email" />
        {!signedIn && <input type="password" className="rounded-lg border border-grey-700 bg-transparent px-3 py-2 text-sm" placeholder="Password" value={password} onChange={(e) => setPassword(e.target.value)} aria-label="Password" />}
      </div>
      <label className="mt-2 flex items-center gap-2 text-sm"><input type="checkbox" checked={syncLibrary} onChange={(e) => { setSyncLibrary(e.target.checked); void setSetting("syncLibrary", e.target.checked); }} />Also sync library metadata (titles and artists, never files)</label>
      <div className="mt-3 flex flex-wrap gap-3">
        {signedIn ? (
          <>
            <Pill disabled={busy} onClick={() => void run(async () => { await saveConfig(); const r = await syncNow(syncLibrary); return `Synced: ${r.pulled} received, ${r.pushed} sent`; })}>Sync now</Pill>
            <Pill filled={false} onClick={() => void run(async () => { await signOut(); return "Signed out"; })}>Sign out</Pill>
          </>
        ) : (
          <>
            <Pill disabled={busy} onClick={() => void run(async () => { await saveConfig(); await signIn(email, password); setPassword(""); return "Signed in"; })}>Sign in</Pill>
            <Pill filled={false} disabled={busy} onClick={() => void run(async () => { await saveConfig(); return signUp(email, password); })}>Create account</Pill>
          </>
        )}
      </div>

      <SectionTitle>Storage</SectionTitle>
      <p className="text-sm text-grey-400">{usage || "Storage use isn't reported by this browser."}</p>
      <div className="mt-3"><Pill filled={false} onClick={() => void run(async () => { if (!confirm("Remove every song from this browser? Playlists and favourites are kept.")) return; for (const t of await db.tracks.toArray()) await removeTrackData(t); return "Library cleared"; })}>Clear library</Pill></div>

      <SectionTitle>About</SectionTitle>
      <p className="text-sm text-grey-400">
        Unpaused web companion. Free software (GPL-3.0-or-later), source on GitHub. Fonts: Geist, Geist Mono and Doto (SIL Open Font License).
        A web app can't keep playing as reliably as the Android app: browsers may suspend background tabs, and they decode and resample audio to the output rate, so playback isn't bit-perfect.
      </p>
    </div>
  );
}

function Range({ label, value, min, max, step, unit, onChange }: { label: string; value: number; min: number; max: number; step: number; unit: string; onChange: (v: number) => void }) {
  return (
    <label className="mt-3 block">
      <span className="flex justify-between text-sm"><span>{label}</span><span className="label">{value > 0 ? "+" : ""}{value} {unit}</span></span>
      <input type="range" className="w-full" min={min} max={max} step={step} value={value} onChange={(e) => onChange(Number(e.target.value))} />
    </label>
  );
}

// ---- Player ----------------------------------------------------------------------------------------------

function PlayerBar({ onOpen }: { onOpen: () => void }) {
  const st = useEngine();
  const t = engine.current;
  const station = st.station;
  if (!t && !station) return null;
  return (
    <footer className="relative z-20 border-t border-grey-800 bg-ink px-4 pb-[max(env(safe-area-inset-bottom),12px)] pt-3">
      <DotSeek position={st.positionMs} duration={st.durationMs} disabled={!!station} />
      <div className="mt-2 flex items-center gap-3">
        <button className="flex min-w-0 flex-1 items-center gap-3 text-left" onClick={onOpen} aria-label="Open player details">
          <Art blob={station ? undefined : t?.artwork} src={station?.faviconUrl?.startsWith("https://") ? station.faviconUrl : undefined} size={48} />
          <span className="min-w-0">
            <span className="block truncate text-sm font-medium">{station?.name ?? t?.title}</span>
            <span className="block truncate text-xs text-grey-400">{station ? "Live radio" : `${t?.artist} · ${fmt(st.positionMs)} / ${fmt(st.durationMs)}`}</span>
          </span>
        </button>
        {!station && <button aria-label="Previous" className="px-2 text-xl" onClick={() => void engine.previous()}>⏮</button>}
        <button aria-label={st.playing ? "Pause" : "Play"} className="flex h-12 w-12 items-center justify-center rounded-full bg-paper text-xl text-ink" onClick={() => engine.toggle()}>{st.playing ? "❚❚" : "▶"}</button>
        {!station && <button aria-label="Next" className="px-2 text-xl" onClick={() => void engine.next()}>⏭</button>}
        {!station && <button aria-label="Shuffle" className={`hidden px-2 sm:block ${st.shuffle ? "text-accent" : "text-grey-400"}`} onClick={() => engine.setShuffle(!st.shuffle)}>⤨</button>}
        {!station && <button aria-label={`Repeat ${st.repeat}`} className={`hidden px-2 font-mono text-xs uppercase sm:block ${st.repeat !== "off" ? "text-accent" : "text-grey-400"}`} onClick={() => engine.setRepeat(st.repeat === "off" ? "all" : st.repeat === "all" ? "one" : "off")}>↻{st.repeat === "one" ? "1" : ""}</button>}
      </div>
      {st.error && <p role="alert" className="mt-2 text-xs text-accent">{st.error}</p>}
    </footer>
  );
}

function DotSeek({ position, duration, disabled }: { position: number; duration: number; disabled: boolean }) {
  const ref = useRef<HTMLDivElement>(null);
  const dots = 64;
  const head = duration > 0 ? Math.round((position / duration) * (dots - 1)) : -1;
  return (
    <div ref={ref} role="slider" aria-label="Seek" aria-valuemin={0} aria-valuemax={Math.round(duration / 1000)} aria-valuenow={Math.round(position / 1000)} tabIndex={0}
      className={`flex h-4 items-center justify-between ${disabled ? "" : "cursor-pointer"}`}
      onKeyDown={(e) => { if (e.key === "ArrowRight") engine.seek(position + 5000); if (e.key === "ArrowLeft") engine.seek(Math.max(0, position - 5000)); }}
      onClick={(e) => { if (disabled || !ref.current || duration <= 0) return; const r = ref.current.getBoundingClientRect(); engine.seek(((e.clientX - r.left) / r.width) * duration); }}>
      {Array.from({ length: dots }, (_, i) => (
        <span key={i} className={`rounded-full ${i === head ? "h-2.5 w-2.5 bg-accent" : `h-1 w-1 ${i < head ? "bg-paper" : "bg-grey-700"}`}`} />
      ))}
    </div>
  );
}

function NowPanel({ onClose }: { onClose: () => void }) {
  const st = useEngine();
  const [tab, setTab] = useState<"queue" | "lyrics" | "audio">("queue");
  const t = engine.current;
  const lyrics = useMemo(() => (t?.lyrics ? parseLrc(t.lyrics) : null), [t?.id, t?.lyrics]);
  const active = lyrics ? lineIndexAt(lyrics, st.positionMs) : -1;
  return (
    <aside className="fixed inset-x-0 bottom-[112px] top-16 z-10 overflow-y-auto border-t border-grey-800 bg-ink/95 p-5 backdrop-blur sm:left-auto sm:w-[420px] sm:border-l">
      <div className="flex items-center gap-2">
        {(["queue", "lyrics", "audio"] as const).map((x) => <Chip key={x} label={x} selected={tab === x} onClick={() => setTab(x)} />)}
        <button className="label ml-auto" onClick={onClose}>Close</button>
      </div>
      {tab === "queue" && (
        <ol className="mt-4 divide-y divide-grey-850">
          {engine.playOrder.map((q, i) => (
            <li key={`${q.id}-${i}`}><button className={`w-full truncate py-2 text-left text-sm ${i === st.index ? "text-accent" : ""}`} onClick={() => void engine.skipTo(i)}>{q.title} <span className="text-grey-500">· {q.artist}</span></button></li>
          ))}
        </ol>
      )}
      {tab === "lyrics" && (
        <div className="mt-4 space-y-2">
          {!lyrics || lyrics.lines.length === 0 ? <p className="text-sm text-grey-400">No lyrics in this file. Unpaused reads embedded lyrics and .lrc files added together with the song.</p>
            : lyrics.lines.map((l, i) => (
              <p key={i} onClick={() => l.timeMs != null && engine.seek(l.timeMs)} className={`${l.timeMs != null ? "cursor-pointer" : ""} ${!lyrics.synced || i === active ? "text-paper" : "text-grey-500"} ${i === active ? "text-lg font-medium" : ""}`}>{l.text || "♪"}</p>
            ))}
        </div>
      )}
      {tab === "audio" && <AudioInfo />}
    </aside>
  );
}

function AudioInfo() {
  const st = useEngine();
  const t = engine.current;
  if (st.station) return <Card className="mt-4"><p className="text-sm">{st.station.name}: {[st.station.codec, st.station.bitrateKbps ? `${st.station.bitrateKbps} kbps` : null].filter(Boolean).join(" · ") || "quality not reported"} (as reported by the station).</p></Card>;
  if (!t) return null;
  const q = { codec: t.codec, sampleRateHz: t.sampleRate, bitDepth: t.bitDepth, channels: t.channels, bitrateBps: t.bitrate };
  return (
    <div className="mt-4 space-y-3">
      <Card>
        <div className="label text-paper">Source file</div>
        <p className="mt-2 font-mono text-sm text-accent">{describeQuality(q)}</p>
        <p className="mt-1 text-sm">{tierLabel(tier(q))}{CODECS[t.codec].lossless ? "" : " (lossy codec)"} · {(t.sizeBytes / 1048576).toFixed(1)} MB</p>
      </Card>
      <Card>
        <div className="label text-paper">Output</div>
        <p className="mt-2 text-sm">Browser audio output: {st.outputRate ? formatSampleRate(st.outputRate) : "starts with playback"}. The browser decodes to floating point and resamples to this rate, then the operating system mixes it. Not bit-perfect.</p>
      </Card>
      <Card>
        <div className="label text-paper">Processing</div>
        <p className="mt-2 text-sm">ReplayGain: {st.gain.source === "OFF" ? "off" : `${st.gain.totalDb > 0 ? "+" : ""}${st.gain.totalDb} dB (${st.gain.source.toLowerCase()})`} · EQ: {engine.settings.eqEnabled ? "on" : "off"} · limiter at −1 dBFS</p>
      </Card>
    </div>
  );
}
