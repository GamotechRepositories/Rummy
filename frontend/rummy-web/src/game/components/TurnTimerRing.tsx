import React, { useEffect, useId, useRef, useState } from 'react';
import { soundEngine } from '../audio/soundEngine';

interface TurnTimerRingProps {
  turnDeadline: string | null;
  /** Pixel size; omit to fill parent (responsive). */
  size?: number;
  strokeWidth?: number;
  totalDurationSeconds?: number;
  showText?: boolean;
  showBadge?: boolean;
  enableTickSound?: boolean;
}

export const TurnTimerRing: React.FC<TurnTimerRingProps> = ({
  turnDeadline,
  size,
  strokeWidth = 4.5,
  totalDurationSeconds = 30,
  showText = false,
  showBadge = false,
  enableTickSound = false,
}) => {
  const [secondsRemaining, setSecondsRemaining] = useState<number>(0);
  const [progress, setProgress] = useState<number>(1);
  const lastTickRef = useRef<number>(-1);
  const idPrefix = useId().replace(/:/g, '');

  useEffect(() => {
    if (!turnDeadline) {
      setSecondsRemaining(0);
      setProgress(0);
      lastTickRef.current = -1;
      return;
    }

    const updateTimer = () => {
      const deadline = new Date(turnDeadline).getTime();
      const now = Date.now();
      const msLeft = Math.max(0, deadline - now);
      const secs = Math.ceil(msLeft / 1000);
      const ratio = Math.min(1, Math.max(0, msLeft / (totalDurationSeconds * 1000)));

      setSecondsRemaining(secs);
      setProgress(ratio);

      // Soft audio tick in critical urgency (last 5 seconds)
      if (enableTickSound && secs <= 5 && secs > 0 && lastTickRef.current !== secs) {
        lastTickRef.current = secs;
        soundEngine.play('tick');
      }
    };

    updateTimer();
    const interval = setInterval(updateTimer, 100);
    return () => clearInterval(interval);
  }, [turnDeadline, totalDurationSeconds, enableTickSound]);

  if (!turnDeadline || secondsRemaining <= 0) {
    return null;
  }

  // Phase determination
  let phase: 'safe' | 'warning' | 'critical' = 'safe';
  if (secondsRemaining <= 5) {
    phase = 'critical';
  } else if (secondsRemaining <= 12) {
    phase = 'warning';
  }

  const vb = 100;
  const sw = size ? (strokeWidth / size) * vb : strokeWidth * 1.5;
  const radius = (vb - sw - 4) / 2;
  const circumference = 2 * Math.PI * radius;
  const strokeDashoffset = circumference * (1 - progress);

  // Calculate coordinates of the leading-edge bead (in -90deg rotated space)
  const angle = progress * 2 * Math.PI;
  const beadX = vb / 2 + radius * Math.cos(angle);
  const beadY = vb / 2 + radius * Math.sin(angle);
  const showBead = progress > 0.02 && progress < 0.99;

  const gradSafeId = `timer-grad-safe-${idPrefix}`;
  const gradWarnId = `timer-grad-warn-${idPrefix}`;
  const gradCritId = `timer-grad-crit-${idPrefix}`;
  const glowId = `timer-glow-${idPrefix}`;

  const currentGradId =
    phase === 'critical' ? gradCritId : phase === 'warning' ? gradWarnId : gradSafeId;

  const boxStyle: React.CSSProperties = size
    ? {
        position: 'relative',
        width: size,
        height: size,
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
      }
    : {
        position: 'relative',
        width: '100%',
        height: '100%',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
      };

  return (
    <div style={boxStyle} className={`turn-timer-root turn-timer-root--${phase}`}>
      {/* Floating Countdown Pill Badge above avatar */}
      {showBadge && (
        <div className={`turn-timer-pill turn-timer-pill--${phase}`} aria-label={`${secondsRemaining} left`}>
          <span className="turn-timer-pill-pulse" />
          <span className="turn-timer-pill-num">{secondsRemaining}</span>
        </div>
      )}

      <svg
        viewBox={`0 0 ${vb} ${vb}`}
        width={size ?? '100%'}
        height={size ?? '100%'}
        preserveAspectRatio="xMidYMid meet"
        className="turn-timer-svg"
      >
        <defs>
          {/* Safe Phase: Vibrant Emerald to Electric Mint & Cyan */}
          <linearGradient id={gradSafeId} x1="0%" y1="0%" x2="100%" y2="100%">
            <stop offset="0%" stopColor="#059669" />
            <stop offset="45%" stopColor="#10b981" />
            <stop offset="85%" stopColor="#34d399" />
            <stop offset="100%" stopColor="#06b6d4" />
          </linearGradient>

          {/* Warning Phase: Molten Amber to Radiant Gold */}
          <linearGradient id={gradWarnId} x1="0%" y1="0%" x2="100%" y2="100%">
            <stop offset="0%" stopColor="#d97706" />
            <stop offset="50%" stopColor="#f59e0b" />
            <stop offset="85%" stopColor="#fbbf24" />
            <stop offset="100%" stopColor="#fef08a" />
          </linearGradient>

          {/* Critical Phase: Vivid Scarlet to Intense Neon Ruby */}
          <linearGradient id={gradCritId} x1="0%" y1="0%" x2="100%" y2="100%">
            <stop offset="0%" stopColor="#b91c1c" />
            <stop offset="40%" stopColor="#ef4444" />
            <stop offset="80%" stopColor="#f87171" />
            <stop offset="100%" stopColor="#fda4af" />
          </linearGradient>

          {/* Bloom Filter for high-end glowing light */}
          <filter id={glowId} x="-20%" y="-20%" width="140%" height="140%">
            <feGaussianBlur stdDeviation="2.2" result="blur" />
            <feMerge>
              <feMergeNode in="blur" />
              <feMergeNode in="SourceGraphic" />
            </feMerge>
          </filter>
        </defs>

        {/* Recessed Dark Glass Outer Track */}
        <circle
          cx={vb / 2}
          cy={vb / 2}
          r={radius}
          stroke="rgba(0, 0, 0, 0.7)"
          strokeWidth={sw + 3}
          fill="none"
        />

        {/* Beveled Metallic Track Groove */}
        <circle
          cx={vb / 2}
          cy={vb / 2}
          r={radius}
          stroke="rgba(255, 255, 255, 0.12)"
          strokeWidth={sw}
          fill="none"
        />

        {/* Rotated Group for Clockwise Progress Starting at 12 o'clock */}
        <g style={{ transform: 'rotate(-90deg)', transformOrigin: `${vb / 2}px ${vb / 2}px` }}>
          {/* Active Glowing Progress Arc */}
          <circle
            cx={vb / 2}
            cy={vb / 2}
            r={radius}
            stroke={`url(#${currentGradId})`}
            strokeWidth={sw}
            fill="none"
            strokeDasharray={circumference}
            strokeDashoffset={strokeDashoffset}
            strokeLinecap="round"
            filter={`url(#${glowId})`}
            className="turn-timer-active-arc"
          />

          {/* Leading-Edge Glowing Orb / Flare Bead */}
          {showBead && (
            <g className="turn-timer-bead-group" filter={`url(#${glowId})`}>
              <circle
                cx={beadX}
                cy={beadY}
                r={sw * 0.9}
                fill={phase === 'critical' ? '#fda4af' : phase === 'warning' ? '#fef08a' : '#a7f3d0'}
                opacity={0.85}
              />
              <circle
                cx={beadX}
                cy={beadY}
                r={sw * 0.45}
                fill="#ffffff"
              />
            </g>
          )}
        </g>
      </svg>

      {showText && (
        <span className={`turn-timer-center-text turn-timer-center-text--${phase}`}>
          {secondsRemaining}
        </span>
      )}
    </div>
  );
};
