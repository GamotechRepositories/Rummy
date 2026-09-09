import React from 'react';
import type { OpponentView } from '../types/game';
import { TurnTimerRing } from './TurnTimerRing';
import { Bot, User } from 'lucide-react';

interface OpponentSeatProps {
  player?: OpponentView;
  activePlayerId?: string | null;
  turnDeadline?: string | null;
  seatNumber: number;
}

export const OpponentSeat: React.FC<OpponentSeatProps> = ({
  player,
  activePlayerId,
  turnDeadline,
  seatNumber,
}) => {
  if (!player) {
    return (
      <div
        style={{
          display: 'flex',
          flexDirection: 'column',
          alignItems: 'center',
          gap: '6px',
          opacity: 0.4,
        }}
      >
        <div
          style={{
            width: '60px',
            height: '60px',
            borderRadius: '50%',
            border: '2px dashed rgba(255, 255, 255, 0.3)',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            color: 'var(--text-muted)',
            fontSize: '12px',
          }}
        >
          Seat {seatNumber + 1}
        </div>
        <span style={{ fontSize: '11px', color: 'var(--text-muted)' }}>Empty</span>
      </div>
    );
  }

  const isCurrentTurn = activePlayerId === player.playerId;
  const isDropped = player.status === 'DROPPED';
  const isDeclared = player.status === 'DECLARED';

  return (
    <div
      id={`seat-${player.playerId}`}
      style={{
        display: 'flex',
        flexDirection: 'column',
        alignItems: 'center',
        gap: '6px',
        position: 'relative',
      }}
    >
      {/* Avatar Container with Timer Ring */}
      <div style={{ position: 'relative', width: '68px', height: '68px' }}>
        {isCurrentTurn && (
          <div style={{ position: 'absolute', top: '-2px', left: '-2px', zIndex: 10 }}>
            <TurnTimerRing turnDeadline={turnDeadline ?? null} size={72} strokeWidth={4} />
          </div>
        )}

        <div
          style={{
            width: '64px',
            height: '64px',
            margin: '2px',
            borderRadius: '50%',
            background: isCurrentTurn
              ? 'linear-gradient(135deg, #1e293b, #0f172a)'
              : 'linear-gradient(135deg, #334155, #1e293b)',
            border: isCurrentTurn
              ? '2px solid var(--border-gold)'
              : '2px solid rgba(255, 255, 255, 0.2)',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            boxShadow: isCurrentTurn ? '0 0 15px var(--border-gold-glow)' : 'none',
            color: '#ffffff',
            position: 'relative',
          }}
        >
          {player.isBot ? <Bot size={28} color="#93c5fd" /> : <User size={28} color="#fde047" />}

          {/* Status Badge */}
          {isDropped && (
            <span
              style={{
                position: 'absolute',
                bottom: '-4px',
                background: '#dc2626',
                color: '#ffffff',
                fontSize: '9px',
                fontWeight: 800,
                padding: '1px 5px',
                borderRadius: '4px',
              }}
            >
              DROPPED
            </span>
          )}

          {isDeclared && (
            <span
              style={{
                position: 'absolute',
                bottom: '-4px',
                background: '#16a34a',
                color: '#ffffff',
                fontSize: '9px',
                fontWeight: 800,
                padding: '1px 5px',
                borderRadius: '4px',
              }}
            >
              WON
            </span>
          )}
        </div>
      </div>

      {/* Name and Public Card Count */}
      <div
        style={{
          background: 'rgba(15, 23, 42, 0.85)',
          padding: '4px 10px',
          borderRadius: '8px',
          border: '1px solid rgba(255, 255, 255, 0.1)',
          textAlign: 'center',
          minWidth: '100px',
        }}
      >
        <div
          style={{
            fontSize: '12px',
            fontWeight: 700,
            color: 'var(--text-main)',
            whiteSpace: 'nowrap',
            overflow: 'hidden',
            textOverflow: 'ellipsis',
            maxWidth: '120px',
          }}
        >
          {player.displayName}
        </div>

        <div
          style={{
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            gap: '6px',
            fontSize: '11px',
            color: 'var(--text-muted)',
            marginTop: '2px',
          }}
        >
          <span>🂠 {player.cardCount} cards</span>
          <span>•</span>
          <span style={{ color: 'var(--gold-light)' }}>{player.score} pts</span>
        </div>
      </div>
    </div>
  );
};
