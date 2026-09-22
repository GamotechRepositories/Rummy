import React, { useEffect, useMemo, useRef, useState } from 'react';
import { soundEngine } from '../audio/soundEngine';

export type DealTarget = {
  id: string;
  /** Optional DOM selector; defaults to `#seat-${id}` */
  selector?: string;
};

type FlyCard = {
  key: string;
  dx: number;
  dy: number;
  mx: number;
  my: number;
  rot: number;
  delayMs: number;
};

type Props = {
  active: boolean;
  /** Opponents + self — cards fly in this seat order, round-robin */
  targets: DealTarget[];
  cardsPerPlayer?: number;
  onComplete: () => void;
};

const FLIGHT_MS = 720;
const STAGGER_MS = 62;
/** Dealer hands on wood rim (BG cover top) — not face/neck */
const HANDS_Y = 0.36;
const HANDS_X = 0.5;

function measurePoint(
  table: HTMLElement,
  selector: string
): { x: number; y: number } | null {
  const el = table.querySelector(selector);
  if (!el) return null;
  const t = table.getBoundingClientRect();
  const r = el.getBoundingClientRect();
  return {
    x: r.left + r.width / 2 - t.left,
    y: r.top + r.height / 2 - t.top,
  };
}

export const DealAnimation: React.FC<Props> = ({
  active,
  targets,
  cardsPerPlayer = 13,
  onComplete,
}) => {
  const [cards, setCards] = useState<FlyCard[]>([]);
  const [origin, setOrigin] = useState<{ x: number; y: number } | null>(null);
  const doneRef = useRef(false);
  const onCompleteRef = useRef(onComplete);
  onCompleteRef.current = onComplete;

  const targetsKey = useMemo(() => targets.map((t) => t.id).join('|'), [targets]);

  useEffect(() => {
    if (!active || targets.length === 0) {
      setCards([]);
      setOrigin(null);
      doneRef.current = false;
      return;
    }

    doneRef.current = false;
    const table = document.getElementById('game-felt-table');
    if (!table) {
      onCompleteRef.current();
      return;
    }

    let finishTimer = 0;
    let cancelled = false;
    const frozenTargets = targets;

    const start = () => {
      if (cancelled) return;
      const tableRect = table.getBoundingClientRect();
      const ox = tableRect.width * HANDS_X;
      const oy = tableRect.height * HANDS_Y;
      setOrigin({ x: ox, y: oy });

      const dests = frozenTargets.map((t) => {
        const sel = t.selector ?? `#seat-${t.id}`;
        return measurePoint(table, sel) ?? { x: ox, y: oy + tableRect.height * 0.4 };
      });

      const next: FlyCard[] = [];
      let idx = 0;
      for (let round = 0; round < cardsPerPlayer; round++) {
        for (let p = 0; p < frozenTargets.length; p++) {
          const dest = dests[p];
          const dx = dest.x - ox;
          const dy = dest.y - oy;
          // Arc: leave hands slightly into the felt, then out to seat
          const mx = dx * 0.28;
          const my = dy * 0.2 + Math.min(28, tableRect.height * 0.04);
          next.push({
            key: `deal-${idx}`,
            dx,
            dy,
            mx,
            my,
            rot: (p % 2 === 0 ? -1 : 1) * (22 + (round % 4) * 9) + (idx % 3) * 5,
            delayMs: idx * STAGGER_MS,
          });
          idx += 1;
        }
      }
      setCards(next);
      soundEngine.play('deal');

      const flights = frozenTargets.length * cardsPerPlayer;
      const totalMs = Math.max(0, flights - 1) * STAGGER_MS + FLIGHT_MS + 360;
      finishTimer = window.setTimeout(() => {
        if (!doneRef.current) {
          doneRef.current = true;
          onCompleteRef.current();
        }
      }, totalMs);
    };

    requestAnimationFrame(() => requestAnimationFrame(start));

    return () => {
      cancelled = true;
      window.clearTimeout(finishTimer);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps -- freeze on active + targetsKey
  }, [active, targetsKey, cardsPerPlayer]);

  if (!active || !origin) return null;

  return (
    <div className="deal-anim-layer" aria-hidden>
      <div className="deal-anim-banner">Dealer is dealing…</div>

      {/* Mini stock in her hands — makes origin read as “from hands” */}
      <div
        className="deal-hands-stock"
        style={{ left: origin.x, top: origin.y }}
      >
        <div className="rummy-card-back deal-stock-card deal-stock-card--a" />
        <div className="rummy-card-back deal-stock-card deal-stock-card--b" />
        <div className="rummy-card-back deal-stock-card deal-stock-card--c" />
      </div>

      {cards.map((c) => (
        <div
          key={c.key}
          className="deal-fly-card"
          style={
            {
              left: origin.x,
              top: origin.y,
              animationDuration: `${FLIGHT_MS}ms`,
              animationDelay: `${c.delayMs}ms`,
              '--deal-dx': `${c.dx}px`,
              '--deal-dy': `${c.dy}px`,
              '--deal-mx': `${c.mx}px`,
              '--deal-my': `${c.my}px`,
              '--deal-rot': `${c.rot}deg`,
            } as React.CSSProperties
          }
        >
          <div className="rummy-card-back deal-fly-face" />
        </div>
      ))}
    </div>
  );
};
