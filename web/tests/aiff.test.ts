import { describe, expect, it } from "vitest";
import { aiffToWav, extendedToNumber, parseAiffHeader } from "../src/lib/core/aiff";

/** 80-bit extended encoding of a positive integer sample rate. */
function extended(rate: number): Uint8Array {
  const out = new Uint8Array(10);
  const exp = Math.floor(Math.log2(rate));
  const v = new DataView(out.buffer);
  v.setUint16(0, 16383 + exp);
  const mantissa = BigInt(rate) << BigInt(63 - exp);
  v.setUint32(2, Number((mantissa >> 32n) & 0xffffffffn));
  v.setUint32(6, Number(mantissa & 0xffffffffn));
  return out;
}

function aiff(opts: { aifc?: boolean; compression?: string; bits: number; channels: number; rate: number; data: number[] }): Uint8Array {
  const parts: number[] = [];
  const u32 = (n: number) => parts.push((n >>> 24) & 255, (n >>> 16) & 255, (n >>> 8) & 255, n & 255);
  const s16 = (n: number) => parts.push((n >> 8) & 255, n & 255);
  const str = (s: string) => { for (const ch of s) parts.push(ch.charCodeAt(0)); };
  const bpf = opts.channels * Math.ceil(opts.bits / 8);
  const commSize = opts.aifc ? 24 : 18;
  str("FORM"); u32(0); str(opts.aifc ? "AIFC" : "AIFF");
  str("COMM"); u32(commSize); s16(opts.channels); u32(opts.data.length / bpf); s16(opts.bits); parts.push(...extended(opts.rate));
  if (opts.aifc) { str(opts.compression ?? "NONE"); parts.push(0, 0); }
  str("SSND"); u32(8 + opts.data.length); u32(0); u32(0); parts.push(...opts.data);
  const out = new Uint8Array(parts);
  new DataView(out.buffer).setUint32(4, out.length - 8);
  return out;
}

describe("aiff", () => {
  it("reads 80-bit sample rates", () => {
    for (const r of [8000, 44100, 48000, 96000, 192000]) expect(extendedToNumber(new DataView(extended(r).buffer), 0)).toBe(r);
  });

  it("converts 16-bit big-endian to little-endian WAV", () => {
    const wav = aiffToWav(aiff({ bits: 16, channels: 2, rate: 44100, data: [0x12, 0x34, 0xab, 0xcd] }));
    const v = new DataView(wav.buffer);
    expect(String.fromCharCode(...wav.subarray(0, 4))).toBe("RIFF");
    expect(v.getUint16(20, true)).toBe(1);
    expect(v.getUint32(24, true)).toBe(44100);
    expect(v.getUint16(34, true)).toBe(16);
    expect([...wav.subarray(44)]).toEqual([0x34, 0x12, 0xcd, 0xab]);
  });

  it("converts signed 8-bit to unsigned and keeps sowt little-endian data", () => {
    expect([...aiffToWav(aiff({ bits: 8, channels: 1, rate: 8000, data: [0x80, 0x00, 0x7f] })).subarray(44)]).toEqual([0x00, 0x80, 0xff]);
    const sowt = aiff({ aifc: true, compression: "sowt", bits: 24, channels: 1, rate: 96000, data: [1, 2, 3] });
    expect(parseAiffHeader(sowt).format).toBe("S24_LE");
    expect([...aiffToWav(sowt).subarray(44)]).toEqual([1, 2, 3]);
  });

  it("marks float AIFF-C as IEEE float WAV and rejects compressed AIFF-C", () => {
    const fl = aiffToWav(aiff({ aifc: true, compression: "fl32", bits: 32, channels: 1, rate: 48000, data: [0x3f, 0x80, 0, 0] }));
    expect(new DataView(fl.buffer).getUint16(20, true)).toBe(3);
    expect([...fl.subarray(44)]).toEqual([0, 0, 0x80, 0x3f]);
    expect(() => parseAiffHeader(aiff({ aifc: true, compression: "ima4", bits: 16, channels: 1, rate: 8000, data: [0, 0] }))).toThrow(/not supported/);
  });
});
