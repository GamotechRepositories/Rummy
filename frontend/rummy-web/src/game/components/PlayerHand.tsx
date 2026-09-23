import React, { useEffect, useLayoutEffect, useRef, useState } from 'react';
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
const DRAG_THRESHOLD_PX = 10;

// In-memory fallback if browser dataTransfer payload is restricted
let activeDragCardIds: string[] = [];

/** Visible fraction of each overlapped card (lower = tighter fan). */
const SHOW_RATIO = 0.36;

function isTouchLikePointer(e: React.PointerEvent | PointerEvent): boolean {
  return e.pointerType === 'touch' || e.pointerType === 'pen';
}

function findDropGroupId(clientX: number, clientY: number): string | 'NEW' | null {
  const stack = document.elementsFromPoint(clientX, clientY);
  for (const el of stack) {
    if (!(el instanceof HTMLElement)) continue;
    const zone = el.closest('[data-drop-group]') as HTMLElement | null;
    if (zone?.dataset.dropGroup) {
      return zone.dataset.dropGroup as string | 'NEW';
    }
  }
  return null;
}

export const PlayerHand: React.FC = () => {
  const {
    groups,
    selectedCardIds,
    gameState,
    toggleSelectCard,
    moveCardsToGroup,
  } = useGameStore();

  const [dropTargetId, setDropTargetId] = useState<string | null>(null);
  const [touchDragging, setTouchDragging] = useState(false);
  const trayRef = useRef<HTMLDivElement>(null);
  const ghostRef = useRef<HTMLDivElement | null>(null);
  const suppressClickRef = useRef(false);
  const pointerDragRef = useRef<{
    pointerId: number;
    cardId: string;
    startX: number;
    startY: number;
    active: boolean;
    ids: string[];
  } | null>(null);

  const wildJoker = gameState?.cutJoker ?? null;

  // Fit card size so the largest group (often all 13) stays fully readable
  useLayoutEffect(() => {
    const tray = trayRef.current;
    if (!tray) return;

    const fit = () => {
      const tray = trayRef.current;
      if (!tray) return;
      const trayW = tray.clientWidth;
      if (trayW < 40) return;

      const stage = tray.closest('.casino-table-stage') as HTMLElement | null;
      const stageW = stage?.clientWidth || trayW;
      const stageH = stage?.clientHeight || window.innerHeight;
      // Scale with the smaller stage axis so short landscape screens shrink cards
      const scaleBase = Math.min(stageW, stageH * 1.7);
      const minCard = Math.max(18, scaleBase * 0.028);
      const maxCard = Math.max(minCard + 4, scaleBase * 0.052);

      const counts = groups.map((g) => g.cards.length);
      const maxInGroup = Math.max(1, ...counts, 0);
      const groupCount = Math.max(1, groups.length);
      const newZoneW = Math.max(36, scaleBase * 0.05);
      const gaps = groupCount * Math.max(4, scaleBase * 0.007) + 6;
      const groupChrome = groupCount * Math.max(8, scaleBase * 0.012);
      const avail = Math.max(120, trayW - newZoneW - gaps - groupChrome);

      const totalCards = counts.reduce((a, b) => a + b, 0) || maxInGroup;
      const share =
        groupCount <= 1 ? 1 : Math.min(1, (maxInGroup / totalCards) * 1.15 + 0.15);
      const groupAvail = avail * share;

      const n = Math.max(maxInGroup, 1);
      let cardW = groupAvail / (1 + (n - 1) * SHOW_RATIO);
      cardW = Math.min(maxCard, Math.max(minCard, cardW));
      const cardH = Math.round(cardW * 1.4);
      const overlap = -(cardW * (1 - SHOW_RATIO));

      tray.style.setProperty('--hand-card-w', `${Math.round(cardW)}px`);
      tray.style.setProperty('--hand-card-h', `${cardH}px`);
      tray.style.setProperty('--card-overlap', `${Math.round(overlap)}px`);
    };

    fit();
    const ro = new ResizeObserver(() => fit());
    ro.observe(tray);
    const stage = tray.closest('.casino-table-stage');
    if (stage) ro.observe(stage);
    return () => ro.disconnect();
  }, [groups]);

  const resolveDragIds = (cardId: string): string[] => {
    const selected = useGameStore.getState().selectedCardIds;
    if (selected.includes(cardId) && selected.length > 1) {
      return [...selected];
    }
    return [cardId];
  };

  const commitMove = (ids: string[], targetGroupId: string | 'NEW') => {
    if (!ids.length) return;

    if (targetGroupId !== 'NEW') {
      const currentGroups = useGameStore.getState().groups;
      const target = currentGroups.find((g) => g.id === targetGroupId);
      if (
        target &&
        ids.every((id) => target.cards.some((c) => c.instanceId === id)) &&
        ids.length === target.cards.length
      ) {
        return;
      }
    }

    soundEngine.play('group');
    moveCardsToGroup(ids, targetGroupId);
  };

  const removeGhost = () => {
    ghostRef.current?.remove();
    ghostRef.current = null;
  };

  const placeGhost = (clientX: number, clientY: number, sourceEl: HTMLElement) => {
    removeGhost();
    const ghost = sourceEl.cloneNode(true) as HTMLDivElement;
    ghost.classList.add('hand-card-ghost');
    ghost.style.width = `${sourceEl.offsetWidth}px`;
    ghost.style.height = `${sourceEl.offsetHeight}px`;
    ghost.style.left = `${clientX - sourceEl.offsetWidth / 2}px`;
    ghost.style.top = `${clientY - sourceEl.offsetHeight / 2}px`;
    document.body.appendChild(ghost);
    ghostRef.current = ghost;
  };

  const moveGhost = (clientX: number, clientY: number) => {
    const ghost = ghostRef.current;
    if (!ghost) return;
    const w = ghost.offsetWidth;
    const h = ghost.offsetHeight;
    ghost.style.left = `${clientX - w / 2}px`;
    ghost.style.top = `${clientY - h / 2}px`;
  };

  const endPointerDrag = (clientX: number, clientY: number, dropped: boolean) => {
    const drag = pointerDragRef.current;
    pointerDragRef.current = null;
    removeGhost();
    setTouchDragging(false);
    setDropTargetId(null);

    document.querySelectorAll('.rummy-card.dragging').forEach((el) => {
      el.classList.remove('dragging');
    });

    if (drag?.active && dropped) {
      const target = findDropGroupId(clientX, clientY);
      if (target) {
        commitMove(drag.ids, target);
      }
      suppressClickRef.current = true;
      window.setTimeout(() => {
        suppressClickRef.current = false;
      }, 300);
    }

    activeDragCardIds = [];
  };

  useEffect(() => {
    const onMove = (e: PointerEvent) => {
      const drag = pointerDragRef.current;
      if (!drag || e.pointerId !== drag.pointerId) return;

      const dx = e.clientX - drag.startX;
      const dy = e.clientY - drag.startY;
      const dist = Math.hypot(dx, dy);

      if (!drag.active) {
        if (dist < DRAG_THRESHOLD_PX) return;
        drag.active = true;
        setTouchDragging(true);
        activeDragCardIds = drag.ids;
        const source = document.getElementById(`card-${drag.cardId}`);
        if (source) {
          source.classList.add('dragging');
          placeGhost(e.clientX, e.clientY, source);
        }
      }

      e.preventDefault();
      moveGhost(e.clientX, e.clientY);
      const over = findDropGroupId(e.clientX, e.clientY);
      setDropTargetId(over);
    };

    const onUp = (e: PointerEvent) => {
      const drag = pointerDragRef.current;
      if (!drag || e.pointerId !== drag.pointerId) return;
      endPointerDrag(e.clientX, e.clientY, drag.active);
    };

    window.addEventListener('pointermove', onMove, { passive: false });
    window.addEventListener('pointerup', onUp);
    window.addEventListener('pointercancel', onUp);
    return () => {
      window.removeEventListener('pointermove', onMove);
      window.removeEventListener('pointerup', onUp);
      window.removeEventListener('pointercancel', onUp);
      removeGhost();
    };
  }, []);

  const handleCardPointerDown = (e: React.PointerEvent, cardId: string) => {
    // Mouse keeps native HTML5 DnD; touch/pen use pointer drag
    if (!isTouchLikePointer(e)) return;
    if (e.button !== 0 && e.button !== -1) return;

    const ids = resolveDragIds(cardId);
    pointerDragRef.current = {
      pointerId: e.pointerId,
      cardId,
      startX: e.clientX,
      startY: e.clientY,
      active: false,
      ids,
    };
    try {
      (e.currentTarget as HTMLElement).setPointerCapture(e.pointerId);
    } catch {
      // ignore
    }
  };

  const handleCardDragStart = (e: React.DragEvent, cardId: string) => {
    // Touch path uses pointer events — ignore HTML5 on touch
    if (pointerDragRef.current) {
      e.preventDefault();
      return;
    }
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
    commitMove(ids, targetGroupId);
  };

  const totalCards = groups.reduce((n, g) => n + g.cards.length, 0);

  return (
    <div className={`player-hand${touchDragging ? ' is-touch-dragging' : ''}`}>
      <div className="player-hand-tray" id="seat-self" ref={trayRef}>
        {totalCards === 0 && (
          <div className="player-hand-empty">Your cards will appear here after the deal.</div>
        )}

        {groups.map((group) => {
          const label = GROUP_LABELS[group.groupType] ?? GROUP_LABELS.INVALID;
          const isOver = dropTargetId === group.id;
          return (
            <div
              key={group.id}
              data-drop-group={group.id}
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
                    onPointerDown={(e) => handleCardPointerDown(e, card.instanceId)}
                  >
                    <CardView
                      card={card}
                      wildJoker={wildJoker}
                      isSelected={selectedCardIds.includes(card.instanceId)}
                      isDraggable
                      onDragStart={(e) => handleCardDragStart(e, card.instanceId)}
                      onDragEnd={handleCardDragEnd}
                      onClick={() => {
                        if (suppressClickRef.current) return;
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
            data-drop-group="NEW"
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
