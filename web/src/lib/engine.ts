import { db, deviceId, type Station, type Track } from "./db";
import { aiffToWav, isAiff } from "./core/aiff";
import { decide, type GainDecision, NO_GAIN, type ReplayGainSettings } from "./core/replayGain";
import { fileFor } from "./importer";

export const EQ_CENTERS = [31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000];

export interface EngineSettings { rg: ReplayGainSettings; eqEnabled: boolean; eqBands: number[]; bassDb: number; trebleDb: number }
export const defaultEngineSettings: EngineSettings = {
  rg: { mode: "TRACK", preampDb: 0, untaggedPreampDb: 0, preventClipping: true },
  eqEnabled: false, eqBands: EQ_CENTERS.map(() => 0), bassDb: 0, trebleDb: 0,
};

export type Repeat = "off" | "all" | "one";

export interface EngineState {
  queue: Track[];
  index: number;
  station: Station | null;
  playing: boolean;
  buffering: boolean;
  positionMs: number;
  durationMs: number;
  shuffle: boolean;
  repeat: Repeat;
  error: string | null;
  gain: GainDecision;
  outputRate: number | null;
}

interface Deck { audio: HTMLAudioElement; gain: GainNode | null; url: string | null; trackId: number | null }

/**
 * Two <audio> elements ("decks") so the next track is already buffered when the current one ends
 * (near-gapless: browsers can't guarantee sample-exact gapless with media elements). Local files go
 * through Web Audio for ReplayGain, EQ and a limiter; radio uses a plain element because most stations
 * don't send CORS headers, which Web Audio requires.
 */
export class AudioEngine {
  private decks: [Deck, Deck];
  private active = 0;
  private radio = new Audio();
  private ctx: AudioContext | null = null;
  private eqNodes: BiquadFilterNode[] = [];
  private bass: BiquadFilterNode | null = null;
  private treble: BiquadFilterNode | null = null;
  private order: number[] = [];
  private listeners = new Set<() => void>();
  private listenedMs = 0;
  private lastTick = 0;
  settings: EngineSettings = defaultEngineSettings;
  state: EngineState = { queue: [], index: -1, station: null, playing: false, buffering: false, positionMs: 0, durationMs: 0, shuffle: false, repeat: "off", error: null, gain: NO_GAIN, outputRate: null };

  constructor() {
    const mk = (): Deck => ({ audio: new Audio(), gain: null, url: null, trackId: null });
    this.decks = [mk(), mk()];
    for (const d of this.decks) {
      d.audio.preload = "auto";
      d.audio.addEventListener("timeupdate", () => this.onTime(d));
      d.audio.addEventListener("ended", () => d === this.deck && this.onEnded());
      d.audio.addEventListener("playing", () => d === this.deck && this.set({ playing: true, buffering: false, error: null }));
      d.audio.addEventListener("pause", () => d === this.deck && this.set({ playing: false }));
      d.audio.addEventListener("waiting", () => d === this.deck && this.set({ buffering: true }));
      d.audio.addEventListener("error", () => d === this.deck && this.onError());
      d.audio.addEventListener("loadedmetadata", () => d === this.deck && this.set({ durationMs: Math.round(d.audio.duration * 1000) || 0 }));
    }
    this.radio.addEventListener("playing", () => this.set({ playing: true, buffering: false, error: null }));
    this.radio.addEventListener("pause", () => this.set({ playing: false }));
    this.radio.addEventListener("waiting", () => this.set({ buffering: true }));
    this.radio.addEventListener("error", () => {
      const s = this.state.station;
      this.set({ playing: false, error: s?.streamUrl.startsWith("http://") && location.protocol === "https:" ? "This station only streams over plain HTTP, which browsers block on secure pages." : "The station's stream couldn't be played." });
    });
    this.setupMediaSession();
  }

  private get deck(): Deck { return this.decks[this.active]; }
  private get other(): Deck { return this.decks[1 - this.active]; }
  get current(): Track | null { return this.state.queue[this.order[this.state.index] ?? -1] ?? null; }

  subscribe(fn: () => void): () => void { this.listeners.add(fn); return () => this.listeners.delete(fn); }
  private set(patch: Partial<EngineState>) { this.state = { ...this.state, ...patch }; this.listeners.forEach((l) => l()); this.updateMediaSession(); }

  /** Web Audio graph, built on the first user gesture (browsers require one). */
  private ensureGraph() {
    if (this.ctx) { if (this.ctx.state === "suspended") void this.ctx.resume(); return; }
    const ctx = new AudioContext();
    this.ctx = ctx;
    this.bass = Object.assign(ctx.createBiquadFilter(), { type: "lowshelf" as BiquadFilterType });
    this.bass.frequency.value = 100;
    this.treble = Object.assign(ctx.createBiquadFilter(), { type: "highshelf" as BiquadFilterType });
    this.treble.frequency.value = 8000;
    this.eqNodes = EQ_CENTERS.map((f) => { const n = ctx.createBiquadFilter(); n.type = "peaking"; n.frequency.value = f; n.Q.value = 1.1; return n; });
    const limiter = ctx.createDynamicsCompressor();
    limiter.threshold.value = -1; limiter.knee.value = 0; limiter.ratio.value = 20; limiter.attack.value = 0.003; limiter.release.value = 0.1;
    const chain: AudioNode[] = [this.bass, ...this.eqNodes, this.treble, limiter, ctx.destination];
    for (let i = 0; i < chain.length - 1; i++) chain[i].connect(chain[i + 1]);
    for (const d of this.decks) {
      d.gain = ctx.createGain();
      ctx.createMediaElementSource(d.audio).connect(d.gain);
      d.gain.connect(this.bass);
    }
    this.set({ outputRate: ctx.sampleRate });
    this.applyEq();
  }

  applySettings(s: EngineSettings) { this.settings = s; this.applyEq(); this.applyGain(this.deck); }

  private applyEq() {
    const on = this.settings.eqEnabled;
    this.eqNodes.forEach((n, i) => (n.gain.value = on ? this.settings.eqBands[i] ?? 0 : 0));
    if (this.bass) this.bass.gain.value = on ? this.settings.bassDb : 0;
    if (this.treble) this.treble.gain.value = on ? this.settings.trebleDb : 0;
  }

  private applyGain(d: Deck) {
    const t = this.state.queue.find((q) => q.id === d.trackId);
    const g = t ? decide(t.rg, this.settings.rg) : NO_GAIN;
    // Web Audio gain can boost; the limiter after the EQ keeps boosts from clipping.
    if (d.gain) d.gain.gain.value = Math.pow(10, g.totalDb / 20);
    if (d === this.deck) this.set({ gain: g });
  }

  private async load(d: Deck, t: Track): Promise<boolean> {
    if (d.trackId === t.id && d.url) return true;
    if (d.url) URL.revokeObjectURL(d.url);
    d.url = null; d.trackId = null;
    const file = await fileFor(t);
    if (!file) return false;
    let blob: Blob = file;
    if (t.codec === "AIFF" && !d.audio.canPlayType("audio/aiff")) {
      const bytes = new Uint8Array(await file.arrayBuffer());
      if (isAiff(bytes)) blob = new Blob([aiffToWav(bytes) as BlobPart], { type: "audio/wav" });
    }
    d.url = URL.createObjectURL(blob);
    d.trackId = t.id ?? null;
    d.audio.src = d.url;
    this.applyGain(d);
    return true;
  }

  async playQueue(tracks: Track[], start = 0, shuffle = false) {
    if (tracks.length === 0) return;
    this.stopRadio();
    this.commitListen();
    const order = tracks.map((_, i) => i);
    if (shuffle) shuffleInPlace(order, start);
    this.order = order;
    this.set({ queue: tracks, index: shuffle ? 0 : start, shuffle, station: null });
    await this.startCurrent();
  }

  async playStation(s: Station) {
    this.commitListen();
    for (const d of this.decks) d.audio.pause();
    this.set({ station: s, error: null, durationMs: 0, positionMs: 0, buffering: true });
    this.radio.src = s.streamUrl;
    try { await this.radio.play(); } catch (e) { this.set({ error: (e as Error).message, buffering: false }); }
    await db.stations.put({ ...s, lastPlayedAt: Date.now() });
  }

  private stopRadio() { if (this.state.station) { this.radio.pause(); this.radio.removeAttribute("src"); this.radio.load(); } }

  private async startCurrent() {
    const t = this.current;
    if (!t) return;
    this.ensureGraph();
    this.deck.audio.pause();
    if (!(await this.load(this.deck, t))) {
      this.set({ error: `“${t.title}” isn't available in this browser session. Re-add its folder (Library → Add folder) to play it.`, playing: false });
      return;
    }
    this.lastTick = 0;
    try { await this.deck.audio.play(); } catch (e) { this.onError(e as Error); }
    void this.preloadNext();
  }

  private nextIndex(step: number): number | null {
    const n = this.order.length;
    if (n === 0) return null;
    if (this.state.repeat === "one" && step === 0) return this.state.index;
    const i = this.state.index + (step === 0 ? 1 : step);
    if (i >= 0 && i < n) return i;
    return this.state.repeat === "all" ? (i + n) % n : null;
  }

  private async preloadNext() {
    const i = this.nextIndex(0);
    const t = i == null ? null : this.state.queue[this.order[i]];
    if (t && t.id !== this.deck.trackId) await this.load(this.other, t).catch(() => false);
  }

  private async onEnded() {
    this.commitListen();
    const i = this.nextIndex(0);
    if (i == null) { this.set({ playing: false }); return; }
    const t = this.state.queue[this.order[i]];
    if (this.other.trackId === t.id && this.other.url) {
      // Pre-buffered: switch decks immediately.
      this.active = 1 - this.active;
      this.set({ index: i, positionMs: 0, durationMs: Math.round(this.deck.audio.duration * 1000) || 0 });
      this.applyGain(this.deck);
      this.deck.audio.currentTime = 0;
      try { await this.deck.audio.play(); } catch (e) { this.onError(e as Error); }
      void this.preloadNext();
    } else {
      this.set({ index: i });
      await this.startCurrent();
    }
  }

  private onError(e?: Error) {
    const t = this.current;
    const code = this.deck.audio.error?.code;
    const unsupported = code === MediaError.MEDIA_ERR_SRC_NOT_SUPPORTED || code === MediaError.MEDIA_ERR_DECODE;
    this.set({
      playing: false,
      error: unsupported && t?.codec === "ALAC" ? "This browser can't decode ALAC. Safari can, and so can the Android app."
        : unsupported ? `This browser can't play ${t?.codec ?? "this file"}.` : e?.message ?? "Playback failed.",
    });
  }

  private onTime(d: Deck) {
    if (d !== this.deck) return;
    const now = performance.now();
    if (this.lastTick && !d.audio.paused) this.listenedMs += Math.min(1000, now - this.lastTick);
    this.lastTick = now;
    this.set({ positionMs: Math.round(d.audio.currentTime * 1000) });
  }

  /** Same rule as Android: a play counts after 30 s or half the track, whichever is shorter. */
  private commitListen() {
    const t = this.current;
    const listened = this.listenedMs;
    this.listenedMs = 0; this.lastTick = 0;
    if (!t || this.state.station) return;
    const threshold = t.durationMs > 0 ? Math.min(30000, t.durationMs / 2) : 30000;
    if (listened < threshold) return;
    void deviceId().then((dev) => db.plays.put({ id: crypto.randomUUID(), trackKey: t.trackKey, title: t.title, artist: t.artist, album: t.album, playedAt: Date.now(), listenedMs: Math.round(listened), deviceId: dev, synced: 0 }));
  }

  toggle() {
    if (this.state.station) { if (this.radio.paused) void this.radio.play(); else this.radio.pause(); return; }
    this.ensureGraph();
    if (this.deck.audio.paused) { if (this.deck.url) void this.deck.audio.play(); else void this.startCurrent(); } else this.deck.audio.pause();
  }
  pause() { if (this.state.station) this.radio.pause(); else this.deck.audio.pause(); }

  async next() { const i = this.nextIndex(1); if (i != null) { this.commitListen(); this.set({ index: i }); await this.startCurrent(); } }
  async previous() {
    if (this.deck.audio.currentTime > 3) { this.deck.audio.currentTime = 0; return; }
    const i = this.nextIndex(-1);
    if (i != null) { this.commitListen(); this.set({ index: i }); await this.startCurrent(); }
  }
  async skipTo(orderIndex: number) { this.commitListen(); this.set({ index: orderIndex }); await this.startCurrent(); }
  seek(ms: number) { if (!this.state.station) this.deck.audio.currentTime = ms / 1000; }
  setRepeat(r: Repeat) { this.set({ repeat: r }); void this.preloadNext(); }
  setShuffle(on: boolean) {
    const cur = this.order[this.state.index] ?? 0;
    const order = this.state.queue.map((_, i) => i);
    if (on) shuffleInPlace(order, cur);
    this.order = order;
    this.set({ shuffle: on, index: on ? 0 : cur });
    void this.preloadNext();
  }
  /** Queue in play order. */
  get playOrder(): Track[] { return this.order.map((i) => this.state.queue[i]); }

  addToQueue(tracks: Track[], next: boolean) {
    const base = this.state.queue.length;
    const queue = [...this.state.queue, ...tracks];
    const added = tracks.map((_, i) => base + i);
    const at = next ? this.state.index + 1 : this.order.length;
    this.order.splice(at, 0, ...added);
    this.set({ queue, index: this.state.index < 0 ? 0 : this.state.index });
    void this.preloadNext();
  }

  // ---- Media Session: lock screen / hardware keys / headset buttons ----------------------------------
  private setupMediaSession() {
    if (!("mediaSession" in navigator)) return;
    const ms = navigator.mediaSession;
    ms.setActionHandler("play", () => this.toggle());
    ms.setActionHandler("pause", () => this.pause());
    ms.setActionHandler("nexttrack", () => void this.next());
    ms.setActionHandler("previoustrack", () => void this.previous());
    ms.setActionHandler("seekto", (d) => d.seekTime != null && this.seek(d.seekTime * 1000));
  }

  private artUrl: string | null = null;
  private artFor: number | null = null;

  private updateMediaSession() {
    if (!("mediaSession" in navigator)) return;
    const ms = navigator.mediaSession;
    const t = this.current;
    const s = this.state.station;
    if (s) {
      ms.metadata = new MediaMetadata({ title: s.name, artist: "Internet radio", artwork: s.faviconUrl?.startsWith("https://") ? [{ src: s.faviconUrl }] : [] });
    } else if (t) {
      if (this.artFor !== t.id) {
        if (this.artUrl) URL.revokeObjectURL(this.artUrl);
        this.artUrl = t.artwork ? URL.createObjectURL(t.artwork) : null;
        this.artFor = t.id ?? null;
        ms.metadata = new MediaMetadata({ title: t.title, artist: t.artist, album: t.album, artwork: this.artUrl ? [{ src: this.artUrl, sizes: "512x512", type: "image/jpeg" }] : [] });
      }
      if (this.state.durationMs > 0 && "setPositionState" in ms) {
        try { ms.setPositionState({ duration: this.state.durationMs / 1000, position: Math.min(this.state.positionMs, this.state.durationMs) / 1000, playbackRate: 1 }); } catch { /* ignore */ }
      }
    }
    ms.playbackState = this.state.playing ? "playing" : "paused";
  }
}

function shuffleInPlace(order: number[], first: number) {
  for (let i = order.length - 1; i > 0; i--) {
    const j = Math.floor(Math.random() * (i + 1));
    [order[i], order[j]] = [order[j], order[i]];
  }
  const at = order.indexOf(first);
  if (at > 0) [order[0], order[at]] = [order[at], order[0]];
}

export const engine = new AudioEngine();
