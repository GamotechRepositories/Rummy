import React, { useState } from 'react';
import { useGameStore } from '../store/useGameStore';
import { CardView } from './CardView';
import type { GroupValidationType } from '../types/game';
import { Layers, ArrowUpDown, XCircle, Plus } from 'lucide-react';
import { soundEngine } from '../audio/soundEngine';
import { socketClient } from '../websocket/GameSocketClient';

const GROUP_LABELS: Record<GroupValidationType, { title: string; color: string; bg: string }> = {
  PURE_SEQUENCE: { title: '✓ Pure run', color: 'var(--color-pure)', bg: 'rgba(16,185,129,0.2)' },
  IMPURE_SEQUENCE: { title: '★ Run', color: 'var(--color-impure)', bg: 'rgba(245,158,11,0.2)' },
  SET: { title: '◆ Set', color: 'var(--color-set)', bg: 'rgba(59,130,246,0.2)' },
  INVALID: { title: 'Not a group', color: 'var(--color-invalid)', bg: 'rgba(239,68,68,0.2)' },
};

const DND_MIME = 'application/x-rummy-cards';

export const PlayerHand: React.FC = () => {
  const {
    groups,
    selectedCardIds,
    gameState,
    toggleSelectCard,
    groupSelectedCards,
    moveCardsToGroup,
    autoSortHand,
    clearSelection,
  } = useGameStore();

  const [dropTargetId, setDropTargetId] = useState<string | null>(null);
  const [dragging, setDragging] = useState(false);

  const wildJoker = gameState?.cutJoker ?? null;
  const hasPure = groups.some((g) => g.groupType === 'PURE_SEQUENCE');
  const totalCards = groups.reduce((n, g) => n + g.cards.length, 0);

  const resolveDragIds = (cardId: string): string[] => {
    if (selectedCardIds.includes(cardId) && selectedCardIds.length > 1) {
      return [...selectedCardIds];
    }
    return [cardId];
  };

  const handleCardDragStart = (e: React.DragEvent, cardId: string) => {
    const ids = resolveDragIds(cardId);
    const payload = JSON.stringify(ids);
    e.dataTransfer.setData(DND_MIME, payload);
    e.dataTransfer.setData('text/plain', payload);
    e.dataTransfer.effectAllowed = 'move';
    setDragging(true);
    (e.currentTarget as HTMLElement).classList.add('dragging');
  };

  const handleCardDragEnd = (e: React.DragEvent) => {
    (e.currentTarget as HTMLElement).classList.remove('dragging');
    setDragging(false);
    setDropTargetId(null);
  };

  const parseDragIds = (e: React.DragEvent): string[] => {
    try {
      const raw = e.dataTransfer.getData(DND_MIME) || e.dataTransfer.getData('text/plain');
      if (!raw) return [];
      const parsed = JSON.parse(raw) as unknown;
      return Array.isArray(parsed) ? parsed.filter((x): x is string => typeof x === 'string') : [];
    } catch {
      return [];
    }
  };

  const allowDrop = (e: React.DragEvent, targetId: string) => {
    e.preventDefault();
    e.dataTransfer.dropEffect = 'move';
    if (dropTargetId !== targetId) setDropTargetId(targetId);
  };

  const handleDropOnGroup = (e: React.DragEvent, targetGroupId: string | 'NEW') => {
    e.preventDefault();
    e.stopPropagation();
    const ids = parseDragIds(e);
    setDropTargetId(null);
    setDragging(false);
    if (!ids.length) return;

    // Dropping onto same group with no other cards removed is a no-op for UX sound
    if (targetGroupId !== 'NEW') {
      const sourceOnly = groups.find((g) => g.id === targetGroupId);
      if (sourceOnly && ids.every((id) => sourceOnly.cards.some((c) => c.instanceId === id))
          && ids.length === sourceOnly.cards.length) {
        return;
      }
    }

    soundEngine.play('group');
    moveCardsToGroup(ids, targetGroupId);
  };

  return (
    <div className="player-hand">
      <div className="player-hand-toolbar">
        <div className="player-hand-actions">
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
          <span className="player-hand-hint">
            {dragging ? 'Drop on a group tray' : 'Drag cards onto a group · or select & Group'}
          </span>
          <span className="player-hand-hint player-hand-hint-short">
            {dragging ? 'Drop here' : 'Drag to group'}
          </span>
        </div>
        <span className={`player-hand-pure${hasPure ? ' ok' : ''}`}>
          {hasPure ? 'Pure run ✓' : 'Need pure run'}
        </span>
      </div>

      <div className="player-hand-tray">
        {totalCards === 0 && (
          <div className="player-hand-empty">Your cards will appear here after the deal.</div>
        )}

        {groups.map((group) => {
          const label = GROUP_LABELS[group.groupType] ?? GROUP_LABELS.INVALID;
          const isOver = dropTargetId === group.id;
          return (
            <div
              key={group.id}
              className={`hand-group${isOver ? ' drop-over' : ''} hand-group--${group.groupType.toLowerCase()}`}
              onDragOver={(e) => allowDrop(e, group.id)}
              onDragLeave={() => {
                if (dropTargetId === group.id) setDropTargetId(null);
              }}
              onDrop={(e) => handleDropOnGroup(e, group.id)}
            >
              <div
                className="hand-group-label"
                style={{
                  backgroundColor: label.bg,
                  borderColor: label.color,
                  color: label.color,
                }}
              >
                {label.title}
              </div>
              <div className="hand-group-cards">
                {group.cards.map((card, idx) => (
                  <div
                    key={card.instanceId}
                    className="hand-card-slot"
                    style={{ marginLeft: idx === 0 ? 0 : 'var(--card-overlap)' }}
                  >
                    <CardView
                      card={card}
                      wildJoker={wildJoker}
                      isSelected={selectedCardIds.includes(card.instanceId)}
                      isDraggable
                      onDragStart={(e) => handleCardDragStart(e, card.instanceId)}
                      onDragEnd={handleCardDragEnd}
                      onClick={() => {
                        soundEngine.play('select');
                        toggleSelectCard(card.instanceId);
                      }}
                      onDoubleClick={() => {
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

        {totalCards > 0 && (
          <div
            className={`hand-new-group-zone${dropTargetId === 'NEW' ? ' drop-over' : ''}`}
            onDragOver={(e) => allowDrop(e, 'NEW')}
            onDragLeave={() => {
              if (dropTargetId === 'NEW') setDropTargetId(null);
            }}
            onDrop={(e) => handleDropOnGroup(e, 'NEW')}
          >
            <Plus size={18} />
            <span>New group</span>
          </div>
        )}
      </div>
    </div>
  );
};
