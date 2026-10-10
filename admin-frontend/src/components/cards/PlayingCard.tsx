import React from 'react';

export interface CardData {
  suit: 'HEARTS' | 'DIAMONDS' | 'SPADES' | 'CLUBS' | 'JOKER';
  suitSymbol: '♥' | '♦' | '♠' | '♣' | '🃏';
  rank: string;
  rankName?: string;
  isRed: boolean;
  isWildJoker?: boolean;
  isPrintedJoker?: boolean;
  instanceId?: string;
}

interface PlayingCardProps {
  card: CardData;
  size?: 'sm' | 'md' | 'lg';
  isCutJoker?: boolean;
  label?: string;
}

export const PlayingCard: React.FC<PlayingCardProps> = ({
  card,
  size = 'md',
  isCutJoker = false,
  label,
}) => {
  const isRed = card.isRed;
  const isJoker = card.isPrintedJoker || card.rank === 'JOKER';
  const isWild = card.isWildJoker;

  // Dimensions based on size
  let width = 56;
  let height = 80;
  let fontSize = 14;
  let centerSymbolSize = 24;

  if (size === 'sm') {
    width = 44;
    height = 64;
    fontSize = 11;
    centerSymbolSize = 18;
  } else if (size === 'lg') {
    width = 72;
    height = 104;
    fontSize = 18;
    centerSymbolSize = 34;
  }

  const color = isJoker ? '#8b5cf6' : isRed ? '#dc2626' : '#0f172a';

  return (
    <div style={{ display: 'inline-flex', flexDirection: 'column', alignItems: 'center', gap: '3px' }}>
      <div
        style={{
          width: `${width}px`,
          height: `${height}px`,
          backgroundColor: '#ffffff',
          borderRadius: size === 'sm' ? '5px' : '7px',
          border: isWild || isCutJoker
            ? '2px solid #fbbf24'
            : isJoker
            ? '2px solid #a855f7'
            : '1px solid rgba(0, 0, 0, 0.2)',
          boxShadow: isWild || isCutJoker
            ? '0 0 10px rgba(251, 191, 36, 0.4), 0 2px 6px rgba(0, 0, 0, 0.3)'
            : '0 2px 6px rgba(0, 0, 0, 0.25)',
          color,
          display: 'flex',
          flexDirection: 'column',
          justifyContent: 'space-between',
          padding: size === 'sm' ? '3px 4px' : '4px 6px',
          position: 'relative',
          userSelect: 'none',
          boxSizing: 'border-box',
          overflow: 'hidden',
          transition: 'transform 0.15s ease',
        }}
        title={`${card.rankName || card.rank} of ${card.suit}${isWild ? ' (Wild Joker)' : ''}`}
      >
        {/* Top-Left Rank & Mini Suit */}
        <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', lineHeight: 1 }}>
          <span style={{ fontSize: `${fontSize}px`, fontWeight: 800, fontFamily: 'var(--font-mono)' }}>
            {card.rank}
          </span>
          <span style={{ fontSize: `${fontSize - 2}px`, marginTop: '1px' }}>
            {card.suitSymbol}
          </span>
        </div>

        {/* Center Big Symbol */}
        <div
          style={{
            position: 'absolute',
            top: '50%',
            left: '50%',
            transform: 'translate(-50%, -50%)',
            fontSize: `${centerSymbolSize}px`,
            opacity: 0.9,
            pointerEvents: 'none',
          }}
        >
          {card.suitSymbol}
        </div>

        {/* Bottom-Right Inverted Rank */}
        <div
          style={{
            display: 'flex',
            flexDirection: 'column',
            alignItems: 'center',
            lineHeight: 1,
            transform: 'rotate(180deg)',
          }}
        >
          <span style={{ fontSize: `${fontSize}px`, fontWeight: 800, fontFamily: 'var(--font-mono)' }}>
            {card.rank}
          </span>
          <span style={{ fontSize: `${fontSize - 2}px`, marginTop: '1px' }}>
            {card.suitSymbol}
          </span>
        </div>

        {/* Wild Joker Gold Ribbon Badge */}
        {(isWild || isCutJoker) && (
          <div
            style={{
              position: 'absolute',
              bottom: 0,
              left: 0,
              right: 0,
              background: 'linear-gradient(90deg, #d97706, #fbbf24)',
              color: '#000',
              fontSize: size === 'sm' ? '7px' : '8.5px',
              fontWeight: 800,
              textAlign: 'center',
              textTransform: 'uppercase',
              letterSpacing: '0.3px',
              padding: '1px 0',
            }}
          >
            {isCutJoker ? 'CUT JOKER' : 'WILD'}
          </div>
        )}
      </div>

      {label && (
        <span style={{ fontSize: '10px', color: 'var(--text-dim)', fontWeight: 500 }}>
          {label}
        </span>
      )}
    </div>
  );
};
