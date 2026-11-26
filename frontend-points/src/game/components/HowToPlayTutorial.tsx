import React, { useEffect, useState } from 'react';
import { ArrowRight, Check, Layers, Sparkles, Trophy, X } from 'lucide-react';
import { soundEngine } from '../audio/soundEngine';

const TUTORIAL_KEY = 'rummy_tutorial_done_v1';

export function shouldShowTutorial(): boolean {
  try {
    return localStorage.getItem(TUTORIAL_KEY) !== '1';
  } catch {
    return true;
  }
}

export function markTutorialDone(): void {
  try {
    localStorage.setItem(TUTORIAL_KEY, '1');
  } catch {
    // ignore
  }
}

interface HowToPlayTutorialProps {
  forceOpen?: boolean;
  onClose: () => void;
}

type Step = {
  id: string;
  title: string;
  body: string;
  visual: 'welcome' | 'groups' | 'turn' | 'declare' | 'ready';
};

const STEPS: Step[] = [
  {
    id: 'welcome',
    title: 'Welcome to Royal Rummy',
    body: 'You get 13 cards. Make valid groups, then discard your extra card to win.',
    visual: 'welcome',
  },
  {
    id: 'groups',
    title: 'Make groups',
    body: 'A run is same suit in a row (A♥ 2♥ 3♥). A set is same number (7♠ 7♥ 7♦). You need at least one pure run — no jokers.',
    visual: 'groups',
  },
  {
    id: 'turn',
    title: 'Every turn is simple',
    body: 'Step 1: Draw a card (mystery pile or open pile). Step 2: Throw one card away. Arrange groups while you wait.',
    visual: 'turn',
  },
  {
    id: 'declare',
    title: 'How to win',
    body: 'When all 13 cards are in valid groups (with 1 pure run), select your extra card and tap Declare.',
    visual: 'declare',
  },
  {
    id: 'ready',
    title: 'You’re ready!',
    body: 'Follow the golden coach banner in-game — it always tells you the next step. Have fun!',
    visual: 'ready',
  },
];

function MiniCard({
  rank,
  suit,
  delay = 0,
  highlight = false,
}: {
  rank: string;
  suit: string;
  delay?: number;
  highlight?: boolean;
}) {
  const red = suit === '♥' || suit === '♦';
  return (
    <div
      className="tut-mini-card"
      style={{
        animationDelay: `${delay}ms`,
        borderColor: highlight ? '#d4af37' : '#e2e8f0',
        boxShadow: highlight ? '0 0 0 2px rgba(212,175,55,0.5)' : undefined,
      }}
    >
      <span style={{ color: red ? '#dc2626' : '#0f172a', fontWeight: 800 }}>{rank}</span>
      <span style={{ color: red ? '#dc2626' : '#0f172a', fontSize: 14 }}>{suit}</span>
    </div>
  );
}

function StepVisual({ type }: { type: Step['visual'] }) {
  if (type === 'welcome') {
    return (
      <div className="tut-visual">
        <div className="tut-fan">
          {['A♠', 'K♥', 'Q♦', 'J♣', '10♥'].map((c, i) => (
            <div
              key={c}
              className="tut-fan-card"
              style={{
                transform: `rotate(${(i - 2) * 12}deg) translateY(${Math.abs(i - 2) * 4}px)`,
                animationDelay: `${i * 80}ms`,
                zIndex: i,
              }}
            >
              <span style={{ color: c.includes('♥') || c.includes('♦') ? '#dc2626' : '#0f172a' }}>{c}</span>
            </div>
          ))}
        </div>
        <p className="tut-caption">13 cards dealt to you</p>
      </div>
    );
  }

  if (type === 'groups') {
    return (
      <div className="tut-visual">
        <div className="tut-group-row">
          <div className="tut-group-block">
            <span className="tut-badge pure">Pure run ✓</span>
            <div className="tut-cards-row">
              <MiniCard rank="4" suit="♥" delay={0} highlight />
              <MiniCard rank="5" suit="♥" delay={100} highlight />
              <MiniCard rank="6" suit="♥" delay={200} highlight />
            </div>
          </div>
          <div className="tut-group-block">
            <span className="tut-badge set">Set</span>
            <div className="tut-cards-row">
              <MiniCard rank="9" suit="♠" delay={300} />
              <MiniCard rank="9" suit="♥" delay={400} />
              <MiniCard rank="9" suit="♦" delay={500} />
            </div>
          </div>
        </div>
      </div>
    );
  }

  if (type === 'turn') {
    return (
      <div className="tut-visual">
        <div className="tut-turn-flow">
          <div className="tut-turn-step">
            <div className="tut-pile closed tut-pulse">?</div>
            <span>1. Draw</span>
          </div>
          <ArrowRight className="tut-arrow" size={22} />
          <div className="tut-turn-step">
            <div className="tut-pile open">7♣</div>
            <span>or open</span>
          </div>
          <ArrowRight className="tut-arrow" size={22} />
          <div className="tut-turn-step">
            <div className="tut-pile discard tut-toss">K♦</div>
            <span>2. Discard</span>
          </div>
        </div>
      </div>
    );
  }

  if (type === 'declare') {
    return (
      <div className="tut-visual">
        <div className="tut-declare">
          <div className="tut-win-slot tut-glow">
            <Trophy size={28} color="#10b981" />
            <span>Win</span>
          </div>
          <div className="tut-toss-card">
            <MiniCard rank="2" suit="♣" highlight />
          </div>
        </div>
        <p className="tut-caption">Select extra card → Declare</p>
      </div>
    );
  }

  return (
    <div className="tut-visual tut-ready">
      <div className="tut-ready-ring">
        <Check size={40} color="#10b981" strokeWidth={3} />
      </div>
      <p className="tut-caption">Coach banner guides every move</p>
    </div>
  );
}

export const HowToPlayTutorial: React.FC<HowToPlayTutorialProps> = ({ forceOpen, onClose }) => {
  const [step, setStep] = useState(0);
  const [visible, setVisible] = useState(true);
  const current = STEPS[step];
  const isLast = step === STEPS.length - 1;

  useEffect(() => {
    setStep(0);
    setVisible(true);
  }, [forceOpen]);

  const finish = () => {
    markTutorialDone();
    soundEngine.play('win');
    setVisible(false);
    setTimeout(onClose, 280);
  };

  const next = () => {
    soundEngine.play('click');
    if (isLast) finish();
    else setStep((s) => s + 1);
  };

  const back = () => {
    soundEngine.play('click');
    setStep((s) => Math.max(0, s - 1));
  };

  return (
    <div className={`tut-overlay ${visible ? 'tut-show' : 'tut-hide'}`} role="dialog" aria-modal="true" aria-labelledby="tut-title">
      <div className="tut-card">
        <button type="button" className="tut-skip" onClick={finish} aria-label="Skip tutorial">
          <X size={18} />
        </button>

        <div className="tut-progress">
          {STEPS.map((s, i) => (
            <div key={s.id} className={`tut-dot ${i <= step ? 'active' : ''} ${i === step ? 'current' : ''}`} />
          ))}
        </div>

        <div key={current.id} className="tut-step-body">
          <div className="tut-icon-row">
            {current.visual === 'groups' && <Layers size={18} color="#38bdf8" />}
            {current.visual === 'declare' && <Trophy size={18} color="#34d399" />}
            {(current.visual === 'welcome' || current.visual === 'ready') && (
              <Sparkles size={18} color="#fbbf24" />
            )}
            <span className="tut-step-num">
              {step + 1} / {STEPS.length}
            </span>
          </div>

          <StepVisual type={current.visual} />

          <h2 id="tut-title">{current.title}</h2>
          <p>{current.body}</p>
        </div>

        <div className="tut-actions">
          {step > 0 ? (
            <button type="button" className="btn-secondary" onClick={back}>
              Back
            </button>
          ) : (
            <button type="button" className="btn-secondary" onClick={finish}>
              Skip
            </button>
          )}
          <button type="button" className="btn-primary tut-next" onClick={next}>
            {isLast ? 'Start playing' : 'Next'}
            {!isLast && <ArrowRight size={16} />}
          </button>
        </div>
      </div>
    </div>
  );
};
