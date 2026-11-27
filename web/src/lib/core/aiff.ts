// AIFF/AIFF-C → WAV for browsers that can't play AIFF (Chrome, Firefox). Header rules mirror
// android/core AiffParser.kt; the PCM is rewritten little-endian into a standard WAV container.
export type AiffFormat = "S8" | "U8" | "S16_BE" | "S16_LE" | "S24_BE" | "S24_LE" | "S32_BE" | "S32_LE" | "F32_BE" | "F64_BE";
export interface AiffHeader { isAifc: boolean; channels: number; sampleFrames: number; bitsPerSample: number; sampleRate: number; compression: string; format: AiffFormat; dataOffset: number; dataSize: number }

const BYTES: Record<AiffFormat, number> = { S8: 1, U8: 1, S16_BE: 2, S16_LE: 2, S24_BE: 3, S24_LE: 3, S32_BE: 4, S32_LE: 4, F32_BE: 4, F64_BE: 8 };

const fourCc = (v: DataView, o: number) => String.fromCharCode(v.getUint8(o), v.getUint8(o + 1), v.getUint8(o + 2), v.getUint8(o + 3));

export function isAiff(bytes: Uint8Array): boolean {
  if (bytes.length < 12) return false;
  const v = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  return fourCc(v, 0) === "FORM" && ["AIFF", "AIFC"].includes(fourCc(v, 8));
}

/** IEEE 754 80-bit extended (big-endian) → number. */
export function extendedToNumber(v: DataView, o: number): number {
  const se = v.getUint16(o);
  const negative = (se & 0x8000) !== 0;
  const exponent = se & 0x7fff;
  const hi = v.getUint32(o + 2);
  const lo = v.getUint32(o + 6);
  if (exponent === 0 && hi === 0 && lo === 0) return 0;
  if (exponent === 0x7fff) return NaN;
  const mantissa = hi * 2 ** 32 + lo;
  const value = mantissa * 2 ** (exponent - 16383 - 63);
  return negative ? -value : value;
}

function formatFor(bits: number, compression: string): AiffFormat {
  const bytes = Math.floor((bits + 7) / 8);
  const be: AiffFormat[] = ["S8", "S16_BE", "S24_BE", "S32_BE"];
  const le: AiffFormat[] = ["S8", "S16_LE", "S24_LE", "S32_LE"];
  switch (compression) {
    case "NONE": case "twos": if (bytes >= 1 && bytes <= 4) return be[bytes - 1]; break;
    case "sowt": if (bytes >= 1 && bytes <= 4) return le[bytes - 1]; break;
    case "in24": return "S24_BE";
    case "in32": return "S32_BE";
    case "42ni": return "S24_LE";
    case "23ni": return "S32_LE";
    case "raw ": return "U8";
    case "fl32": case "FL32": return "F32_BE";
    case "fl64": case "FL64": return "F64_BE";
    default: throw new Error(`Compressed AIFF-C (${compression.trim()}) is not supported`);
  }
  throw new Error(`Unsupported PCM sample size ${bits}`);
}

export function parseAiffHeader(bytes: Uint8Array): AiffHeader {
  if (!isAiff(bytes)) throw new Error("Not an AIFF file");
  const v = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  const isAifc = fourCc(v, 8) === "AIFC";
  let pos = 12;
  let comm: { channels: number; frames: number; bits: number; rate: number; compression: string } | null = null;
  let ssndOffset: number | null = null;
  let ssndSize: number | null = null;
  while ((comm == null || ssndOffset == null) && pos + 8 <= bytes.length) {
    const id = fourCc(v, pos);
    const size = v.getUint32(pos + 4);
    const padded = size + (size & 1);
    const body = pos + 8;
    if (id === "COMM") {
      if (size < 18 || size > 4096) throw new Error(`Bad COMM chunk size ${size}`);
      const channels = v.getInt16(body);
      const frames = v.getUint32(body + 2);
      const bits = v.getInt16(body + 6);
      const rate = extendedToNumber(v, body + 8);
      const compression = isAifc && size >= 22 ? fourCc(v, body + 18) : "NONE";
      if (channels < 1 || channels > 32) throw new Error(`Unsupported channel count ${channels}`);
      if (bits < 1 || bits > 64) throw new Error(`Unsupported sample size ${bits}`);
      if (!(rate > 0 && rate <= 1_536_000)) throw new Error(`Unsupported sample rate ${rate}`);
      comm = { channels, frames, bits, rate, compression };
    } else if (id === "SSND") {
      if (size < 8) throw new Error(`Bad SSND chunk size ${size}`);
      const offset = v.getUint32(body);
      ssndOffset = body + 8 + offset;
      ssndSize = size - 8 - offset;
    }
    pos = body + padded;
  }
  if (!comm) throw new Error("Missing COMM chunk");
  if (ssndOffset == null) throw new Error("Missing SSND chunk");
  const format = formatFor(comm.bits, comm.compression);
  const bpf = comm.channels * BYTES[format];
  const declared = comm.frames * bpf;
  const available = ssndSize && ssndSize > 0 ? ssndSize : declared;
  const dataSize = Math.floor(Math.min(declared, available, bytes.length - ssndOffset) / bpf) * bpf;
  return { isAifc, channels: comm.channels, sampleFrames: comm.frames, bitsPerSample: comm.bits, sampleRate: comm.rate, compression: comm.compression, format, dataOffset: ssndOffset, dataSize };
}

/** Converts a whole AIFF file to a WAV file (PCM little-endian, or IEEE float for fl32/fl64). */
export function aiffToWav(bytes: Uint8Array): Uint8Array {
  const h = parseAiffHeader(bytes);
  const bps = BYTES[h.format];
  const isFloat = h.format === "F32_BE" || h.format === "F64_BE";
  const out = new Uint8Array(44 + h.dataSize);
  const w = new DataView(out.buffer);
  const ascii = (o: number, s: string) => { for (let i = 0; i < 4; i++) out[o + i] = s.charCodeAt(i); };
  const rate = Math.round(h.sampleRate);
  ascii(0, "RIFF"); w.setUint32(4, 36 + h.dataSize, true); ascii(8, "WAVE");
  ascii(12, "fmt "); w.setUint32(16, 16, true); w.setUint16(20, isFloat ? 3 : 1, true); w.setUint16(22, h.channels, true);
  w.setUint32(24, rate, true); w.setUint32(28, rate * h.channels * bps, true); w.setUint16(32, h.channels * bps, true); w.setUint16(34, bps * 8, true);
  ascii(36, "data"); w.setUint32(40, h.dataSize, true);
  const src = bytes.subarray(h.dataOffset, h.dataOffset + h.dataSize);
  const dst = out.subarray(44);
  switch (h.format) {
    case "S8": for (let i = 0; i < src.length; i++) dst[i] = (src[i] + 128) & 0xff; break; // WAV 8-bit is unsigned
    case "U8": case "S16_LE": case "S24_LE": case "S32_LE": dst.set(src); break;
    default: for (let i = 0; i + bps <= src.length; i += bps) for (let b = 0; b < bps; b++) dst[i + b] = src[i + bps - 1 - b];
  }
  return out;
}
