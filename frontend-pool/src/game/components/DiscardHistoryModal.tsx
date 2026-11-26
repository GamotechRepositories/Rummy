import React, { useState, useEffect, useMemo } from 'react';
import { createPortal } from 'react-dom';
import type { CardInstance, Suit } from '../types/game';
import { CardView } from './CardView';
import { History, X, ArrowUpDown, Sparkles } from 'lucide-react';
import { soundEngine } from '../audio/soundEngine';

interface DiscardHistoryModalProps {
  isOpen: boolean;
  onClose: () => void;
  discardHistory: CardInstance[];
  cutJoker: CardInstance | null;
}

type SortOrder = 'newest' | 'oldest';
type SuitFilter = 'ALL' | Suit;

export const DiscardHistoryModal: React.FC<DiscardHistoryModalProps> = ({
  isOpen,
  onClose,
  discardHistory,
  cutJoker,
}) => {
  const [sortOrder, setSortOrder] = useState<SortOrder>('newest');
  const [selectedSuit, setSelectedSuit] = useState<SuitFilter>('ALL');

  // Handle ESC key to close
  useEffect(() => {
    if (!isOpen) return;

    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        soundEngine.play('click');
        onClose();
      }
    };

    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [isOpen, onClose]);

  // Count discarded cards by suit
  const suitCounts = useMemo(() => {
    const counts = {
      ALL: discardHistory.length,
      SPADES: 0,
      HEARTS: 0,
      DIAMONDS: 0,
      CLUBS: 0,
    };
    for (const card of discardHistory) {
      if (card.suit === 'SPADES') counts.SPADES++;
      else if (card.suit === 'HEARTS') counts.HEARTS++;
      else if (card.suit === 'DIAMONDS') counts.DIAMONDS++;
      else if (card.suit === 'CLUBS') counts.CLUBS++;
    }
    return counts;
  }, [discardHistory]);

  // Process cards with original sequence index and filters
  const items = useMemo(() => {
    const list = discardHistory.map((card, idx) => ({
      card,
      turnIndex: idx + 1, // 1-based order: 1 = initial table card
      isLatest: idx === discardHistory.length - 1,
    }));

    // Filter by suit if selected
    const filtered = selectedSuit === 'ALL'
      ? list
      : list.filter((item) => item.card.suit === selectedSuit);

    // Sort order
    if (sortOrder === 'newest') {
      return [...filtered].reverse();
    }
    return filtered;
  }, [discardHistory, selectedSuit, sortOrder]);

  if (!isOpen) return null;

  return createPortal(
    <div
      className="discard-history-overlay"
      onClick={() => {
        soundEngine.play('click');
        onClose();
      }}
      role="presentation"
    >
      <div
        className="discard-history-dialog"
        onClick={(e) => e.stopPropagation()}
        role="dialog"
        aria-modal="true"
        aria-label="Discard history"
      >
        {/* Header */}
        <div className="discard-history-header">
          <div className="discard-history-title-group">
            <div className="discard-history-icon-bubble">
              <History size={18} className="gold-icon" />
            </div>
            <div>
              <div className="discard-history-title-row">
                <h3>Discard History</h3>
                <span className="discard-history-total-badge">
                  {discardHistory.length} {discardHistory.length === 1 ? 'card' : 'cards'}
                </span>
              </div>
              <p className="discard-history-subtitle">
                {sortOrder === 'newest' ? 'Showing most recent discards first' : 'Showing first discards to latest'}
              </p>
            </div>
          </div>

          <div className="discard-history-header-actions">
            <button
              type="button"
              className="discard-history-sort-btn"
              onClick={() => {
                soundEngine.play('click');
                setSortOrder((prev) => (prev === 'newest' ? 'oldest' : 'newest'));
              }}
              title="Toggle sorting order"
            >
              <ArrowUpDown size={14} />
              <span>{sortOrder === 'newest' ? 'Newest First' : 'Oldest First'}</span>
            </button>

            <button
              type="button"
              className="discard-history-close-btn"
              onClick={() => {
                soundEngine.play('click');
                onClose();
              }}
              aria-label="Close discard history"
            >
              <X size={18} />
            </button>
          </div>
        </div>

        {/* Suit Filters */}
        <div className="discard-history-filter-bar">
          <button
            type="button"
            className={`suit-filter-chip${selectedSuit === 'ALL' ? ' is-active' : ''}`}
            onClick={() => {
              soundEngine.play('click');
              setSelectedSuit('ALL');
            }}
          >
            All <span className="chip-count">{suitCounts.ALL}</span>
          </button>
          <button
            type="button"
            className={`suit-filter-chip suit-spades${selectedSuit === 'SPADES' ? ' is-active' : ''}`}
            onClick={() => {
              soundEngine.play('click');
              setSelectedSuit('SPADES');
            }}
          >
            ♠ Spades <span className="chip-count">{suitCounts.SPADES}</span>
          </button>
          <button
            type="button"
            className={`suit-filter-chip suit-hearts${selectedSuit === 'HEARTS' ? ' is-active' : ''}`}
            onClick={() => {
              soundEngine.play('click');
              setSelectedSuit('HEARTS');
            }}
          >
            ♥ Hearts <span className="chip-count">{suitCounts.HEARTS}</span>
          </button>
          <button
            type="button"
            className={`suit-filter-chip suit-diamonds${selectedSuit === 'DIAMONDS' ? ' is-active' : ''}`}
            onClick={() => {
              soundEngine.play('click');
              setSelectedSuit('DIAMONDS');
            }}
          >
            ♦ Diamonds <span className="chip-count">{suitCounts.DIAMONDS}</span>
          </button>
          <button
            type="button"
            className={`suit-filter-chip suit-clubs${selectedSuit === 'CLUBS' ? ' is-active' : ''}`}
            onClick={() => {
              soundEngine.play('click');
              setSelectedSuit('CLUBS');
            }}
          >
            ♣ Clubs <span className="chip-count">{suitCounts.CLUBS}</span>
          </button>
        </div>

        {/* Scrollable Body */}
        <div className="discard-history-body">
          {items.length === 0 ? (
            <div className="discard-history-empty">
              <History size={36} className="empty-icon" />
              <p>No cards discarded in this category yet</p>
            </div>
          ) : (
            <div className="discard-history-grid">
              {items.map((item) => (
                <div
                  key={`${item.card.instanceId}-${item.turnIndex}`}
                  className={`discard-item-pod${item.isLatest ? ' is-top-pile' : ''}`}
                >
                  {item.isLatest && (
                    <div className="top-pile-badge" title="Currently on top of open pile">
                      <Sparkles size={10} />
                      <span>TOP PILE</span>
                    </div>
                  )}
                  <div className="discard-card-wrapper">
                    <CardView card={item.card} wildJoker={cutJoker} size="small" />
                  </div>
                  <span className="discard-seq-tag">#{item.turnIndex}</span>
                </div>
              ))}
            </div>
          )}
        </div>

        {/* Footer */}
        <div className="discard-history-footer">
          <div className="discard-history-tip">
            <span className="tip-dot" />
            <span>Card marked <strong>TOP PILE</strong> is currently open on table</span>
          </div>
          <button
            type="button"
            className="discard-history-done-btn"
            onClick={() => {
              soundEngine.play('click');
              onClose();
            }}
          >
            Close
          </button>
        </div>
      </div>
    </div>,
    document.getElementById('root') || document.body
  );
};
