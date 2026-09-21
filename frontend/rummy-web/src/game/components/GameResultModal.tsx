import React, { useEffect, useState } from 'react';
import { useGameStore } from '../store/useGameStore';
import { socketClient } from '../websocket/GameSocketClient';
import confetti from 'canvas-confetti';
import {
  Trophy,
  Award,
  RotateCcw,
  LogOut,
  CheckCircle2,
  AlertCircle,
  Eye,
  EyeOff,
  Layers,
  Sparkles,
  ChevronDown,
  ChevronUp,
} from 'lucide-react';
import { soundEngine } from '../audio/soundEngine';
import { CardView } from './CardView';
import type { CardInstance, GroupValidationType } from '../types/game';
import { evaluateCardGroup, getCardScore } from '../rules/clientValidator';

interface GameResultModalProps {
  isOpen: boolean;
}

const GROUP_LABELS: Record<GroupValidationType, { title: string; color: string; bg: string }> = {
  PURE_SEQUENCE: { title: '✓ Pure Run', color: 'var(--color-pure, #10b981)', bg: 'rgba(16,185,129,0.2)' },
  IMPURE_SEQUENCE: { title: '★ Run', color: 'var(--color-impure, #f59e0b)', bg: 'rgba(245,158,11,0.2)' },
  SET: { title: '◆ Set', color: 'var(--color-set, #3b82f6)', bg: 'rgba(59,130,246,0.2)' },
  INVALID: { title: 'Not a group', color: 'var(--color-invalid, #ef4444)', bg: 'rgba(239,68,68,0.2)' },
};

const SUIT_ORDER: Record<string, number> = { SPADES: 0, HEARTS: 1, CLUBS: 2, DIAMONDS: 3, NONE: 4 };
const RANK_ORDER: Record<string, number> = {
  ACE: 1,
  TWO: 2,
  THREE: 3,
  FOUR: 4,
  FIVE: 5,
  SIX: 6,
  SEVEN: 7,
  EIGHT: 8,
  NINE: 9,
  TEN: 10,
  JACK: 11,
  QUEEN: 12,
  KING: 13,
  JOKER: 14,
};

function groupCardsForShowdown(
  cards: CardInstance[],
  wildJoker: CardInstance | null
): { title: string; type: GroupValidationType; cards: CardInstance[]; pts: number }[] {
  if (!cards || cards.length === 0) return [];

  const sorted = [...cards].sort((a, b) => {
    const s = (SUIT_ORDER[a.suit] ?? 99) - (SUIT_ORDER[b.suit] ?? 99);
    if (s !== 0) return s;
    return (RANK_ORDER[a.rank] ?? 99) - (RANK_ORDER[b.rank] ?? 99);
  });

  const suitBuckets: Record<string, CardInstance[]> = {};
  for (const c of sorted) {
    const key = c.printedJoker ? 'JOKER' : c.suit;
    if (!suitBuckets[key]) suitBuckets[key] = [];
    suitBuckets[key].push(c);
  }

  return Object.entries(suitBuckets).map(([key, groupCards]) => {
    const type = evaluateCardGroup(groupCards, wildJoker);
    const pts = groupCards.reduce((acc, c) => acc + getCardScore(c, wildJoker), 0);
    return {
      title: key === 'JOKER' ? 'Jokers' : key.charAt(0) + key.slice(1).toLowerCase(),
      type,
      cards: groupCards,
      pts,
    };
  });
}

const ShowdownCardTray: React.FC<{
  groups: { title: string; type?: GroupValidationType; cards: CardInstance[]; pts?: number }[];
  wildJoker: CardInstance | null;
}> = ({ groups, wildJoker }) => {
  if (!groups || groups.length === 0) {
    return (
      <div style={{ fontSize: '11px', color: '#94a3b8', fontStyle: 'italic', padding: '6px 2px' }}>
        No cards to display
      </div>
    );
  }

  return (
    <div
      style={{
        display: 'flex',
        flexWrap: 'wrap',
        gap: '8px',
        marginTop: '8px',
        padding: '8px 10px',
        borderRadius: '12px',
        background: 'rgba(0, 0, 0, 0.35)',
        border: '1px solid rgba(255, 255, 255, 0.06)',
      }}
    >
      {groups.map((group, gIdx) => {
        const type = group.type || 'INVALID';
        const labelInfo = GROUP_LABELS[type] || GROUP_LABELS.INVALID;
        return (
          <div
            key={gIdx}
            style={{
              background: 'rgba(15, 23, 42, 0.85)',
              border: `1px solid ${labelInfo.color}`,
              borderRadius: '8px',
              padding: '4px 6px',
              display: 'flex',
              flexDirection: 'column',
              gap: '4px',
              boxShadow: '0 2px 6px rgba(0, 0, 0, 0.4)',
            }}
          >
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: '6px' }}>
              <span
                style={{
                  fontSize: '8.5px',
                  fontWeight: 900,
                  textTransform: 'uppercase',
                  letterSpacing: '0.04em',
                  color: labelInfo.color,
                  backgroundColor: labelInfo.bg,
                  padding: '1px 5px',
                  borderRadius: '4px',
                }}
              >
                {labelInfo.title}
              </span>
              {typeof group.pts === 'number' && group.pts > 0 && (
                <span style={{ fontSize: '8.5px', color: '#f87171', fontWeight: 800 }}>
                  {group.pts} pts
                </span>
              )}
            </div>

            <div style={{ display: 'flex', alignItems: 'center' }}>
              {group.cards.map((c, cIdx) => (
                <div
                  key={c.instanceId || `${gIdx}-${cIdx}`}
                  style={{
                    marginLeft: cIdx === 0 ? 0 : 'var(--card-overlap, -20px)',
                    position: 'relative',
                    zIndex: cIdx + 1,
                  }}
                >
                  <CardView card={c} wildJoker={wildJoker} size="small" />
                </div>
              ))}
            </div>
          </div>
        );
      })}
    </div>
  );
};

export const GameResultModal: React.FC<GameResultModalProps> = ({ isOpen }) => {
  const {
    gameState,
    playerId,
    displayName,
    groups: myVisualGroups,
    leaveTable,
    setAutoMatchmakePending,
    clearSelection,
  } = useGameStore();

  const [expandedPlayerIds, setExpandedPlayerIds] = useState<Record<string, boolean>>({});
  const [showAllCards, setShowAllCards] = useState<boolean>(true);

  const isCompleted = gameState?.gameStatus === 'COMPLETED';
  const winnerId = gameState?.winnerId;
  const isWinner = winnerId === playerId;

  useEffect(() => {
    if (isOpen && isCompleted && isWinner) {
      confetti({
        particleCount: 120,
        spread: 75,
        origin: { y: 0.5 },
      });
      soundEngine.play('win');
    } else if (isOpen && isCompleted && !isWinner) {
      soundEngine.play('lose');
    }
  }, [isOpen, isCompleted, isWinner]);

  if (!isOpen || !isCompleted || !gameState) return null;

  const opponents = gameState.opponents ?? [];
  const winnerName = isWinner
    ? displayName
    : opponents.find((p) => p.playerId === winnerId)?.displayName ?? 'Opponent';

  const myScore = isWinner ? 0 : (gameState.viewerScore ?? (opponents.length > 0 ? opponents[0].score : 80));
  const myStatus = isWinner ? 'WON' : (gameState.viewerStatus ?? 'LOST');

  // Determine winner player cards with robust fallback
  const winnerOpponent = opponents.find((p) => p.playerId === winnerId);
  const rawWinnerCards = isWinner
    ? (gameState.hand || [])
    : (winnerOpponent?.hand && winnerOpponent.hand.length > 0)
      ? winnerOpponent.hand
      : (gameState.winningGroups && gameState.winningGroups.length > 0)
        ? gameState.winningGroups.flatMap((wg) => wg.cards)
        : [];

  const winningMeldsDisplay = (gameState.winningGroups && gameState.winningGroups.length > 0)
    ? gameState.winningGroups.map((wg) => ({
        title: '',
        type: evaluateCardGroup(wg.cards, gameState.cutJoker),
        cards: wg.cards,
        pts: 0,
      }))
    : rawWinnerCards.length > 0
      ? groupCardsForShowdown(rawWinnerCards, gameState.cutJoker)
      : [];

  const allPlayers = [
    {
      playerId,
      name: displayName || 'You',
      isMe: true,
      isWinner,
      status: myStatus,
      score: myScore,
      hand: isWinner && rawWinnerCards.length > 0 ? rawWinnerCards : (gameState.hand || []),
    },
    ...opponents.map((opp) => {
      const isThisOpponentWinner = opp.playerId === winnerId;
      const oppHand = isThisOpponentWinner && rawWinnerCards.length > 0 ? rawWinnerCards : (opp.hand || []);
      return {
        playerId: opp.playerId,
        name: opp.displayName,
        isMe: false,
        isWinner: isThisOpponentWinner,
        status: isThisOpponentWinner ? 'WON' : opp.status,
        score: opp.score,
        hand: oppHand,
      };
    }),
  ];

  const togglePlayerExpanded = (pId: string) => {
    setExpandedPlayerIds((prev) => ({
      ...prev,
      [pId]: prev[pId] !== undefined ? !prev[pId] : false,
    }));
  };

  const isPlayerExpanded = (pId: string): boolean => {
    if (expandedPlayerIds[pId] !== undefined) {
      return expandedPlayerIds[pId];
    }
    return showAllCards;
  };

  const handleRematch = () => {
    soundEngine.play('match');
    clearSelection();
    setAutoMatchmakePending(true);
    socketClient.disconnect();
    leaveTable();
  };

  const handleLeave = () => {
    soundEngine.play('click');
    socketClient.disconnect();
    leaveTable();
  };

  return (
    <div
      style={{
        position: 'fixed',
        inset: 0,
        backgroundColor: 'rgba(0, 0, 0, 0.82)',
        backdropFilter: 'blur(10px)',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        zIndex: 1000,
        padding: '16px',
        animation: 'fadeIn 0.25s ease-out',
      }}
    >
      <div
        style={{
          width: '100%',
          maxWidth: '680px',
          maxHeight: '92vh',
          background: 'linear-gradient(180deg, #1e293b 0%, #0b1324 100%)',
          borderRadius: '24px',
          border: isWinner ? '2px solid var(--border-gold, #fbbf24)' : '1px solid rgba(255, 255, 255, 0.16)',
          boxShadow: isWinner
            ? '0 0 50px rgba(212, 175, 55, 0.35), 0 24px 60px rgba(0, 0, 0, 0.9)'
            : '0 24px 60px rgba(0, 0, 0, 0.9)',
          overflow: 'hidden',
          display: 'flex',
          flexDirection: 'column',
        }}
      >
        {/* Header Banner */}
        <div
          style={{
            padding: '20px 20px 14px',
            textAlign: 'center',
            background: isWinner
              ? 'linear-gradient(180deg, rgba(212, 175, 55, 0.28) 0%, transparent 100%)'
              : 'linear-gradient(180deg, rgba(51, 65, 85, 0.45) 0%, transparent 100%)',
            borderBottom: '1px solid rgba(255, 255, 255, 0.08)',
            position: 'relative',
          }}
        >
          <div
            style={{
              display: 'inline-flex',
              alignItems: 'center',
              justifyContent: 'center',
              width: '56px',
              height: '56px',
              borderRadius: '50%',
              background: isWinner
                ? 'linear-gradient(135deg, #fbbf24, #d97706)'
                : 'linear-gradient(135deg, #475569, #1e293b)',
              boxShadow: isWinner ? '0 0 25px rgba(251, 191, 36, 0.6)' : 'none',
              marginBottom: '8px',
            }}
          >
            {isWinner ? <Trophy size={28} color="#1e1b4b" /> : <Award size={28} color="#94a3b8" />}
          </div>

          <h2
            style={{
              margin: '0 0 4px',
              fontSize: '22px',
              fontWeight: 900,
              color: isWinner ? 'var(--gold-light, #fef08a)' : '#f8fafc',
              fontFamily: 'var(--font-display, inherit)',
              letterSpacing: '-0.01em',
            }}
          >
            {isWinner ? '🎉 You Won the Hand!' : `${winnerName} Won the Hand`}
          </h2>
          <p style={{ margin: 0, fontSize: '12px', color: '#94a3b8' }}>
            {isWinner
              ? 'Valid Declaration! 0 penalty points. All players cards are revealed below.'
              : 'Hand completed. Review the winner’s declare and all player cards below.'}
          </p>
        </div>

        {/* Scrollable Content: Showdown & Scoreboard */}
        <div style={{ padding: '16px 20px', overflowY: 'auto', flex: 1 }}>
          {/* Winner's Declared Melds Spotlight (if available) */}
          {winningMeldsDisplay.length > 0 && (
            <div
              style={{
                marginBottom: '16px',
                padding: '12px 14px',
                borderRadius: '16px',
                background: 'linear-gradient(135deg, rgba(212, 175, 55, 0.12), rgba(15, 23, 42, 0.6))',
                border: '1.5px solid rgba(251, 191, 36, 0.5)',
                boxShadow: '0 4px 16px rgba(0, 0, 0, 0.3)',
              }}
            >
              <div
                style={{
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'space-between',
                  marginBottom: '6px',
                }}
              >
                <div style={{ display: 'flex', alignItems: 'center', gap: '6px' }}>
                  <Sparkles size={16} color="#fbbf24" />
                  <span style={{ fontSize: '12px', fontWeight: 900, color: '#fef08a', textTransform: 'uppercase' }}>
                    🏆 Winner's Declaration ({winnerName})
                  </span>
                </div>
                <span
                  style={{
                    fontSize: '11px',
                    fontWeight: 800,
                    color: '#86efac',
                    background: 'rgba(34, 197, 94, 0.2)',
                    padding: '2px 8px',
                    borderRadius: '6px',
                    border: '1px solid #22c55e',
                  }}
                >
                  0 Points (Valid Show)
                </span>
              </div>

              <ShowdownCardTray groups={winningMeldsDisplay} wildJoker={gameState.cutJoker} />
            </div>
          )}

          {/* Section Header with Show/Hide All toggle */}
          <div
            style={{
              fontSize: '11px',
              fontWeight: 800,
              textTransform: 'uppercase',
              letterSpacing: '0.08em',
              color: '#94a3b8',
              marginBottom: '10px',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'space-between',
              padding: '0 4px',
            }}
          >
            <span style={{ display: 'flex', alignItems: 'center', gap: '5px' }}>
              <Layers size={13} />
              Showdown Scoreboard & Cards (सर्व खेळाडूंचे पत्ते)
            </span>

            <button
              type="button"
              onClick={() => {
                const next = !showAllCards;
                setShowAllCards(next);
                setExpandedPlayerIds(
                  allPlayers.reduce((acc, p) => ({ ...acc, [p.playerId]: next }), {})
                );
              }}
              style={{
                background: 'transparent',
                border: 'none',
                color: '#38bdf8',
                fontSize: '11px',
                fontWeight: 700,
                cursor: 'pointer',
                display: 'flex',
                alignItems: 'center',
                gap: '4px',
                padding: '2px 6px',
              }}
            >
              {showAllCards ? (
                <>
                  <EyeOff size={13} /> Hide Cards
                </>
              ) : (
                <>
                  <Eye size={13} /> Show All Cards
                </>
              )}
            </button>
          </div>

          {/* Players List with Revealed Cards */}
          <div style={{ display: 'flex', flexDirection: 'column', gap: '10px' }}>
            {allPlayers.map((p) => {
              const won = p.isWinner;
              const expanded = isPlayerExpanded(p.playerId);

              // Calculate player's card groups for display
              let playerGroups: { title: string; type?: GroupValidationType; cards: CardInstance[]; pts?: number }[] = [];
              if (won) {
                playerGroups = winningMeldsDisplay.length > 0
                  ? winningMeldsDisplay
                  : groupCardsForShowdown(p.hand, gameState.cutJoker);
              } else if (p.isMe && myVisualGroups && myVisualGroups.length > 0) {
                playerGroups = myVisualGroups.map((g) => ({
                  title: '',
                  type: g.groupType,
                  cards: g.cards,
                  pts: g.deadwoodPoints,
                }));
              } else {
                playerGroups = groupCardsForShowdown(p.hand, gameState.cutJoker);
              }

              const cardCountDisplay = playerGroups.reduce((sum, g) => sum + g.cards.length, 0) || p.hand.length;

              return (
                <div
                  key={p.playerId}
                  style={{
                    padding: '12px 14px',
                    borderRadius: '16px',
                    background: won
                      ? 'rgba(212, 175, 55, 0.12)'
                      : 'rgba(255, 255, 255, 0.04)',
                    border: won
                      ? '1.5px solid rgba(212, 175, 55, 0.45)'
                      : '1px solid rgba(255, 255, 255, 0.08)',
                    transition: 'all 0.15s ease',
                  }}
                >
                  {/* Player Summary Row */}
                  <div
                    style={{
                      display: 'flex',
                      alignItems: 'center',
                      justifyContent: 'space-between',
                      cursor: 'pointer',
                    }}
                    onClick={() => togglePlayerExpanded(p.playerId)}
                  >
                    <div style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
                      <div
                        style={{
                          width: '34px',
                          height: '34px',
                          borderRadius: '50%',
                          background: won ? '#fbbf24' : '#475569',
                          color: won ? '#1a1a1a' : '#ffffff',
                          display: 'flex',
                          alignItems: 'center',
                          justifyContent: 'center',
                          fontWeight: 900,
                          fontSize: '13px',
                          flexShrink: 0,
                          boxShadow: won ? '0 0 10px rgba(251, 191, 36, 0.4)' : 'none',
                        }}
                      >
                        {p.name.charAt(0).toUpperCase()}
                      </div>

                      <div>
                        <div style={{ fontWeight: 700, fontSize: '14px', color: '#ffffff' }}>
                          {p.name} {p.isMe ? <span style={{ color: '#fbbf24', fontSize: '11px', fontWeight: 800 }}>(You)</span> : ''}
                        </div>
                        <div style={{ fontSize: '11px', color: '#94a3b8', display: 'flex', alignItems: 'center', gap: '4px' }}>
                          {won ? (
                            <span style={{ color: '#86efac', display: 'flex', alignItems: 'center', gap: '3px', fontWeight: 800 }}>
                              <CheckCircle2 size={12} /> Winner
                            </span>
                          ) : (
                            <span style={{ color: p.status === 'DROPPED' ? '#f59e0b' : '#fca5a5', display: 'flex', alignItems: 'center', gap: '3px', fontWeight: 700 }}>
                              <AlertCircle size={12} /> {p.status}
                            </span>
                          )}
                        </div>
                      </div>
                    </div>

                    <div style={{ display: 'flex', alignItems: 'center', gap: '12px' }}>
                      <div style={{ textAlign: 'right' }}>
                        <div
                          style={{
                            fontWeight: 900,
                            fontSize: '16px',
                            color: won ? '#86efac' : p.status === 'DROPPED' ? '#fbbf24' : '#f87171',
                          }}
                        >
                          {p.score} pts
                        </div>
                        <div style={{ fontSize: '10px', color: '#94a3b8' }}>
                          {cardCountDisplay} cards
                        </div>
                      </div>

                      <div style={{ color: '#94a3b8', padding: '4px' }}>
                        {expanded ? <ChevronUp size={18} /> : <ChevronDown size={18} />}
                      </div>
                    </div>
                  </div>

                  {/* Expanded Card Tray for this Player */}
                  {expanded && (
                    <div style={{ marginTop: '8px', animation: 'fadeIn 0.2s ease-out' }}>
                      <ShowdownCardTray groups={playerGroups} wildJoker={gameState.cutJoker} />
                    </div>
                  )}
                </div>
              );
            })}
          </div>
        </div>

        {/* Action Buttons */}
        <div
          style={{
            padding: '14px 20px 18px',
            display: 'flex',
            gap: '12px',
            background: 'rgba(0, 0, 0, 0.45)',
            borderTop: '1px solid rgba(255, 255, 255, 0.08)',
          }}
        >
          <button
            type="button"
            className="btn-secondary"
            onClick={handleLeave}
            style={{
              flex: 1,
              padding: '12px',
              fontSize: '13px',
              fontWeight: 700,
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              gap: '8px',
            }}
          >
            <LogOut size={16} />
            Leave Table
          </button>

          <button
            type="button"
            className="btn-primary"
            onClick={handleRematch}
            style={{
              flex: 1.6,
              padding: '12px',
              fontSize: '14px',
              fontWeight: 800,
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              gap: '8px',
            }}
          >
            <RotateCcw size={16} />
            Rematch Same Stake
          </button>
        </div>
      </div>
    </div>
  );
};
