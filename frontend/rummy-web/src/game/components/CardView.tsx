import React from 'react';
import type { CardInstance, Rank, Suit } from '../types/game';
import { isJoker } from '../rules/clientValidator';

interface CardViewProps {
  card: CardInstance;
  wildJoker: CardInstance | null;
  isSelected?: boolean;
  onClick?: () => void;
  onDoubleClick?: () => void;
  isDraggable?: boolean;
  onDragStart?: (e: React.DragEvent<HTMLDivElement>) => void;
  onDragEnd?: (e: React.DragEvent<HTMLDivElement>) => void;
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
  onDoubleClick,
  isDraggable = false,
  onDragStart,
  onDragEnd,
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
      ? { width: 'calc(var(--card-w) * 0.72)', height: 'calc(var(--card-h) * 0.72)', fontSize: '11px' }
      : size === 'large'
        ? { width: 'calc(var(--card-w) * 1.15)', height: 'calc(var(--card-h) * 1.15)', fontSize: '15px' }
        : {};

  return (
    <div
      id={`card-${card.instanceId}`}
      className={`rummy-card ${isSelected ? 'selected' : ''} ${isDraggable ? 'is-draggable' : ''} ${className}`}
      style={{
        ...scaleStyle,
        color: isRed ? 'var(--card-red)' : 'var(--card-black)',
        cursor: isDraggable ? 'grab' : onClick ? 'pointer' : undefined,
      }}
      draggable={isDraggable}
      onDragStart={isDraggable ? onDragStart : undefined}
      onDragEnd={isDraggable ? onDragEnd : undefined}
      onClick={onClick}
      onDoubleClick={onDoubleClick}
      role="button"
      tabIndex={0}
      aria-label={`${card.rank} of ${card.suit}`}
    >
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

      <div style={{ lineHeight: 1.1, textAlign: 'left' }}>
        <div style={{ fontWeight: 800, fontSize: size === 'small' ? '12px' : '15px' }}>
          {rankStr}
        </div>
        <div style={{ fontSize: size === 'small' ? '12px' : '16px' }}>{suitChar}</div>
      </div>

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
