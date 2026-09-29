import React from 'react';
import type { OpponentView } from '../types/game';
import { TurnTimerRing } from './TurnTimerRing';
import { getAvatarForPlayer, photoForCharacter } from '../utils/avatarUtils';
import { UserPlus } from 'lucide-react';
import { useGameStore } from '../store/useGameStore';

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
        className={`opponent-seat-pod pos-${position} empty-seat-pod`}
        style={{
          display: 'flex',
          flexDirection: 'column',
          alignItems: 'center',
          gap: '5px',
          zIndex: 8,
          pointerEvents: 'none',
        }}
      >
        {/* Visual Seat Beacon / Icon */}
        <div
          className="empty-seat-circle"
          style={{
            position: 'relative',
            width: '52px',
            height: '52px',
            borderRadius: '50%',
            background: 'radial-gradient(circle at 35% 30%, rgba(28, 48, 36, 0.95) 0%, rgba(8, 18, 12, 0.98) 100%)',
            border: '2px dashed rgba(251, 191, 36, 0.8)',
            boxShadow: '0 0 18px rgba(251, 191, 36, 0.35), 0 4px 14px rgba(0, 0, 0, 0.7), inset 0 0 14px rgba(251, 191, 36, 0.15)',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            color: '#fbbf24',
          }}
        >
          <UserPlus size={22} strokeWidth={2.2} style={{ opacity: 0.95 }} />
        </div>

        {/* Seat Label & Empty Badge */}
        <div
          className="empty-seat-nameplate"
          style={{
            background: 'linear-gradient(180deg, rgba(20, 36, 26, 0.94) 0%, rgba(8, 18, 12, 0.98) 100%)',
            backdropFilter: 'blur(8px)',
            border: '1px solid rgba(251, 191, 36, 0.45)',
            borderRadius: '12px',
            padding: '3px 10px',
            boxShadow: '0 4px 12px rgba(0, 0, 0, 0.65), 0 0 10px rgba(251, 191, 36, 0.18)',
            textAlign: 'center',
            minWidth: '82px',
            display: 'flex',
            flexDirection: 'column',
            alignItems: 'center',
            gap: '1px',
          }}
        >
          <div
            style={{
              fontSize: '11.5px',
              fontWeight: 800,
              color: '#fef08a',
              letterSpacing: '0.3px',
              whiteSpace: 'nowrap',
              textShadow: '0 1px 3px rgba(0, 0, 0, 0.8)',
            }}
          >
            Seat {seatNumber + 1}
          </div>
          <div
            style={{
              fontSize: '9.5px',
              fontWeight: 700,
              color: '#cbd5e1',
              letterSpacing: '0.6px',
              textTransform: 'uppercase',
              display: 'flex',
              alignItems: 'center',
              gap: '4px',
            }}
          >
            <span
              style={{
                width: '5px',
                height: '5px',
                borderRadius: '50%',
                background: '#fbbf24',
                boxShadow: '0 0 6px #fbbf24',
                display: 'inline-block',
              }}
            />
            Empty
          </div>
        </div>
      </div>
    );
  }

  const dealInProgress = useGameStore((s) => s.dealInProgress);
  const gameState = useGameStore((s) => s.gameState);
  const threshold = gameState?.eliminationThreshold ?? 0;
  const isPool = threshold > 0;
  const isDeals = (gameState?.totalDeals ?? 0) > 1 || (gameState?.rulesetId ?? '').toUpperCase().includes('DEAL');
  const chipBalance = player.chipBalance ?? 0;
  const isEliminated = player.status === 'ELIMINATED' || !!player.isEliminated;
  const isCurrentTurn = activePlayerId === player.playerId && !dealInProgress && !isEliminated;
  const isDropped = player.status === 'DROPPED';
  const isDeclared = player.status === 'DECLARED';
  const isDealer = seatNumber === gameState?.dealerSeatIndex;
  const isDangerZone = isPool && !isEliminated && (player.cumulativeScore ?? 0) >= threshold * 0.75;
  const avatar = getAvatarForPlayer(player.displayName || player.playerId);
  const resolvedPhoto = player.avatarId ? photoForCharacter(player.avatarId) : avatar.photo;

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
          {resolvedPhoto ? (
            <img
              className="opponent-avatar-photo"
              src={resolvedPhoto}
              alt=""
              draggable={false}
            />
          ) : (
            avatar.renderSvg(51)
          )}
        </div>

        {/* Status Overlay Badges */}
        {isEliminated && (
          <span
            style={{
              position: 'absolute',
              bottom: '-3px',
              background: 'linear-gradient(135deg, #b91c1c, #7f1d1d)',
              color: '#ffffff',
              fontSize: '8px',
              fontWeight: 900,
              padding: '1px 5px',
              borderRadius: '4px',
              boxShadow: '0 2px 4px rgba(0,0,0,0.5)',
              border: '1px solid rgba(254, 202, 202, 0.4)',
              zIndex: 15,
            }}
          >
            OUT
          </span>
        )}

        {!isEliminated && isDropped && (
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

        {!isEliminated && isDeclared && (
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

      {/* Dealer Button Puck - placed above bezel and timer ring */}
      {isDealer && (
        <div className="dealer-puck" title="Dealer for this deal">
          D
        </div>
      )}
    </div>
  );

  const hasRevealedCards = gameStatus === 'COMPLETED' && player.hand && player.hand.length > 0;
  const cardCount = displayCount ?? player.cardCount;
  const fanSize = Math.min(5, Math.max(0, cardCount));

  const cardFanElement = isEliminated ? null : hasRevealedCards ? (
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
      className={`opponent-seat-pod pos-${position}${isCurrentTurn ? ' active-turn' : ''}${isEliminated ? ' eliminated' : ''}`}
      style={{
        display: 'flex',
        flexDirection: 'column',
        alignItems: 'center',
        gap: '4px',
        zIndex: isCurrentTurn ? 20 : 10,
        opacity: isEliminated ? 0.65 : 1,
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
            : isEliminated
              ? 'rgba(40, 10, 10, 0.85)'
              : 'rgba(10, 22, 16, 0.88)',
          backdropFilter: 'blur(12px)',
          padding: '2px 10px',
          borderRadius: '14px',
          border: isCurrentTurn
            ? '1.5px solid #fbbf24'
            : isEliminated
              ? '1px solid rgba(239, 68, 68, 0.4)'
              : '1px solid rgba(255, 255, 255, 0.14)',
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
            color: isCurrentTurn ? '#fef08a' : isEliminated ? '#fca5a5' : '#ffffff',
            whiteSpace: 'nowrap',
            overflow: 'hidden',
            textOverflow: 'ellipsis',
          }}
        >
          {player.displayName}
        </div>
        {isPool ? (
          isDangerZone ? (
            <div
              className="danger-zone-pill"
              style={{
                fontSize: '9.5px',
                fontWeight: 900,
                marginTop: '2px',
                padding: '1px 6px',
                borderRadius: '4px',
                display: 'inline-flex',
                alignItems: 'center',
                gap: '3px',
              }}
              title="Danger Zone: One drop or loss will eliminate this player!"
            >
              <span>🔥</span>
              <span>{player.cumulativeScore ?? player.score ?? 0}/{threshold}</span>
            </div>
          ) : (
            <div
              style={{
                fontSize: '10px',
                fontWeight: 800,
                marginTop: '1px',
                color: isEliminated
                  ? '#ef4444'
                  : (player.cumulativeScore ?? 0) >= threshold * 0.5
                    ? '#fcd34d'
                    : '#6ee7b7',
              }}
            >
              {isEliminated ? 'OUT' : `${player.cumulativeScore ?? player.score ?? 0}/${threshold}`}
            </div>
          )
        ) : isDeals ? (
          <div
            style={{
              fontSize: '10.5px',
              fontWeight: 800,
              marginTop: '1px',
              color: '#fbbf24',
              display: 'inline-flex',
              alignItems: 'center',
              gap: '2px',
            }}
          >
            <span>🪙</span>
            <span>{chipBalance}</span>
            {gameStatus === 'COMPLETED' && player.score !== undefined && (
              <span style={{ color: '#94a3b8', fontSize: '9.5px', marginLeft: '2px' }}>
                ({player.score}p)
              </span>
            )}
          </div>
        ) : (
          gameStatus === 'COMPLETED' && (
            <div style={{ fontSize: '10px', color: 'var(--gold-light)', fontWeight: 800 }}>
              {player.score} pts
            </div>
          )
        )}
      </div>
    </div>
  );
};
