import React from 'react';
import type { CardInstance, Rank, Suit } from '../types/game';
import { isJoker } from '../rules/clientValidator';

interface CardViewProps {
  card: CardInstance;
  wildJoker: CardInstance | null;
  isSelected?: boolean;
  onClick?: () => void;
  isDraggable?: boolean;
  className?: string;
  size?: 'normal' | 'small' | 'large';
}

const SUIT_SYMBOLS: Record<Suit, string> = {
  HEARTS: '♥',
  DIAMONDS: '♦',
  CLUBS: '♣',
  SPADES: '♠',
  NONE: '★',
};

const RANK_SHORT: Record<Rank, string> = {
  TWO: '2',
  THREE: '3',
  FOUR: '4',
  FIVE: '5',
  SIX: '6',
  SEVEN: '7',
  EIGHT: '8',
  NINE: '9',
  TEN: '10',
  JACK: 'J',
  QUEEN: 'Q',
  KING: 'K',
  ACE: 'A',
  JOKER: 'JKR',
};

export const CardView: React.FC<CardViewProps> = ({
  card,
  wildJoker,
  isSelected = false,
  onClick,
  className = '',
  size = 'normal',
}) => {
  const isRed = card.suit === 'HEARTS' || card.suit === 'DIAMONDS';
  const suitChar = SUIT_SYMBOLS[card.suit] || '';
  const rankStr = RANK_SHORT[card.rank] || '';
  const joker = isJoker(card, wildJoker);
  const isPrinted = card.printedJoker;

  const scaleStyle: React.CSSProperties =
    size === 'small'
      ? { width: '52px', height: '76px', fontSize: '12px' }
      : size === 'large'
      ? { width: '84px', height: '120px', fontSize: '16px' }
      : {};

  return (
    <div
      id={`card-${card.instanceId}`}
      className={`rummy-card ${isSelected ? 'selected' : ''} ${className}`}
      style={{
        ...scaleStyle,
        color: isRed ? 'var(--card-red)' : 'var(--card-black)',
      }}
      onClick={onClick}
      role="button"
      tabIndex={0}
      aria-label={`${card.rank} of ${card.suit}`}
    >
      {/* Joker Ribbon */}
      {joker && (
        <div
          style={{
            position: 'absolute',
            top: '-6px',
            right: '-6px',
            background: 'linear-gradient(135deg, #f59e0b, #d97706)',
            color: '#ffffff',
            fontSize: '9px',
            fontWeight: 800,
            padding: '1px 5px',
            borderRadius: '4px',
            boxShadow: '0 2px 4px rgba(0,0,0,0.3)',
            zIndex: 10,
            letterSpacing: '0.5px',
          }}
        >
          {isPrinted ? 'PRINTED' : 'WILD'}
        </div>
      )}

      {/* Top Left Rank & Suit */}
      <div style={{ lineHeight: 1.1, textAlign: 'left' }}>
        <div style={{ fontWeight: 800, fontSize: size === 'small' ? '12px' : '15px' }}>
          {rankStr}
        </div>
        <div style={{ fontSize: size === 'small' ? '12px' : '16px' }}>{suitChar}</div>
      </div>

      {/* Center Watermark / Symbol */}
      <div
        style={{
          position: 'absolute',
          top: '50%',
          left: '50%',
          transform: 'translate(-50%, -50%)',
          fontSize: size === 'small' ? '22px' : '32px',
          opacity: 0.85,
        }}
      >
        {isPrinted ? '🃏' : suitChar}
      </div>

      {/* Bottom Right Inverted */}
      <div
        style={{
          lineHeight: 1.1,
          textAlign: 'right',
          transform: 'rotate(180deg)',
        }}
      >
        <div style={{ fontWeight: 800, fontSize: size === 'small' ? '12px' : '15px' }}>
          {rankStr}
        </div>
        <div style={{ fontSize: size === 'small' ? '12px' : '16px' }}>{suitChar}</div>
      </div>
    </div>
  );
};
