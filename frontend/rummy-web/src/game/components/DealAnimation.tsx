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
  rotMid: number;
  delayMs: number;
};

type Props = {
  active: boolean;
  /** Opponents + self — cards fly in this seat order, round-robin */
  targets: DealTarget[];
  cardsPerPlayer?: number;
  onComplete: () => void;
};

const FLIGHT_MS = 780;
const STAGGER_MS = 70;
/**
 * Origin = painted deck between dealer hands (felt rim notch).
 * Stage uses the board PNG 1:1 via aspect-ratio — tune as % of stage height.
 */
const HANDS_Y = 0.305;
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
        return measurePoint(table, sel) ?? { x: ox, y: oy + tableRect.height * 0.45 };
      });

      const next: FlyCard[] = [];
      let idx = 0;
      for (let round = 0; round < cardsPerPlayer; round++) {
        for (let p = 0; p < frozenTargets.length; p++) {
          const dest = dests[p];
          const dx = dest.x - ox;
          const dy = dest.y - oy;
          // Leave hands straight onto felt, then arc out to seat
          const feltDip = Math.max(tableRect.height * 0.16, 64);
          const mx = dx * 0.38;
          const my = Math.max(dy * 0.32, feltDip);
          const rot = (p % 2 === 0 ? -1 : 1) * (16 + (round % 4) * 7) + (idx % 3) * 4;
          next.push({
            key: `deal-${idx}`,
            dx,
            dy,
            mx,
            my,
            rot,
            rotMid: rot * 0.35,
            delayMs: idx * STAGGER_MS,
          });
          idx += 1;
        }
      }
      setCards(next);
      soundEngine.play('deal');

      const flights = frozenTargets.length * cardsPerPlayer;
      const totalMs = Math.max(0, flights - 1) * STAGGER_MS + FLIGHT_MS + 280;
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

      {/* Stock in hands — cards peel off from here */}
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
              '--deal-rot-mid': `${c.rotMid}deg`,
            } as React.CSSProperties
          }
        >
          <div className="rummy-card-back deal-fly-face" />
        </div>
      ))}
    </div>
  );
};
