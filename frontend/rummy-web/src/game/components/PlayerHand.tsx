import React, { useState } from 'react';
import { useGameStore } from '../store/useGameStore';
import { CardView } from './CardView';
import type { GroupValidationType } from '../types/game';
import { Plus } from 'lucide-react';
import { soundEngine } from '../audio/soundEngine';
import { socketClient } from '../websocket/GameSocketClient';

const GROUP_LABELS: Record<GroupValidationType, { title: string; color: string; bg: string }> = {
  PURE_SEQUENCE: { title: '✓ Pure run', color: 'var(--color-pure)', bg: 'rgba(16,185,129,0.2)' },
  IMPURE_SEQUENCE: { title: '★ Run', color: 'var(--color-impure)', bg: 'rgba(245,158,11,0.2)' },
  SET: { title: '◆ Set', color: 'var(--color-set)', bg: 'rgba(59,130,246,0.2)' },
  INVALID: { title: 'Not a group', color: 'var(--color-invalid)', bg: 'rgba(239,68,68,0.2)' },
};

const DND_MIME = 'application/x-rummy-cards';

// In-memory fallback if browser dataTransfer payload is restricted
let activeDragCardIds: string[] = [];

export const PlayerHand: React.FC = () => {
  const {
    groups,
    selectedCardIds,
    gameState,
    toggleSelectCard,
    moveCardsToGroup,
  } = useGameStore();

  const [dropTargetId, setDropTargetId] = useState<string | null>(null);

  const wildJoker = gameState?.cutJoker ?? null;

  const resolveDragIds = (cardId: string): string[] => {
    const selected = useGameStore.getState().selectedCardIds;
    if (selected.includes(cardId) && selected.length > 1) {
      return [...selected];
    }
    return [cardId];
  };

  const handleCardDragStart = (e: React.DragEvent, cardId: string) => {
    const ids = resolveDragIds(cardId);
    activeDragCardIds = ids;
    const payload = JSON.stringify(ids);
    try {
      e.dataTransfer.setData(DND_MIME, payload);
      e.dataTransfer.setData('text/plain', payload);
    } catch {
      // Ignore browsers/WebViews with restricted custom MIME
    }
    e.dataTransfer.effectAllowed = 'move';
    (e.currentTarget as HTMLElement).classList.add('dragging');
  };

  const handleCardDragEnd = (e: React.DragEvent) => {
    (e.currentTarget as HTMLElement).classList.remove('dragging');
    setDropTargetId(null);
    setTimeout(() => {
      activeDragCardIds = [];
    }, 250);
  };

  const parseDragIds = (e: React.DragEvent): string[] => {
    try {
      const raw = e.dataTransfer.getData(DND_MIME) || e.dataTransfer.getData('text/plain');
      if (raw) {
        const parsed = JSON.parse(raw) as unknown;
        if (Array.isArray(parsed)) {
          const res = parsed.filter((x): x is string => typeof x === 'string');
          if (res.length > 0) return res;
        }
      }
    } catch {
      // fallback to activeDragCardIds
    }
    return activeDragCardIds.length > 0 ? [...activeDragCardIds] : [];
  };

  const allowDrop = (e: React.DragEvent, targetId: string) => {
    e.preventDefault();
    e.dataTransfer.dropEffect = 'move';
    if (dropTargetId !== targetId) {
      setDropTargetId(targetId);
    }
  };

  const handleDragLeave = (e: React.DragEvent, targetId: string) => {
    const related = e.relatedTarget as Node | null;
    const current = e.currentTarget as HTMLElement;
    if (related && current.contains(related)) return;
    if (dropTargetId === targetId) {
      setDropTargetId(null);
    }
  };

  const handleDropOnGroup = (e: React.DragEvent, targetGroupId: string | 'NEW') => {
    e.preventDefault();
    e.stopPropagation();
    const ids = parseDragIds(e);
    setDropTargetId(null);
    activeDragCardIds = [];

    if (!ids.length) return;

    if (targetGroupId !== 'NEW') {
      const currentGroups = useGameStore.getState().groups;
      const target = currentGroups.find((g) => g.id === targetGroupId);
      if (target && ids.every((id) => target.cards.some((c) => c.instanceId === id)) && ids.length === target.cards.length) {
        return;
      }
    }

    soundEngine.play('group');
    moveCardsToGroup(ids, targetGroupId);
  };

  const totalCards = groups.reduce((n, g) => n + g.cards.length, 0);

  return (
    <div className="player-hand">
      <div className="player-hand-tray" id="seat-self">
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
              onDragEnter={(e) => allowDrop(e, group.id)}
              onDragLeave={(e) => handleDragLeave(e, group.id)}
              onDrop={(e) => handleDropOnGroup(e, group.id)}
            >
              <div
                className="hand-group-label"
                style={{
                  backgroundColor: label.bg,
                  borderColor: label.color,
                  color: label.color,
                  cursor: selectedCardIds.length > 0 ? 'pointer' : 'default',
                }}
                onClick={(e) => {
                  if (selectedCardIds.length > 0) {
                    e.stopPropagation();
                    const allInThisGroup = selectedCardIds.every((id) =>
                      group.cards.some((c) => c.instanceId === id)
                    );
                    if (!allInThisGroup) {
                      soundEngine.play('group');
                      moveCardsToGroup(selectedCardIds, group.id);
                    }
                  }
                }}
                title={
                  selectedCardIds.length > 0
                    ? `Move ${selectedCardIds.length} selected card${selectedCardIds.length > 1 ? 's' : ''} into this group`
                    : label.title
                }
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
            id="btn-hand-new-group"
            className={`hand-new-group-zone${dropTargetId === 'NEW' ? ' drop-over' : ''}${selectedCardIds.length > 0 ? ' has-selection' : ''}`}
            onClick={() => {
              if (selectedCardIds.length > 0) {
                soundEngine.play('group');
                moveCardsToGroup(selectedCardIds, 'NEW');
              }
            }}
            onDragOver={(e) => allowDrop(e, 'NEW')}
            onDragEnter={(e) => allowDrop(e, 'NEW')}
            onDragLeave={(e) => handleDragLeave(e, 'NEW')}
            onDrop={(e) => handleDropOnGroup(e, 'NEW')}
            role="button"
            tabIndex={0}
            title={
              selectedCardIds.length > 0
                ? `Move ${selectedCardIds.length} selected card${selectedCardIds.length > 1 ? 's' : ''} to new group`
                : 'Drag cards or select cards and click here to create a new group'
            }
          >
            <Plus size={18} />
            <span>New group</span>
          </div>
        )}
      </div>
    </div>
  );
};
