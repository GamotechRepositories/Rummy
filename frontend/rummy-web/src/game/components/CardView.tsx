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

export const CardView: React.FC<CardViewProps> = React.memo(({
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
  // HTML5 DnD is unreliable on touch phones — PlayerHand uses pointer drag there
  const useNativeDrag =
    isDraggable &&
    typeof window !== 'undefined' &&
    !window.matchMedia('(pointer: coarse)').matches;

  const cornerMarks = (
    <>
      <span
        className={`card-corner-rank${rankStr.length > 1 ? ' card-corner-rank--wide' : ''}`}
      >
        {rankStr}
      </span>
      <span className="card-corner-suit">{suitChar}</span>
    </>
  );

  return (
    <div
      id={`card-${card.instanceId}`}
      className={[
        'rummy-card',
        size !== 'normal' ? `rummy-card--${size}` : '',
        isSelected ? 'selected' : '',
        isDraggable ? 'is-draggable' : '',
        isRed ? 'is-red' : 'is-black',
        className,
      ]
        .filter(Boolean)
        .join(' ')}
      style={{
        cursor: isDraggable ? 'grab' : onClick ? 'pointer' : undefined,
      }}
      draggable={useNativeDrag}
      onDragStart={useNativeDrag ? onDragStart : undefined}
      onDragEnd={useNativeDrag ? onDragEnd : undefined}
      onClick={onClick}
      onDoubleClick={onDoubleClick}
      role="button"
      tabIndex={0}
      aria-label={`${card.rank} of ${card.suit}`}
    >
      {joker && (
        <div className="card-wild-badge">{isPrinted ? 'PRINTED' : 'WILD'}</div>
      )}

      <div className="card-corner card-corner--tl">{cornerMarks}</div>
      <div className="card-center-pip" aria-hidden>
        {isPrinted ? '🃏' : suitChar}
      </div>
      <div className="card-corner card-corner--br">{cornerMarks}</div>
    </div>
  );
});

CardView.displayName = 'CardView';
