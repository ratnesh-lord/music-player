// Runs the same JSON vectors as android/core SharedVectorsTest.kt, so both apps behave identically.
import { readFileSync } from "node:fs";
import { describe as suite, expect, it } from "vitest";
import { sortKey, trackKey } from "../src/lib/core/trackKey";
import { decide, fromTags, type ReplayGainSettings } from "../src/lib/core/replayGain";
import { codecFor, describe, tier } from "../src/lib/core/quality";
import { lineIndexAt, parseLrc } from "../src/lib/core/lrc";
import { plan } from "../src/lib/core/merge";

// eslint-disable-next-line @typescript-eslint/no-explicit-any
const load = (name: string): any => JSON.parse(readFileSync(new URL(`../../shared/test-vectors/${name}`, import.meta.url), "utf8"));

suite("track keys", () => {
  const v = load("track-keys.json");
  for (const c of v.keys) it(`key ${JSON.stringify(c.title)}`, () => expect(trackKey(c.title, c.artist, c.album, c.durationMs)).toBe(c.key));
  for (const c of v.sortKeys) it(`sort ${c.value}`, () => expect(sortKey(c.value)).toBe(c.sortKey));
});

suite("replaygain", () => {
  for (const c of load("replaygain.json").cases) {
    it(c.name, () => {
      const d = decide(fromTags(c.tags), c.settings as ReplayGainSettings);
      expect(d.totalDb).toBeCloseTo(c.expect.totalDb, 2);
      expect(d.boostDb).toBeCloseTo(c.expect.boostDb, 2);
      expect(Math.round(d.attenuation * 10000) / 10000).toBeCloseTo(c.expect.attenuation, 4);
      expect(d.source).toBe(c.expect.source);
    });
  }
});

suite("quality", () => {
  for (const c of load("quality.json").cases) {
    it(`${c.sampleMime ?? "?"} ${c.fileName}`, () => {
      const codec = codecFor(c.sampleMime, c.containerMime, c.fileName);
      expect(codec).toBe(c.codec);
      const q = { codec, sampleRateHz: c.sampleRateHz ?? undefined, bitDepth: c.bitDepth ?? undefined, channels: c.channels ?? undefined, bitrateBps: c.bitrateBps ?? undefined };
      expect(tier(q)).toBe(c.tier);
      expect(describe(q)).toBe(c.describe);
    });
  }
});

suite("lrc", () => {
  for (const c of load("lrc.json").cases) {
    it(c.name, () => {
      const l = parseLrc(c.text);
      expect(l.synced).toBe(c.synced);
      expect(l.title).toBe(c.title);
      expect(l.artist).toBe(c.artist);
      expect(l.lines.map((x) => [x.timeMs, x.text])).toEqual(c.lines);
      for (const [pos, idx] of c.indexAt) expect(lineIndexAt(l, pos)).toBe(idx);
    });
  }
});

suite("sync merge", () => {
  for (const c of load("sync-merge.json").cases) it(c.name, () => expect(plan(c.local, c.remote)).toEqual({ pull: c.pull, push: c.push }));
});
