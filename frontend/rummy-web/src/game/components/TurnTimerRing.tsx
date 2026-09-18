import React, { useEffect, useState } from 'react';

interface TurnTimerRingProps {
  turnDeadline: string | null;
  size?: number;
  strokeWidth?: number;
  totalDurationSeconds?: number;
  showText?: boolean;
}

export const TurnTimerRing: React.FC<TurnTimerRingProps> = ({
  turnDeadline,
  size = 72,
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

  const radius = (size - strokeWidth) / 2;
  const circumference = 2 * Math.PI * radius;
  const progress = Math.min(1, Math.max(0, secondsRemaining / totalDurationSeconds));
  const strokeDashoffset = circumference * (1 - progress);

  // Color transitions: Green (>12s) -> Amber (5-12s) -> Red Pulsing (<5s)
  let strokeColor = 'var(--color-pure)';
  if (secondsRemaining < 5) {
    strokeColor = 'var(--color-invalid)';
  } else if (secondsRemaining < 12) {
    strokeColor = 'var(--gold-accent)';
  }

  const isCritical = secondsRemaining <= 5;

  return (
    <div
      style={{
        position: 'relative',
        width: size,
        height: size,
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
      }}
      className={isCritical ? 'timer-critical' : ''}
    >
      <svg
        width={size}
        height={size}
        style={{
          transform: 'rotate(-90deg)',
          position: 'absolute',
          top: 0,
          left: 0,
        }}
      >
        {/* Background track */}
        <circle
          cx={size / 2}
          cy={size / 2}
          r={radius}
          stroke="rgba(255, 255, 255, 0.15)"
          strokeWidth={strokeWidth}
          fill="none"
        />
        {/* Animated Countdown Ring */}
        <circle
          cx={size / 2}
          cy={size / 2}
          r={radius}
          stroke={strokeColor}
          strokeWidth={strokeWidth}
          fill="none"
          strokeDasharray={circumference}
          strokeDashoffset={strokeDashoffset}
          strokeLinecap="round"
          style={{ transition: 'stroke-dashoffset 0.25s linear, stroke 0.3s ease' }}
        />
      </svg>
      {/* Time Text (optional, default false so it does not block the avatar icon) */}
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
