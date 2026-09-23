import React, { useEffect, useState } from 'react';

interface TurnTimerRingProps {
  turnDeadline: string | null;
  /** Pixel size; omit to fill parent (responsive). */
  size?: number;
  strokeWidth?: number;
  totalDurationSeconds?: number;
  showText?: boolean;
}

export const TurnTimerRing: React.FC<TurnTimerRingProps> = ({
  turnDeadline,
  size,
  strokeWidth = 4,
  totalDurationSeconds = 30,
  showText = false,
}) => {
  const [secondsRemaining, setSecondsRemaining] = useState<number>(0);

  useEffect(() => {
    if (!turnDeadline) {
      setSecondsRemaining(0);
      return;
    }

    const calculateRemaining = () => {
      const deadline = new Date(turnDeadline).getTime();
      const now = Date.now();
      const diff = Math.max(0, Math.ceil((deadline - now) / 1000));
      setSecondsRemaining(diff);
    };

    calculateRemaining();
    const interval = setInterval(calculateRemaining, 250);
    return () => clearInterval(interval);
  }, [turnDeadline]);

  if (!turnDeadline || secondsRemaining <= 0) {
    return null;
  }

  // Unitless viewBox geometry so SVG can scale with CSS width/height
  const vb = 100;
  const sw = size ? (strokeWidth / size) * vb : strokeWidth * 1.6;
  const radius = (vb - sw) / 2;
  const circumference = 2 * Math.PI * radius;
  const progress = Math.min(1, Math.max(0, secondsRemaining / totalDurationSeconds));
  const strokeDashoffset = circumference * (1 - progress);

  let strokeColor = 'var(--color-pure)';
  if (secondsRemaining < 5) {
    strokeColor = 'var(--color-invalid)';
  } else if (secondsRemaining < 12) {
    strokeColor = 'var(--gold-accent)';
  }

  const isCritical = secondsRemaining <= 5;
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
    <div style={boxStyle} className={isCritical ? 'timer-critical' : ''}>
      <svg
        viewBox={`0 0 ${vb} ${vb}`}
        width={size ?? '100%'}
        height={size ?? '100%'}
        preserveAspectRatio="xMidYMid meet"
        style={{
          transform: 'rotate(-90deg)',
          position: 'absolute',
          top: 0,
          left: 0,
        }}
      >
        <circle
          cx={vb / 2}
          cy={vb / 2}
          r={radius}
          stroke="rgba(255, 255, 255, 0.15)"
          strokeWidth={sw}
          fill="none"
        />
        <circle
          cx={vb / 2}
          cy={vb / 2}
          r={radius}
          stroke={strokeColor}
          strokeWidth={sw}
          fill="none"
          strokeDasharray={circumference}
          strokeDashoffset={strokeDashoffset}
          strokeLinecap="round"
          style={{ transition: 'stroke-dashoffset 0.25s linear, stroke 0.3s ease' }}
        />
      </svg>
      {showText && (
        <span
          style={{
            fontSize: '13px',
            fontWeight: 800,
            color: strokeColor,
            fontFamily: 'var(--font-display)',
          }}
        >
          {secondsRemaining}s
        </span>
      )}
    </div>
  );
};
