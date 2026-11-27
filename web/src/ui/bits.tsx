import type { ReactNode } from "react";
import type { Track } from "../lib/db";
import { CODECS } from "../lib/core/quality";
import { useObjectUrl } from "./hooks";

export function Art({ blob, src, size = 48, className = "" }: { blob?: Blob; src?: string; size?: number; className?: string }) {
  const url = useObjectUrl(blob);
  const s = url ?? src;
  return (
    <div className={`dot-bg shrink-0 overflow-hidden rounded-lg bg-grey-850 ${className}`} style={{ width: size, height: size }}>
      {s && <img src={s} alt="" className="h-full w-full object-cover" loading="lazy" onError={(e) => (e.currentTarget.style.display = "none")} />}
    </div>
  );
}

export function Pill({ children, onClick, filled = true, disabled = false, title }: { children: ReactNode; onClick?: () => void; filled?: boolean; disabled?: boolean; title?: string }) {
  return (
    <button
      title={title}
      disabled={disabled}
      onClick={onClick}
      className={`rounded-full px-4 py-2 font-mono text-[13px] uppercase tracking-wider transition disabled:opacity-40 ${filled ? "bg-paper text-ink hover:bg-grey-400" : "border border-grey-700 text-paper hover:border-paper"}`}
    >
      {children}
    </button>
  );
}

export function Chip({ label, selected, onClick }: { label: string; selected: boolean; onClick: () => void }) {
  return (
    <button onClick={onClick} className={`flex items-center gap-1.5 whitespace-nowrap rounded-full border px-3 py-1.5 font-mono text-xs uppercase tracking-wider ${selected ? "border-paper bg-paper text-ink" : "border-grey-700 text-paper hover:border-grey-400"}`}>
      {selected && <span className="h-1.5 w-1.5 rounded-full bg-accent" />}
      {label}
    </button>
  );
}

export function SectionTitle({ children, action }: { children: ReactNode; action?: ReactNode }) {
  return (
    <div className="mt-6 mb-2 flex items-center gap-2.5">
      <span className="h-1.5 w-1.5 rounded-full bg-accent" />
      <span className="label flex-1 text-paper">{children}</span>
      {action}
    </div>
  );
}

/** Lossless badge only for lossless codecs; lossy files are never labelled lossless. */
export function Badge({ t }: { t: Track }) {
  if (!CODECS[t.codec].lossless) return null;
  const hiRes = (t.bitDepth ?? 0) > 16 || (t.sampleRate ?? 0) > 48000;
  return <span className="rounded border border-grey-700 px-1 font-mono text-[10px] text-paper">{CODECS[t.codec].label}{hiRes ? " HI-RES" : ""}</span>;
}

export function Card({ children, className = "" }: { children: ReactNode; className?: string }) {
  return <div className={`rounded-2xl border border-grey-800 bg-grey-900 p-4 ${className}`}>{children}</div>;
}
