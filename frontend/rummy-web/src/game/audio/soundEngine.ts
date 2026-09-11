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

class SoundEngine {
  private ctx: AudioContext | null = null;
  private muted = false;
  private unlocked = false;
  private lastPlay = 0;

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
        this.sweep(ctx, 320, 720, 0.12, 0.1);
        break;
      case 'discard':
        this.sweep(ctx, 600, 280, 0.1, 0.09);
        break;
      case 'group':
        this.chord(ctx, [523, 659, 784], 0.14, 0.07);
        break;
      case 'sort':
        this.arpeggio(ctx, [400, 500, 600, 700], 0.04, 0.05);
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
        this.arpeggio(ctx, [300, 340, 380, 420, 460], 0.035, 0.045);
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
