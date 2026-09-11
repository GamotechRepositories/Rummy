import React from 'react';
import { useGameStore } from '../store/useGameStore';
import { CardView } from './CardView';
import type { GroupValidationType } from '../types/game';
import { Layers, ArrowUpDown, XCircle } from 'lucide-react';
import { soundEngine } from '../audio/soundEngine';
import { socketClient } from '../websocket/GameSocketClient';

const GROUP_LABELS: Record<GroupValidationType, { title: string; color: string; bg: string }> = {
  PURE_SEQUENCE: { title: '✓ Pure run', color: 'var(--color-pure)', bg: 'rgba(16,185,129,0.2)' },
  IMPURE_SEQUENCE: { title: '★ Run', color: 'var(--color-impure)', bg: 'rgba(245,158,11,0.2)' },
  SET: { title: '◆ Set', color: 'var(--color-set)', bg: 'rgba(59,130,246,0.2)' },
  INVALID: { title: 'Not a group', color: 'var(--color-invalid)', bg: 'rgba(239,68,68,0.2)' },
};

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
  const hasPure = groups.some((g) => g.groupType === 'PURE_SEQUENCE');

  return (
    <div
      style={{
        display: 'flex',
        flexDirection: 'column',
        gap: 6,
        width: '100%',
        maxWidth: 1100,
        margin: '0 auto',
        height: '100%',
        minHeight: 0,
      }}
    >
      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between',
          gap: 8,
          padding: '4px 8px',
          flexWrap: 'wrap',
        }}
      >
        <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
          <button
            id="btn-group-cards"
            type="button"
            className="btn-secondary"
            onClick={() => {
              soundEngine.play('group');
              groupSelectedCards();
            }}
            disabled={selectedCardIds.length < 2}
            style={{ padding: '5px 10px', fontSize: 12 }}
          >
            <Layers size={13} />
            Group{selectedCardIds.length >= 2 ? ` (${selectedCardIds.length})` : ''}
          </button>
          <button
            id="btn-sort-cards"
            type="button"
            className="btn-secondary"
            onClick={() => {
              soundEngine.play('sort');
              autoSortHand();
            }}
            style={{ padding: '5px 10px', fontSize: 12 }}
          >
            <ArrowUpDown size={13} />
            Sort
          </button>
          {selectedCardIds.length > 0 && (
            <button
              type="button"
              className="btn-secondary"
              onClick={clearSelection}
              style={{ padding: '5px 8px', fontSize: 12 }}
            >
              <XCircle size={13} />
            </button>
          )}
        </div>
        <span
          style={{
            fontSize: 11,
            fontWeight: 700,
            padding: '3px 8px',
            borderRadius: 8,
            background: hasPure ? 'rgba(16,185,129,0.15)' : 'rgba(245,158,11,0.12)',
            color: hasPure ? '#34d399' : '#fbbf24',
          }}
        >
          {hasPure ? 'Pure run ✓' : 'Need pure run'}
        </span>
      </div>

      <div
        style={{
          display: 'flex',
          flexWrap: 'nowrap',
          overflowX: 'auto',
          overflowY: 'hidden',
          gap: 10,
          padding: '4px 2px 8px',
          flex: 1,
          minHeight: 0,
          alignItems: 'flex-end',
          WebkitOverflowScrolling: 'touch',
        }}
      >
        {groups.map((group) => {
          const label = GROUP_LABELS[group.groupType] ?? GROUP_LABELS.INVALID;
          return (
            <div
              key={group.id}
              style={{
                display: 'flex',
                flexDirection: 'column',
                alignItems: 'center',
                gap: 4,
                background: 'rgba(15, 23, 42, 0.45)',
                border: '1px solid rgba(255,255,255,0.08)',
                borderRadius: 10,
                padding: '4px 6px 6px',
                flexShrink: 0,
              }}
            >
              <div
                style={{
                  backgroundColor: label.bg,
                  border: `1px solid ${label.color}`,
                  color: label.color,
                  padding: '1px 6px',
                  borderRadius: 10,
                  fontSize: 10,
                  fontWeight: 700,
                  whiteSpace: 'nowrap',
                }}
              >
                {label.title}
              </div>
              <div style={{ display: 'flex' }}>
                {group.cards.map((card, idx) => (
                  <div
                    key={card.instanceId}
                    style={{ marginLeft: idx === 0 ? 0 : 'var(--card-overlap)' }}
                  >
                    <CardView
                      card={card}
                      wildJoker={wildJoker}
                      isSelected={selectedCardIds.includes(card.instanceId)}
                      onClick={() => {
                        soundEngine.play('select');
                        toggleSelectCard(card.instanceId);
                      }}
                      onDoubleClick={() => {
                        // Fast discard: double-tap selected/any card during discard phase
                        const { gameState: gs } = useGameStore.getState();
                        if (!gs?.isMyTurn) return;
                        const phase = String(gs.turnPhase || '');
                        if (phase !== 'DISCARD' && phase !== 'AWAITING_DISCARD') return;
                        soundEngine.play('discard');
                        useGameStore.getState().clearSelection();
                        socketClient.discard(card.instanceId);
                      }}
                    />
                  </div>
                ))}
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
};
