import React, { useCallback, useRef } from 'react';
import { useGameStore } from '../store/useGameStore';
import { CardView } from './CardView';
import type { GroupValidationType } from '../types/game';
import { Layers, ArrowUpDown, XCircle, Plus } from 'lucide-react';
import { soundEngine } from '../audio/soundEngine';
import { socketClient } from '../websocket/GameSocketClient';
import { calculateHandPenalty } from '../rules/clientValidator';
import { TurnTimerRing } from './TurnTimerRing';
import { getAvatarForPlayer } from '../utils/avatarUtils';

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
    displayName,
    toggleSelectCard,
    groupSelectedCards,
    moveCardsToGroup,
    autoSortHand,
    clearSelection,
  } = useGameStore();

  const handRef = useRef<HTMLDivElement>(null);
  const dragIdsRef = useRef<string[]>([]);
  const dropTargetRef = useRef<string | null>(null);

  const isMyTurn = gameState?.isMyTurn ?? false;
  const myAvatar = getAvatarForPlayer(displayName);
  const wildJoker = gameState?.cutJoker ?? null;
  const hasPure = groups.some((g) => g.groupType === 'PURE_SEQUENCE');
  const totalCards = groups.reduce((n, g) => n + g.cards.length, 0);
  const liveScore = calculateHandPenalty(groups, wildJoker);

  const clearDropHighlight = useCallback(() => {
    const root = handRef.current;
    if (!root) return;
    root.querySelectorAll('.drop-over').forEach((el) => el.classList.remove('drop-over'));
    dropTargetRef.current = null;
  }, []);

  const setDropHighlight = useCallback((targetId: string | null) => {
    if (dropTargetRef.current === targetId) return;
    clearDropHighlight();
    if (!targetId || !handRef.current) return;
    const el = handRef.current.querySelector(`[data-drop-id="${targetId}"]`);
    if (el) el.classList.add('drop-over');
    dropTargetRef.current = targetId;
  }, [clearDropHighlight]);

  const endDragSession = useCallback(() => {
    dragIdsRef.current = [];
    handRef.current?.classList.remove('is-dnd-active');
    clearDropHighlight();
  }, [clearDropHighlight]);

  const resolveDragIds = (cardId: string): string[] => {
    const selected = useGameStore.getState().selectedCardIds;
    if (selected.includes(cardId) && selected.length > 1) {
      return [...selected];
    }
    return [cardId];
  };

  const handleCardDragStart = (e: React.DragEvent, cardId: string) => {
    const ids = resolveDragIds(cardId);
    dragIdsRef.current = ids;
    const payload = JSON.stringify(ids);
    try {
      e.dataTransfer.setData(DND_MIME, payload);
      e.dataTransfer.setData('text/plain', payload);
    } catch {
      // Some browsers restrict custom MIME mid-drag; ref is the fallback.
    }
    e.dataTransfer.effectAllowed = 'move';
    handRef.current?.classList.add('is-dnd-active');
    (e.currentTarget as HTMLElement).classList.add('dragging');
  };

  const handleCardDragEnd = (e: React.DragEvent) => {
    (e.currentTarget as HTMLElement).classList.remove('dragging');
    endDragSession();
  };

  const parseDragIds = (e: React.DragEvent): string[] => {
    if (dragIdsRef.current.length) return dragIdsRef.current;
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
    e.stopPropagation();
    e.dataTransfer.dropEffect = 'move';
    setDropHighlight(targetId);
  };

  const handleDragLeave = (e: React.DragEvent, targetId: string) => {
    const related = e.relatedTarget as Node | null;
    const current = e.currentTarget as HTMLElement;
    // Ignore leave events that bubble from children into the same drop zone.
    if (related && current.contains(related)) return;
    if (dropTargetRef.current === targetId) {
      current.classList.remove('drop-over');
      dropTargetRef.current = null;
    }
  };

  const handleDropOnGroup = (e: React.DragEvent, targetGroupId: string | 'NEW') => {
    e.preventDefault();
    e.stopPropagation();
    const ids = parseDragIds(e);
    endDragSession();
    if (!ids.length) return;

    const currentGroups = useGameStore.getState().groups;
    if (targetGroupId !== 'NEW') {
      const target = currentGroups.find((g) => g.id === targetGroupId);
      // Already entirely in this tray — no-op (avoids useless re-render / freeze feel)
      if (target && ids.every((id) => target.cards.some((c) => c.instanceId === id))) {
        return;
      }
    }

    soundEngine.play('group');
    moveCardsToGroup(ids, targetGroupId);
  };

  return (
    <div className="player-hand" ref={handRef}>
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
            style={{ padding: '5px 12px', fontSize: 12 }}
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
            style={{ padding: '5px 12px', fontSize: 12 }}
          >
            <ArrowUpDown size={13} />
            Sort
          </button>

          {/* User Icon & VIP badge beside Sort in the center */}
          <div className="player-hand-user-pill">
            <div style={{ position: 'relative', width: 26, height: 26, flexShrink: 0 }}>
              {isMyTurn && (
                <div style={{ position: 'absolute', top: -3, left: -3 }}>
                  <TurnTimerRing turnDeadline={gameState?.turnDeadline ?? null} size={32} strokeWidth={2.5} />
                </div>
              )}
              <div
                style={{
                  width: 26,
                  height: 26,
                  borderRadius: '50%',
                  overflow: 'hidden',
                  border: isMyTurn ? '1.5px solid #fbbf24' : '1px solid rgba(212, 175, 55, 0.4)',
                  boxShadow: isMyTurn ? '0 0 8px rgba(251, 191, 36, 0.5)' : 'none',
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'center',
                }}
              >
                {myAvatar.renderSvg(26)}
              </div>
            </div>
            <span style={{ fontSize: 11, fontWeight: 800, color: '#f8fafc', whiteSpace: 'nowrap' }}>
              {displayName}
            </span>
            <span
              style={{
                fontSize: 8,
                fontWeight: 900,
                color: '#fef08a',
                background: 'linear-gradient(135deg, #78350f, #451a03)',
                border: '1px solid #fbbf24',
                padding: '0.5px 5px',
                borderRadius: 6,
                letterSpacing: '0.03em',
              }}
            >
              {myAvatar.vipTier}
            </span>
          </div>

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

        <div className="player-hand-status">
          {gameState?.gameStatus === 'IN_PROGRESS' && (
            <span className={`player-hand-score${liveScore === 0 && hasPure ? ' ok' : ''}`}>
              {liveScore === 0 && hasPure ? '✓ Score: 0 pts' : `Score: ${liveScore} pts`}
            </span>
          )}
          <span className={`player-hand-pure${hasPure ? ' ok' : ''}`}>
            {hasPure ? 'Pure run ✓' : 'Need pure run'}
          </span>
        </div>
      </div>

      <div className="player-hand-tray">
        {totalCards === 0 && (
          <div className="player-hand-empty">Your cards will appear here after the deal.</div>
        )}

        {groups.map((group) => {
          const label = GROUP_LABELS[group.groupType] ?? GROUP_LABELS.INVALID;
          return (
            <div
              key={group.id}
              data-drop-id={group.id}
              className={`hand-group hand-group--${group.groupType.toLowerCase()}`}
              onDragOver={(e) => allowDrop(e, group.id)}
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
            data-drop-id="NEW"
            className={`hand-new-group-zone${selectedCardIds.length > 0 ? ' has-selection' : ''}`}
            onClick={() => {
              if (selectedCardIds.length > 0) {
                soundEngine.play('group');
                moveCardsToGroup(selectedCardIds, 'NEW');
              }
            }}
            onDragOver={(e) => allowDrop(e, 'NEW')}
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
