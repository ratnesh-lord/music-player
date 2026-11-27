// Mirrors android/core LrcParser.kt (shared/test-vectors/lrc.json).
export interface LyricLine { timeMs: number | null; text: string }
export interface Lyrics { lines: LyricLine[]; synced: boolean; title: string | null; artist: string | null }

const TAG = /^\[([^\]]*)]/;
const TIME = /^(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?$/;
const WORD_TIME = /<\d{1,3}:\d{1,2}(?:[.:]\d{1,3})?>/g;

function parseTime(body: string): number | null {
  const m = TIME.exec(body);
  if (!m) return null;
  const min = Number(m[1]);
  const sec = Number(m[2]);
  if (sec >= 60) return null;
  const frac = m[3] ?? "";
  const fracMs = frac.length === 0 ? 0 : frac.length === 1 ? Number(frac) * 100 : frac.length === 2 ? Number(frac) * 10 : Number(frac);
  return (min * 60 + sec) * 1000 + fracMs;
}

export function parseLrc(text: string): Lyrics {
  let offsetMs = 0;
  let title: string | null = null;
  let artist: string | null = null;
  const timed: [number, string][] = [];
  const plain: string[] = [];
  for (const rawLine of text.replace(/\r\n/g, "\n").replace(/\r/g, "\n").split("\n")) {
    let rest = rawLine.trim().replace(/^﻿/, "");
    const times: number[] = [];
    let sawTag = false;
    for (;;) {
      const m = TAG.exec(rest);
      if (!m) break;
      const body = m[1].trim();
      const time = parseTime(body);
      const lower = body.toLowerCase();
      if (time != null) times.push(time);
      else if (lower.startsWith("offset:")) {
        const v = body.substring(body.indexOf(":") + 1).trim().replace(/^\+/, "");
        if (/^-?\d+$/.test(v)) offsetMs = Number(v);
      } else if (lower.startsWith("ti:")) title = body.substring(3).trim() || null;
      else if (lower.startsWith("ar:")) artist = body.substring(3).trim() || null;
      else if (!body.includes(":")) break; // e.g. "[Chorus]" in plain lyrics
      sawTag = true;
      rest = rest.substring(m[0].length);
    }
    const lyric = rest.replace(WORD_TIME, "").trim();
    if (times.length > 0) for (const t of times) timed.push([t, lyric]);
    else if (!sawTag) plain.push(rawLine.replace(/\s+$/, ""));
  }
  if (timed.length === 0) {
    let start = 0;
    let end = plain.length;
    while (start < end && plain[start].trim() === "") start++;
    while (end > start && plain[end - 1].trim() === "") end--;
    return { lines: plain.slice(start, end).map((t) => ({ timeMs: null, text: t })), synced: false, title, artist };
  }
  const lines = timed.map(([t, s]) => ({ timeMs: Math.max(0, t - offsetMs), text: s })).sort((a, b) => a.timeMs - b.timeMs);
  return { lines, synced: true, title, artist };
}

export function lineIndexAt(lyrics: Lyrics, positionMs: number): number {
  if (!lyrics.synced || lyrics.lines.length === 0) return -1;
  let lo = 0;
  let hi = lyrics.lines.length - 1;
  let ans = -1;
  while (lo <= hi) {
    const mid = (lo + hi) >>> 1;
    if ((lyrics.lines[mid].timeMs ?? 0) <= positionMs) { ans = mid; lo = mid + 1; } else hi = mid - 1;
  }
  return ans;
}
