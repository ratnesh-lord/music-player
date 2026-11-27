// Mirrors android/core ReplayGain.kt (shared/test-vectors/replaygain.json).
export interface ReplayGainInfo { trackGainDb?: number; trackPeak?: number; albumGainDb?: number; albumPeak?: number }
export type ReplayGainMode = "OFF" | "TRACK" | "ALBUM";
export interface ReplayGainSettings { mode: ReplayGainMode; preampDb: number; untaggedPreampDb: number; preventClipping: boolean }
export type GainSource = "OFF" | "TRACK" | "ALBUM" | "UNTAGGED";
export interface GainDecision { totalDb: number; attenuation: number; boostDb: number; source: GainSource }

export const NO_GAIN: GainDecision = { totalDb: 0, attenuation: 1, boostDb: 0, source: "OFF" };
const NUMBER = /[-+]?\d+(?:[.,]\d+)?/;

export function parseGainDb(raw: string): number | undefined {
  const m = NUMBER.exec(raw.trim());
  if (!m) return undefined;
  const v = Number(m[0].replace(",", "."));
  return Number.isFinite(v) && v >= -60 && v <= 60 ? v : undefined;
}

export function parsePeak(raw: string): number | undefined {
  const m = NUMBER.exec(raw.trim());
  if (!m) return undefined;
  const v = Number(m[0].replace(",", "."));
  return Number.isFinite(v) && v > 0 && v < 100 ? v : undefined;
}

/** Opus R128_*_GAIN: Q7.8 dB relative to -23 LUFS; ReplayGain 2 references -18 LUFS. */
export function fromR128(raw: string): number | undefined {
  const t = raw.trim();
  if (!/^[-+]?\d+$/.test(t)) return undefined;
  const q = Number(t);
  if (q < -32768 || q > 32767) return undefined;
  return q / 256 + 5;
}

export function fromTags(tags: Record<string, string>): ReplayGainInfo {
  const lower: Record<string, string> = {};
  for (const [k, v] of Object.entries(tags)) lower[k.trim().toLowerCase()] = v;
  const gain = (k: string) => (lower[k] != null ? parseGainDb(lower[k]) : undefined);
  const peak = (k: string) => (lower[k] != null ? parsePeak(lower[k]) : undefined);
  return {
    trackGainDb: gain("replaygain_track_gain") ?? (lower["r128_track_gain"] != null ? fromR128(lower["r128_track_gain"]) : undefined),
    trackPeak: peak("replaygain_track_peak"),
    albumGainDb: gain("replaygain_album_gain") ?? (lower["r128_album_gain"] != null ? fromR128(lower["r128_album_gain"]) : undefined),
    albumPeak: peak("replaygain_album_peak"),
  };
}

export const dbToLinear = (db: number) => Math.pow(10, db / 20);
export const linearToDb = (linear: number) => 20 * Math.log10(linear);

/** Kotlin's round() rounds half to even; mirror it so both apps agree to the hundredth of a dB. */
function round2(v: number): number {
  const x = v * 100;
  const r = Math.round(x);
  const tie = Math.abs(x - Math.trunc(x)) === 0.5;
  return (tie && r % 2 !== 0 ? r - 1 : r) / 100;
}

export function decide(info: ReplayGainInfo, s: ReplayGainSettings): GainDecision {
  if (s.mode === "OFF") return NO_GAIN;
  const album = s.mode === "ALBUM";
  const gain = album ? info.albumGainDb ?? info.trackGainDb : info.trackGainDb ?? info.albumGainDb;
  const peak = album ? info.albumPeak ?? info.trackPeak : info.trackPeak ?? info.albumPeak;
  const source: GainSource = gain == null ? "UNTAGGED" : album ? (info.albumGainDb != null ? "ALBUM" : "TRACK") : info.trackGainDb != null ? "TRACK" : "ALBUM";
  let total = gain == null ? s.untaggedPreampDb : gain + s.preampDb;
  if (s.preventClipping && gain != null && peak != null && peak > 0) total = Math.min(total, linearToDb(1 / peak));
  total = Math.min(24, Math.max(-60, total));
  return { totalDb: round2(total), attenuation: total < 0 ? dbToLinear(total) : 1, boostDb: round2(Math.max(total, 0)), source };
}
