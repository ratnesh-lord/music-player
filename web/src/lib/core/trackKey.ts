// Mirrors android/core TrackKey.kt; shared/test-vectors/track-keys.json keeps both identical.
const MARKS = /[̀-ͯ]+/g;
const NON_WORD = /[^\p{L}\p{M}\p{N}]+/gu;
const ARTICLES = ["the ", "a ", "an "];

/** Lower-case, accent-free (Latin), punctuation-free, single-spaced. */
export function normalize(value: string | null | undefined): string {
  if (value == null || value.trim() === "") return "";
  return value.normalize("NFKD").replace(MARKS, "").toLowerCase().replace(NON_WORD, " ").trim();
}

/** Durations are bucketed to 2 s so re-encodes of the same track still match. */
export function durationBucket(durationMs: number | null | undefined): number {
  return durationMs == null || durationMs <= 0 ? 0 : Math.round(durationMs / 2000);
}

export function trackKey(title: string | null | undefined, artist: string | null | undefined, album: string | null | undefined, durationMs: number | null | undefined): string {
  return [normalize(artist), normalize(album), normalize(title), String(durationBucket(durationMs))].join("|");
}

export function sortKey(value: string | null | undefined): string {
  const n = normalize(value);
  for (const a of ARTICLES) if (n.startsWith(a) && n.length > a.length) return n.substring(a.length);
  return n;
}
