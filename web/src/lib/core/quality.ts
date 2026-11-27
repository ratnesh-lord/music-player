// Mirrors android/core AudioQuality.kt (shared/test-vectors/quality.json).
export const CODECS = {
  FLAC: { label: "FLAC", lossless: true },
  ALAC: { label: "ALAC", lossless: true },
  WAV: { label: "WAV", lossless: true },
  AIFF: { label: "AIFF", lossless: true },
  MP3: { label: "MP3", lossless: false },
  AAC: { label: "AAC", lossless: false },
  VORBIS: { label: "Vorbis", lossless: false },
  OPUS: { label: "Opus", lossless: false },
  AC3: { label: "AC-3", lossless: false },
  EAC3: { label: "E-AC-3", lossless: false },
  UNKNOWN: { label: "Unknown", lossless: false },
} as const;
export type Codec = keyof typeof CODECS;
export type Tier = "LOSSY" | "LOSSLESS" | "HI_RES_LOSSLESS" | "UNKNOWN";
export interface SourceQuality { codec: Codec; sampleRateHz?: number; bitDepth?: number; channels?: number; bitrateBps?: number }

const AIFF_EXT = ["aif", "aiff", "aifc"];

export function codecFor(sampleMime?: string | null, containerMime?: string | null, fileName?: string | null): Codec {
  const ext = fileName && fileName.includes(".") ? fileName.substring(fileName.lastIndexOf(".") + 1).toLowerCase() : "";
  switch (sampleMime?.toLowerCase()) {
    case "audio/flac": case "audio/x-flac": return "FLAC";
    case "audio/alac": return "ALAC";
    case "audio/mpeg": case "audio/mpeg-l2": case "audio/mpeg-l1": case "audio/mp3": return "MP3";
    case "audio/mp4a-latm": case "audio/aac": case "audio/mp4": case "audio/x-m4a": return "AAC";
    case "audio/vorbis": return "VORBIS";
    case "audio/opus": return "OPUS";
    case "audio/ac3": return "AC3";
    case "audio/eac3": case "audio/eac3-joc": return "EAC3";
    case "audio/raw": case "audio/wav": case "audio/x-wav": case "audio/aiff": case "audio/x-aiff": {
      const c = containerMime?.toLowerCase() ?? "";
      return c.includes("aiff") || AIFF_EXT.includes(ext) ? "AIFF" : "WAV";
    }
  }
  switch (ext) {
    case "flac": return "FLAC";
    case "wav": case "wave": return "WAV";
    case "aif": case "aiff": case "aifc": return "AIFF";
    case "mp3": return "MP3";
    case "aac": return "AAC";
    case "ogg": case "oga": return "VORBIS";
    case "opus": return "OPUS";
    default: return "UNKNOWN"; // .m4a can be AAC or ALAC: don't guess
  }
}

/** Maps music-metadata's format.codec / container strings onto the shared codec set. */
export function codecFromMetadata(codec?: string, container?: string, fileName?: string): Codec {
  const c = (codec ?? "").toLowerCase();
  if (c.includes("flac")) return "FLAC";
  if (c.includes("alac") || c.includes("apple lossless")) return "ALAC";
  if (c.includes("mpeg") && c.includes("layer 3")) return "MP3";
  if (c.includes("mp3")) return "MP3";
  if (c.includes("aac") || c.includes("mp4a")) return "AAC";
  if (c.includes("opus")) return "OPUS";
  if (c.includes("vorbis")) return "VORBIS";
  if (c.includes("pcm") || c.includes("lpcm") || c.includes("float")) {
    const k = (container ?? "").toLowerCase();
    return k.includes("aif") ? "AIFF" : k.includes("wav") ? "WAV" : codecFor("audio/raw", container, fileName);
  }
  return codecFor(null, container, fileName);
}

export function tier(q: SourceQuality): Tier {
  if (q.codec === "UNKNOWN") return "UNKNOWN";
  if (!CODECS[q.codec].lossless) return "LOSSY";
  return (q.bitDepth ?? 16) > 16 || (q.sampleRateHz ?? 44100) > 48000 ? "HI_RES_LOSSLESS" : "LOSSLESS";
}

export function tierLabel(t: Tier): string {
  return { LOSSY: "Lossy", LOSSLESS: "Lossless", HI_RES_LOSSLESS: "Hi-Res Lossless", UNKNOWN: "Unknown" }[t];
}

export function formatSampleRate(hz: number): string {
  const frac = hz % 1000;
  if (frac === 0) return `${Math.trunc(hz / 1000)} kHz`;
  return `${Math.trunc(hz / 1000)}.${String(frac).padStart(3, "0").replace(/0+$/, "")} kHz`;
}

export function formatBitrate(bps: number): string {
  return `${Math.round(bps / 1000).toString().replace(/\B(?=(\d{3})+(?!\d))/g, ",")} kbps`;
}

export function describe(q: SourceQuality): string {
  const parts: string[] = [CODECS[q.codec].label];
  if (CODECS[q.codec].lossless && q.bitDepth && q.bitDepth > 0) parts.push(`${q.bitDepth}-bit`);
  if (q.sampleRateHz && q.sampleRateHz > 0) parts.push(formatSampleRate(q.sampleRateHz));
  if (q.channels === 1) parts.push("mono");
  else if (q.channels != null && q.channels > 2) parts.push(`${q.channels} ch`);
  if (q.bitrateBps && q.bitrateBps > 0) parts.push(formatBitrate(q.bitrateBps));
  return parts.join(" · ");
}
