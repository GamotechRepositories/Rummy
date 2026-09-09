import React from 'react';
import { useGameStore } from '../store/useGameStore';
import { CardView } from './CardView';
import type { GroupValidationType } from '../types/game';
import { Layers, ArrowUpDown, XCircle } from 'lucide-react';

export const PlayerHand: React.FC = () => {
  const {
    groups,
    selectedCardIds,
    gameState,
    toggleSelectCard,
    groupSelectedCards,
    autoSortHand,
    clearSelection,
  } = useGameStore();

  const wildJoker = gameState?.cutJoker ?? null;

  const getGroupBadge = (type: GroupValidationType, points: number) => {
    switch (type) {
      case 'PURE_SEQUENCE':
        return (
          <div
            style={{
              backgroundColor: 'rgba(16, 185, 129, 0.2)',
              border: '1px solid var(--color-pure)',
              color: 'var(--color-pure)',
              padding: '2px 8px',
              borderRadius: '12px',
              fontSize: '11px',
              fontWeight: 700,
              display: 'flex',
              alignItems: 'center',
              gap: '4px',
            }}
          >
            <span>✓ Pure Seq</span>
            <span style={{ opacity: 0.7 }}>({points} pts)</span>
          </div>
        );
      case 'IMPURE_SEQUENCE':
        return (
          <div
            style={{
              backgroundColor: 'rgba(245, 158, 11, 0.2)',
              border: '1px solid var(--color-impure)',
              color: 'var(--color-impure)',
              padding: '2px 8px',
              borderRadius: '12px',
              fontSize: '11px',
              fontWeight: 700,
              display: 'flex',
              alignItems: 'center',
              gap: '4px',
            }}
          >
            <span>★ Impure Seq</span>
            <span style={{ opacity: 0.7 }}>({points} pts)</span>
          </div>
        );
      case 'SET':
        return (
          <div
            style={{
              backgroundColor: 'rgba(59, 130, 246, 0.2)',
              border: '1px solid var(--color-set)',
              color: 'var(--color-set)',
              padding: '2px 8px',
              borderRadius: '12px',
              fontSize: '11px',
              fontWeight: 700,
              display: 'flex',
              alignItems: 'center',
              gap: '4px',
            }}
          >
            <span>◆ Set</span>
            <span style={{ opacity: 0.7 }}>({points} pts)</span>
          </div>
        );
      default:
        return (
          <div
            style={{
              backgroundColor: 'rgba(239, 68, 68, 0.2)',
              border: '1px solid var(--color-invalid)',
              color: 'var(--color-invalid)',
              padding: '2px 8px',
              borderRadius: '12px',
              fontSize: '11px',
              fontWeight: 700,
              display: 'flex',
              alignItems: 'center',
              gap: '4px',
            }}
          >
            <span>Invalid</span>
            <span style={{ opacity: 0.7 }}>({points} pts)</span>
          </div>
        );
    }
  };

  const totalDeadwood = groups.reduce((acc, g) => {
    // Pure sequences and valid sequences / sets do not contribute to penalty if declaration criteria are met
    return acc + g.deadwoodPoints;
  }, 0);

  return (
    <div
      style={{
        display: 'flex',
        flexDirection: 'column',
        alignItems: 'center',
        gap: '12px',
        width: '100%',
        maxWidth: '1100px',
        margin: '0 auto',
      }}
    >
      {/* Hand Action Toolbar */}
      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between',
          width: '100%',
          padding: '6px 16px',
          background: 'rgba(15, 23, 42, 0.8)',
          borderRadius: '12px',
          backdropFilter: 'blur(8px)',
          border: '1px solid rgba(255, 255, 255, 0.1)',
        }}
      >
        <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
          <button
            id="btn-group-cards"
            className="btn-secondary"
            onClick={groupSelectedCards}
            disabled={selectedCardIds.length < 2}
            style={{ padding: '6px 12px', fontSize: '13px' }}
          >
            <Layers size={14} />
            Group ({selectedCardIds.length})
          </button>

          <button
            id="btn-sort-cards"
            className="btn-secondary"
            onClick={autoSortHand}
            style={{ padding: '6px 12px', fontSize: '13px' }}
          >
            <ArrowUpDown size={14} />
            Auto Sort
          </button>

          {selectedCardIds.length > 0 && (
            <button
              id="btn-clear-selection"
              className="btn-secondary"
              onClick={clearSelection}
              style={{ padding: '6px 10px', fontSize: '13px' }}
            >
              <XCircle size={14} />
              Clear
            </button>
          )}
        </div>

        <div style={{ display: 'flex', alignItems: 'center', gap: '16px' }}>
          <div style={{ fontSize: '13px', color: 'var(--text-muted)' }}>
            Selected: <strong style={{ color: 'var(--gold-accent)' }}>{selectedCardIds.length}</strong>
          </div>
          <div
            style={{
              fontSize: '13px',
              padding: '3px 10px',
              background: 'rgba(239, 68, 68, 0.15)',
              border: '1px solid rgba(239, 68, 68, 0.4)',
              borderRadius: '8px',
              color: '#fca5a5',
            }}
          >
            Raw Deadwood: <strong>{totalDeadwood}</strong> pts
          </div>
        </div>
      </div>

      {/* Card Groups Area */}
      <div
        style={{
          display: 'flex',
          flexWrap: 'nowrap',
          overflowX: 'auto',
          gap: '14px',
          padding: '10px 4px 20px',
          maxWidth: '100%',
        }}
      >
        {groups.map((group) => (
          <div
            key={group.id}
            style={{
              display: 'flex',
              flexDirection: 'column',
              alignItems: 'center',
              gap: '8px',
              background: 'rgba(15, 23, 42, 0.5)',
              border: '1px solid rgba(255, 255, 255, 0.1)',
              borderRadius: '12px',
              padding: '8px 10px',
            }}
          >
            {getGroupBadge(group.groupType, group.deadwoodPoints)}

            <div style={{ display: 'flex', gap: '-24px', position: 'relative' }}>
              {group.cards.map((card, idx) => (
                <div
                  key={card.instanceId}
                  style={{
                    marginLeft: idx === 0 ? 0 : '-34px',
                    transition: 'margin 0.2s ease',
                  }}
                >
                  <CardView
                    card={card}
                    wildJoker={wildJoker}
                    isSelected={selectedCardIds.includes(card.instanceId)}
                    onClick={() => toggleSelectCard(card.instanceId)}
                  />
                </div>
              ))}
            </div>
          </div>
        ))}
      </div>
    </div>
  );
};
