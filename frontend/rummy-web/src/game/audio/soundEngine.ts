/**
 * Lightweight Web Audio sound engine — no external audio files.
 * Soft casino-style beeps for card game feedback.
 */

type SoundName =
  | 'click'
  | 'select'
  | 'draw'
  | 'discard'
  | 'group'
  | 'sort'
  | 'turn'
  | 'win'
  | 'lose'
  | 'error'
  | 'match'
  | 'deal'
  | 'tick'
  | 'modal';

const MUTE_KEY = 'rummy_sound_muted';

const SOUND_FILES = {
  'deal-1': ['/sounds/card-deal.wav', '/sounds/card-deal.mp3'],
  'deal-2': ['/sounds/card-deal-2.wav', '/sounds/card-deal-2.mp3'],
  'slide-1': ['/sounds/card-slide.wav', '/sounds/card-slide.mp3'],
  'slide-2': ['/sounds/card-slide-2.wav', '/sounds/card-slide-2.mp3'],
  'fan': ['/sounds/card-fan.wav', '/sounds/card-fan.mp3'],
} as const;

class SoundEngine {
  private ctx: AudioContext | null = null;
  private muted = false;
  private unlocked = false;
  private lastPlay = 0;
  private sampleBuffers: Map<string, AudioBuffer> = new Map();
  private samplesLoading = false;
  private noiseBuffer: AudioBuffer | null = null;

  constructor() {
    try {
      this.muted = localStorage.getItem(MUTE_KEY) === '1';
    } catch {
      this.muted = false;
    }
  }

  isMuted(): boolean {
    return this.muted;
  }

  setMuted(muted: boolean): void {
    this.muted = muted;
    try {
      localStorage.setItem(MUTE_KEY, muted ? '1' : '0');
    } catch {
      // ignore
    }
    if (!muted) this.play('click');
  }

  toggleMute(): boolean {
    this.setMuted(!this.muted);
    return this.muted;
  }

  /** Call on first user gesture so browsers allow audio. */
  unlock(): void {
    if (this.unlocked) return;
    const ctx = this.ensureCtx();
    if (!ctx) return;
    if (ctx.state === 'suspended') {
      void ctx.resume();
    }
    // Silent blip to unlock
    const osc = ctx.createOscillator();
    const gain = ctx.createGain();
    gain.gain.value = 0.0001;
    osc.connect(gain);
    gain.connect(ctx.destination);
    osc.start();
    osc.stop(ctx.currentTime + 0.01);
    this.unlocked = true;

    this.preloadSamples();
  }

  /** Preload realistic casino card audio buffers */
  private preloadSamples(): void {
    if (this.samplesLoading || this.sampleBuffers.size > 0) return;
    const ctx = this.ensureCtx();
    if (!ctx) return;
    this.samplesLoading = true;

    const loadEntry = async (key: string, urls: readonly string[]) => {
      for (const url of urls) {
        try {
          const resp = await fetch(url);
          if (!resp.ok) continue;
          const arrayBuffer = await resp.arrayBuffer();
          const audioBuffer = await ctx.decodeAudioData(arrayBuffer);
          this.sampleBuffers.set(key, audioBuffer);
          return;
        } catch {
          // try next format
        }
      }
    };

    void Promise.all(
      Object.entries(SOUND_FILES).map(([key, urls]) => loadEntry(key, urls))
    ).finally(() => {
      this.samplesLoading = false;
    });
  }

  /**
   * Play authentic playing card deal / flick sound.
   * Plays with micro-variations in pitch, volume, and timbre for natural organic dealing rhythm.
   */
  playCardDeal(cardIndex = 0): void {
    if (this.muted) return;
    const ctx = this.ensureCtx();
    if (!ctx) return;
    if (ctx.state === 'suspended') void ctx.resume();

    // Check if real recorded card deal samples are ready
    const key = cardIndex % 2 === 0 ? 'deal-1' : 'deal-2';
    const sample = this.sampleBuffers.get(key) || this.sampleBuffers.get('deal-1');

    if (sample) {
      this.playBuffer(sample, {
        playbackRate: 0.94 + Math.random() * 0.12,
        volume: 0.65 + (Math.random() - 0.5) * 0.1,
      });
      return;
    }

    // High quality procedural synthesized card flick fallback
    this.synthCardFlick(ctx, cardIndex);
  }

  /** Play smooth card slide / draw sound */
  playCardSlide(): void {
    if (this.muted) return;
    const ctx = this.ensureCtx();
    if (!ctx) return;
    if (ctx.state === 'suspended') void ctx.resume();

    const sample = this.sampleBuffers.get('slide-1');
    if (sample) {
      this.playBuffer(sample, {
        playbackRate: 0.96 + Math.random() * 0.08,
        volume: 0.7,
      });
      return;
    }

    this.synthCardSlide(ctx);
  }

  /** Play crisp card placement / discard sound */
  playCardPlace(): void {
    if (this.muted) return;
    const ctx = this.ensureCtx();
    if (!ctx) return;
    if (ctx.state === 'suspended') void ctx.resume();

    const sample = this.sampleBuffers.get('slide-2') || this.sampleBuffers.get('slide-1');
    if (sample) {
      this.playBuffer(sample, {
        playbackRate: 0.98 + Math.random() * 0.08,
        volume: 0.75,
      });
      return;
    }

    this.sweep(ctx, 580, 240, 0.09, 0.1);
  }

  /** Play card fan / shuffle flourish (e.g. when dealing completes) */
  playCardFan(): void {
    if (this.muted) return;
    const ctx = this.ensureCtx();
    if (!ctx) return;
    if (ctx.state === 'suspended') void ctx.resume();

    const sample = this.sampleBuffers.get('fan');
    if (sample) {
      this.playBuffer(sample, {
        playbackRate: 1.0,
        volume: 0.8,
      });
      return;
    }

    this.arpeggio(ctx, [380, 440, 520, 600, 680], 0.03, 0.06);
  }

  private playBuffer(
    buffer: AudioBuffer,
    opts: { playbackRate?: number; volume?: number } = {}
  ): void {
    if (!this.ctx) return;
    const src = this.ctx.createBufferSource();
    const gain = this.ctx.createGain();
    src.buffer = buffer;
    if (opts.playbackRate) {
      src.playbackRate.value = opts.playbackRate;
    }
    gain.gain.value = Math.max(0, Math.min(1, opts.volume ?? 0.7));
    src.connect(gain);
    gain.connect(this.ctx.destination);
    src.start();
  }

  private getNoiseBuffer(ctx: AudioContext): AudioBuffer {
    if (this.noiseBuffer && this.noiseBuffer.sampleRate === ctx.sampleRate) {
      return this.noiseBuffer;
    }
    const len = Math.floor(ctx.sampleRate * 0.4);
    const buf = ctx.createBuffer(1, len, ctx.sampleRate);
    const data = buf.getChannelData(0);
    for (let i = 0; i < len; i++) {
      data[i] = Math.random() * 2 - 1;
    }
    this.noiseBuffer = buf;
    return buf;
  }

  /** Procedural synthesis of card flick / deal */
  private synthCardFlick(ctx: AudioContext, cardIndex: number): void {
    const t0 = ctx.currentTime;
    const dur = 0.065 + Math.random() * 0.015;
    const pitchJitter = ((cardIndex % 5) - 2) * 90 + (Math.random() - 0.5) * 60;
    const baseFreq = 3800 + pitchJitter;

    // 1. Friction noise layer (card leaving deck)
    const noise = ctx.createBufferSource();
    noise.buffer = this.getNoiseBuffer(ctx);
    noise.loop = true;
    noise.loopStart = Math.random() * 0.1;
    noise.loopEnd = noise.loopStart + 0.15;

    const bpf = ctx.createBiquadFilter();
    bpf.type = 'bandpass';
    bpf.frequency.setValueAtTime(baseFreq, t0);
    bpf.frequency.exponentialRampToValueAtTime(Math.max(baseFreq * 0.55, 1200), t0 + dur);
    bpf.Q.value = 2.4;

    const noiseGain = ctx.createGain();
    const peakVol = 0.22 + (Math.random() - 0.5) * 0.04;
    noiseGain.gain.setValueAtTime(0.0001, t0);
    noiseGain.gain.linearRampToValueAtTime(peakVol, t0 + 0.002);
    noiseGain.gain.exponentialRampToValueAtTime(0.0001, t0 + dur);

    noise.connect(bpf);
    bpf.connect(noiseGain);
    noiseGain.connect(ctx.destination);

    noise.start(t0);
    noise.stop(t0 + dur + 0.02);

    // 2. Card snap body transient
    const snapOsc = ctx.createOscillator();
    const snapGain = ctx.createGain();
    snapOsc.type = 'triangle';
    const snapFreq = 270 + (Math.random() - 0.5) * 35;
    snapOsc.frequency.setValueAtTime(snapFreq, t0);
    snapOsc.frequency.exponentialRampToValueAtTime(55, t0 + 0.02);

    snapGain.gain.setValueAtTime(0.0001, t0);
    snapGain.gain.linearRampToValueAtTime(0.14, t0 + 0.002);
    snapGain.gain.exponentialRampToValueAtTime(0.0001, t0 + 0.022);

    snapOsc.connect(snapGain);
    snapGain.connect(ctx.destination);

    snapOsc.start(t0);
    snapOsc.stop(t0 + 0.025);
  }

  /** Procedural synthesis of card sliding on felt */
  private synthCardSlide(ctx: AudioContext): void {
    const t0 = ctx.currentTime;
    const dur = 0.14;

    const noise = ctx.createBufferSource();
    noise.buffer = this.getNoiseBuffer(ctx);
    noise.loop = true;

    const lpf = ctx.createBiquadFilter();
    lpf.type = 'lowpass';
    lpf.frequency.setValueAtTime(2400, t0);
    lpf.frequency.linearRampToValueAtTime(1400, t0 + dur);

    const gain = ctx.createGain();
    gain.gain.setValueAtTime(0.0001, t0);
    gain.gain.linearRampToValueAtTime(0.15, t0 + 0.02);
    gain.gain.exponentialRampToValueAtTime(0.0001, t0 + dur);

    noise.connect(lpf);
    lpf.connect(gain);
    gain.connect(ctx.destination);

    noise.start(t0);
    noise.stop(t0 + dur + 0.02);
  }

  play(name: SoundName): void {
    if (this.muted) return;
    const now = performance.now();
    // Debounce identical spam
    if (now - this.lastPlay < 30 && name === 'tick') return;
    this.lastPlay = now;

    const ctx = this.ensureCtx();
    if (!ctx) return;
    if (ctx.state === 'suspended') void ctx.resume();

    switch (name) {
      case 'click':
        this.tone(ctx, 880, 0.04, 'sine', 0.08);
        break;
      case 'select':
        this.tone(ctx, 660, 0.05, 'triangle', 0.07);
        break;
      case 'draw':
        this.playCardSlide();
        break;
      case 'discard':
        this.playCardPlace();
        break;
      case 'group':
        this.chord(ctx, [523, 659, 784], 0.14, 0.07);
        break;
      case 'sort':
        this.playCardFan();
        break;
      case 'turn':
        this.chord(ctx, [440, 554], 0.18, 0.09);
        break;
      case 'win':
        this.arpeggio(ctx, [523, 659, 784, 1046], 0.09, 0.1);
        break;
      case 'lose':
        this.sweep(ctx, 400, 180, 0.25, 0.1);
        break;
      case 'error':
        this.tone(ctx, 180, 0.16, 'sawtooth', 0.06);
        break;
      case 'match':
        this.arpeggio(ctx, [392, 494, 587, 784], 0.08, 0.09);
        break;
      case 'deal':
        this.playCardDeal();
        break;
      case 'tick':
        this.tone(ctx, 1200, 0.02, 'square', 0.03);
        break;
      case 'modal':
        this.tone(ctx, 520, 0.08, 'sine', 0.07);
        break;
    }
  }

  private ensureCtx(): AudioContext | null {
    if (typeof window === 'undefined') return null;
    try {
      if (!this.ctx) {
        const AC =
          window.AudioContext ||
          (window as unknown as { webkitAudioContext: typeof AudioContext }).webkitAudioContext;
        this.ctx = new AC();
      }
      return this.ctx;
    } catch {
      return null;
    }
  }

  private tone(
    ctx: AudioContext,
    freq: number,
    dur: number,
    type: OscillatorType,
    vol: number
  ): void {
    const t0 = ctx.currentTime;
    const osc = ctx.createOscillator();
    const gain = ctx.createGain();
    osc.type = type;
    osc.frequency.value = freq;
    gain.gain.setValueAtTime(0.0001, t0);
    gain.gain.exponentialRampToValueAtTime(vol, t0 + 0.01);
    gain.gain.exponentialRampToValueAtTime(0.0001, t0 + dur);
    osc.connect(gain);
    gain.connect(ctx.destination);
    osc.start(t0);
    osc.stop(t0 + dur + 0.02);
  }

  private sweep(
    ctx: AudioContext,
    from: number,
    to: number,
    dur: number,
    vol: number
  ): void {
    const t0 = ctx.currentTime;
    const osc = ctx.createOscillator();
    const gain = ctx.createGain();
    osc.type = 'sine';
    osc.frequency.setValueAtTime(from, t0);
    osc.frequency.exponentialRampToValueAtTime(Math.max(to, 1), t0 + dur);
    gain.gain.setValueAtTime(0.0001, t0);
    gain.gain.exponentialRampToValueAtTime(vol, t0 + 0.02);
    gain.gain.exponentialRampToValueAtTime(0.0001, t0 + dur);
    osc.connect(gain);
    gain.connect(ctx.destination);
    osc.start(t0);
    osc.stop(t0 + dur + 0.02);
  }

  private chord(ctx: AudioContext, freqs: number[], dur: number, vol: number): void {
    freqs.forEach((f, i) => {
      setTimeout(() => this.tone(ctx, f, dur, 'sine', vol * 0.7), i * 20);
    });
  }

  private arpeggio(
    ctx: AudioContext,
    freqs: number[],
    step: number,
    vol: number
  ): void {
    freqs.forEach((f, i) => {
      setTimeout(() => this.tone(ctx, f, step * 2.2, 'triangle', vol), i * step * 1000);
    });
  }
}

export const soundEngine = new SoundEngine();

/** Unlock audio on first pointer/key interaction. */
export function installSoundUnlock(): void {
  const unlock = () => {
    soundEngine.unlock();
    window.removeEventListener('pointerdown', unlock);
    window.removeEventListener('keydown', unlock);
  };
  window.addEventListener('pointerdown', unlock, { once: true });
  window.addEventListener('keydown', unlock, { once: true });
}
