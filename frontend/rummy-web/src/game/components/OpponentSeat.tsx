import React from 'react';
import type { OpponentView } from '../types/game';
import { TurnTimerRing } from './TurnTimerRing';
import { getAvatarForPlayer } from '../utils/avatarUtils';
import { Crown } from 'lucide-react';

export type SeatPosition =
  | 'left'
  | 'right'
  | 'top'
  | 'top-left'
  | 'top-right'
  | 'top-center'
  | 'bottom-left'
  | 'bottom-right';

interface OpponentSeatProps {
  player?: OpponentView;
  activePlayerId?: string | null;
  turnDeadline?: string | null;
  seatNumber: number;
  gameStatus?: string;
  position?: SeatPosition;
  /** While dealing, show this count instead of the server hand size. */
  displayCount?: number;
}

export const OpponentSeat: React.FC<OpponentSeatProps> = ({
  player,
  activePlayerId,
  turnDeadline,
  seatNumber,
  gameStatus,
  position = 'top',
  displayCount,
}) => {
  if (!player) {
    return (
      <div
        className={`opponent-seat-pod pos-${position}`}
        style={{
          display: 'flex',
          flexDirection: 'column',
          alignItems: 'center',
          gap: '6px',
          opacity: 0.35,
        }}
      >
        <div
          style={{
            width: '54px',
            height: '54px',
            borderRadius: '50%',
            border: '2px dashed rgba(255, 255, 255, 0.3)',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            color: 'var(--text-muted)',
            fontSize: '11px',
            fontWeight: 700,
          }}
        >
          Seat {seatNumber + 1}
        </div>
        <span style={{ fontSize: '10px', color: 'var(--text-muted)' }}>Empty</span>
      </div>
    );
  }

  const isCurrentTurn = activePlayerId === player.playerId;
  const isDropped = player.status === 'DROPPED';
  const isDeclared = player.status === 'DECLARED';
  const avatar = getAvatarForPlayer(player.displayName || player.playerId);

  // Inward card fan orientation
  const isRightSide =
    position === 'right' || position === 'top-right' || position === 'bottom-right';

  const avatarElement = (
    <div className="opponent-avatar-wrap">
      {/* Turn Spotlight Glow on Felt */}
      {isCurrentTurn && (
        <div
          style={{
            position: 'absolute',
            inset: '-14px',
            borderRadius: '50%',
            background: 'radial-gradient(circle, rgba(251, 191, 36, 0.45) 0%, transparent 70%)',
            pointerEvents: 'none',
            animation: 'pulse 1.8s infinite ease-in-out',
            zIndex: 1,
          }}
        />
      )}

      {/* Turn Timer Ring */}
      {isCurrentTurn && (
        <div className="opponent-avatar-timer">
          <TurnTimerRing
            turnDeadline={turnDeadline ?? null}
            strokeWidth={4.5}
            showBadge={false}
          />
        </div>
      )}

      {/* Outer Metallic Bezel */}
      <div
        className="opponent-avatar-bezel"
        style={{
          background: isCurrentTurn
            ? 'linear-gradient(135deg, #fef08a 0%, #d97706 50%, #fef08a 100%)'
            : 'linear-gradient(135deg, rgba(255,255,255,0.4) 0%, rgba(212,175,55,0.6) 50%, rgba(0,0,0,0.6) 100%)',
          boxShadow: isCurrentTurn
            ? '0 0 18px rgba(251, 191, 36, 0.7), 0 4px 12px rgba(0,0,0,0.6)'
            : '0 4px 12px rgba(0,0,0,0.55)',
        }}
      >
        {/* Character Illustration SVG */}
        <div
          style={{
            width: '100%',
            height: '100%',
            borderRadius: '50%',
            overflow: 'hidden',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
          }}
        >
          {avatar.renderSvg(51)}
        </div>

        {/* Status Overlay Badges */}
        {isDropped && (
          <span
            style={{
              position: 'absolute',
              bottom: '-3px',
              background: 'linear-gradient(135deg, #ef4444, #991b1b)',
              color: '#ffffff',
              fontSize: '8px',
              fontWeight: 900,
              padding: '1px 5px',
              borderRadius: '4px',
              boxShadow: '0 2px 4px rgba(0,0,0,0.5)',
              zIndex: 15,
            }}
          >
            DROPPED
          </span>
        )}

        {isDeclared && (
          <span
            style={{
              position: 'absolute',
              bottom: '-3px',
              background: 'linear-gradient(135deg, #10b981, #047857)',
              color: '#ffffff',
              fontSize: '8px',
              fontWeight: 900,
              padding: '1px 5px',
              borderRadius: '4px',
              boxShadow: '0 2px 4px rgba(0,0,0,0.5)',
              zIndex: 15,
            }}
          >
            WON
          </span>
        )}
      </div>

      {/* VIP Badge Pill */}
      {!isDropped && !isDeclared && (
        <div
          style={{
            position: 'absolute',
            bottom: '-4px',
            left: '50%',
            transform: 'translateX(-50%)',
            background: 'linear-gradient(135deg, #78350f, #451a03)',
            border: '1px solid #fbbf24',
            color: '#fef08a',
            fontSize: '7.5px',
            fontWeight: 900,
            padding: '0.5px 5px',
            borderRadius: '8px',
            letterSpacing: '0.04em',
            whiteSpace: 'nowrap',
            boxShadow: '0 2px 6px rgba(0,0,0,0.6)',
            zIndex: 14,
            display: 'flex',
            alignItems: 'center',
            gap: '2px',
          }}
        >
          <Crown size={8} color="#fbbf24" />
          {avatar.vipTier}
        </div>
      )}
    </div>
  );

  const hasRevealedCards = gameStatus === 'COMPLETED' && player.hand && player.hand.length > 0;
  const cardCount = displayCount ?? player.cardCount;
  const fanSize = Math.min(5, Math.max(0, cardCount));

  const cardFanElement = hasRevealedCards ? (
    <div
      className="opponent-card-fan"
      title={`${player.displayName}'s cards revealed for showdown`}
      style={{
        margin: position === 'left' || position === 'right' ? '0' : '0 2px',
      }}
    >
      <div
        style={{
          background: 'linear-gradient(135deg, rgba(212, 175, 55, 0.3), rgba(15, 23, 42, 0.85))',
          border: '1.5px solid #fbbf24',
          borderRadius: '8px',
          padding: '4px 6px',
          display: 'flex',
          alignItems: 'center',
          gap: '4px',
          boxShadow: '0 0 10px rgba(251, 191, 36, 0.4)',
        }}
      >
        <span style={{ fontSize: '11px' }}>🎴</span>
        <span style={{ fontSize: '9px', fontWeight: 900, color: '#fef08a' }}>SHOWDOWN</span>
      </div>
    </div>
  ) : (
    <div
      className="opponent-card-fan"
      title={`${cardCount} cards in hand`}
      style={{ margin: position === 'left' || position === 'right' ? '0' : '0 2px' }}
    >
      {fanSize > 0 &&
        [...Array(fanSize)].map((_, i) => {
          const mid = (fanSize - 1) / 2;
          const offset = i - mid;
          return (
            <div
              key={i}
              className="opponent-fan-card"
              style={{
                transform: `rotate(${offset * 12}deg) translateY(${Math.abs(offset) * 2}px)`,
                left: `${i * 5 + 4}px`,
              }}
            />
          );
        })}
      <div className="opponent-fan-count">
        {cardCount} {cardCount === 1 ? 'card' : 'cards'}
      </div>
    </div>
  );

  return (
    <div
      id={`seat-${player.playerId}`}
      className={`opponent-seat-pod pos-${position}${isCurrentTurn ? ' active-turn' : ''}`}
      style={{
        display: 'flex',
        flexDirection: 'column',
        alignItems: 'center',
        gap: '4px',
        zIndex: isCurrentTurn ? 20 : 10,
      }}
    >
      {/* Upper Pod: Avatar & Card Fan */}
      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          gap: '8px',
          flexDirection: isRightSide ? 'row-reverse' : 'row',
        }}
      >
        {avatarElement}
        {cardFanElement}
      </div>

      {/* Name and Status Tag */}
      <div
        style={{
          background: isCurrentTurn
            ? 'linear-gradient(135deg, rgba(26, 46, 32, 0.95), rgba(10, 22, 16, 0.95))'
            : 'rgba(10, 22, 16, 0.88)',
          backdropFilter: 'blur(12px)',
          padding: '2px 10px',
          borderRadius: '14px',
          border: isCurrentTurn ? '1.5px solid #fbbf24' : '1px solid rgba(255, 255, 255, 0.14)',
          boxShadow: isCurrentTurn ? '0 0 12px rgba(251, 191, 36, 0.35)' : '0 4px 10px rgba(0, 0, 0, 0.5)',
          textAlign: 'center',
          minWidth: '92px',
          maxWidth: '125px',
        }}
      >
        <div
          style={{
            fontSize: 'var(--ui-font-sm, 11px)',
            fontWeight: 800,
            color: isCurrentTurn ? '#fef08a' : '#ffffff',
            whiteSpace: 'nowrap',
            overflow: 'hidden',
            textOverflow: 'ellipsis',
          }}
        >
          {player.displayName}
        </div>
        {gameStatus === 'COMPLETED' && (
          <div style={{ fontSize: '10px', color: 'var(--gold-light)', fontWeight: 800 }}>
            {player.score} pts
          </div>
        )}
      </div>
    </div>
  );
};
