import React, { useEffect, useMemo, useRef, useState } from 'react';
import { soundEngine } from '../audio/soundEngine';

export type DealTarget = {
  id: string;
  /** Optional DOM selector; defaults to `#seat-${id}` */
  selector?: string;
};

type FlyCard = {
  key: string;
  targetId: string;
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
  /** Fired when a card reaches a seat, so that seat's count can tick up. */
  onCardLanded?: (targetId: string) => void;
};

const FLIGHT_MS = 460;
/** Next card leaves while the previous is still in the air, so the circle stays readable. */
const STAGGER_MS = 120;
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
  onCardLanded,
}) => {
  const [cards, setCards] = useState<FlyCard[]>([]);
  const [origin, setOrigin] = useState<{ x: number; y: number } | null>(null);
  const doneRef = useRef(false);
  const onCompleteRef = useRef(onComplete);
  const onCardLandedRef = useRef(onCardLanded);
  onCompleteRef.current = onComplete;
  onCardLandedRef.current = onCardLanded;

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
    const soundTimeouts: number[] = [];
    const frozenTargets = targets;

    const start = () => {
      if (cancelled) return;
      const tableRect = table.getBoundingClientRect();
      const ox = tableRect.width * HANDS_X;
      const oy = tableRect.height * HANDS_Y;
      setOrigin({ x: ox, y: oy });

      const cx = tableRect.width / 2;
      const cy = tableRect.height / 2;
      const circle = frozenTargets
        .map((t) => {
          const sel = t.selector ?? `#seat-${t.id}`;
          const dest = measurePoint(table, sel) ?? { x: ox, y: oy + tableRect.height * 0.45 };
          // 0 at the dealer (12 o'clock), increasing clockwise around the oval.
          let angle = Math.atan2(dest.x - cx, -(dest.y - cy));
          if (angle < 0) angle += Math.PI * 2;
          return { id: t.id, dest, angle };
        })
        .sort((a, b) => a.angle - b.angle);

      const next: FlyCard[] = [];
      let idx = 0;
      for (let round = 0; round < cardsPerPlayer; round++) {
        for (let p = 0; p < circle.length; p++) {
          const dest = circle[p].dest;
          const dx = dest.x - ox;
          const dy = dest.y - oy;
          const len = Math.hypot(dx, dy) || 1;
          const midX = dx * 0.5;
          const midY = dy * 0.5;
          const outX = ox + midX - cx;
          const outY = oy + midY - cy;
          const outLen = Math.hypot(outX, outY) || 1;
          const bow = Math.min(22, len * 0.1);
          const rot = (Math.atan2(dy, dx) * 180) / Math.PI * 0.06;
          next.push({
            key: `deal-${idx}`,
            targetId: circle[p].id,
            dx,
            dy,
            mx: midX + (outX / outLen) * bow,
            my: midY + (outY / outLen) * bow,
            rot,
            rotMid: rot * 0.4,
            delayMs: idx * STAGGER_MS,
          });
          idx += 1;
        }
      }
      setCards(next);

      // Play authentic card flick sound for every single card as it leaves dealer's deck
      next.forEach((card, i) => {
        const flick = window.setTimeout(() => {
          if (!cancelled) {
            soundEngine.playCardDeal(i);
          }
        }, card.delayMs);
        const landed = window.setTimeout(() => {
          if (!cancelled) onCardLandedRef.current?.(card.targetId);
        }, card.delayMs + Math.round(FLIGHT_MS * 0.88));
        soundTimeouts.push(flick, landed);
      });

      const flights = frozenTargets.length * cardsPerPlayer;
      const totalMs = Math.max(0, flights - 1) * STAGGER_MS + FLIGHT_MS + 280;
      finishTimer = window.setTimeout(() => {
        if (!doneRef.current) {
          doneRef.current = true;
          soundEngine.playCardFan();
          onCompleteRef.current();
        }
      }, totalMs);
    };

    requestAnimationFrame(() => requestAnimationFrame(start));

    return () => {
      cancelled = true;
      window.clearTimeout(finishTimer);
      soundTimeouts.forEach((t) => window.clearTimeout(t));
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
