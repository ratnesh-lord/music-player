import { db, type Station } from "./db";

/** Radio Browser (https://api.radio-browser.info): free, keyless, community-run. Browsers can't do DNS
 * lookups, so the round-robin host is used; it forwards to a live mirror. */
const BASE = "https://all.api.radio-browser.info";

interface StationDto { stationuuid: string; name?: string; url?: string; url_resolved?: string; homepage?: string; favicon?: string; tags?: string; countrycode?: string; codec?: string; bitrate?: number }

function toStation(d: StationDto, existing?: Station): Station {
  return {
    stationUuid: d.stationuuid, name: d.name?.trim() || "Unnamed station", streamUrl: (d.url_resolved || d.url || "").trim(),
    homepage: d.homepage || undefined, faviconUrl: d.favicon || undefined, tags: d.tags || undefined, countryCode: d.countrycode || undefined,
    codec: d.codec || undefined, bitrateKbps: d.bitrate || undefined, favoritedAt: existing?.favoritedAt ?? 0, lastPlayedAt: existing?.lastPlayedAt ?? 0,
  };
}

async function query(path: string, params: Record<string, string>): Promise<Station[]> {
  const url = new URL(path, BASE);
  for (const [k, v] of Object.entries(params)) url.searchParams.set(k, v);
  const res = await fetch(url, { headers: { Accept: "application/json" } });
  if (!res.ok) throw new Error(`Radio directory: HTTP ${res.status}`);
  const list = (await res.json()) as StationDto[];
  const known = new Map((await db.stations.bulkGet(list.map((d) => d.stationuuid))).filter(Boolean).map((s) => [s!.stationUuid, s!]));
  return list.map((d) => toStation(d, known.get(d.stationuuid))).filter((s) => /^https?:\/\//.test(s.streamUrl));
}

export const topStations = () => query("/json/stations/topclick/60", { hidebroken: "true" });

export const searchStations = (name: string, tag?: string, countryCode?: string) =>
  query("/json/stations/search", {
    ...(name.trim() ? { name: name.trim() } : {}),
    ...(tag ? { tag } : {}),
    ...(countryCode ? { countrycode: countryCode } : {}),
    hidebroken: "true", order: "clickcount", reverse: "true", limit: "60",
  });

/** Counts a play, as the directory asks clients to (once per IP and station per day). */
export const reportClick = (uuid: string) => fetch(`${BASE}/json/url/${encodeURIComponent(uuid)}`).catch(() => undefined);

export async function toggleFavoriteStation(s: Station): Promise<void> {
  const cur = (await db.stations.get(s.stationUuid)) ?? s;
  await db.stations.put({ ...cur, favoritedAt: cur.favoritedAt ? 0 : Date.now() });
}
