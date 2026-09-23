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
  Crown,
  ChevronDown,
  ChevronUp,
  Sparkles,
} from 'lucide-react';
import { soundEngine } from '../audio/soundEngine';
import { clearActiveSessionRemote } from '../utils/sessionResume';
import { CardView } from './CardView';
import type { CardInstance, GroupValidationType } from '../types/game';
import { evaluateCardGroup, getCardScore } from '../rules/clientValidator';

interface GameResultModalProps {
  isOpen: boolean;
}

const GROUP_LABELS: Record<GroupValidationType, { title: string; color: string; bg: string }> = {
  PURE_SEQUENCE: { title: '✓ Pure Run', color: '#10b981', bg: 'rgba(16, 185, 129, 0.2)' },
  IMPURE_SEQUENCE: { title: '★ Run', color: '#f59e0b', bg: 'rgba(245, 158, 11, 0.2)' },
  SET: { title: '◆ Set', color: '#38bdf8', bg: 'rgba(56, 189, 248, 0.2)' },
  INVALID: { title: 'Cards', color: '#f43f5e', bg: 'rgba(244, 63, 94, 0.15)' },
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

const SUIT_NAMES: Record<string, string> = {
  SPADES: '♠ Spades',
  HEARTS: '♥ Hearts',
  CLUBS: '♣ Clubs',
  DIAMONDS: '♦ Diamonds',
  JOKER: '🃏 Jokers',
};

interface FormattedShowdownGroup {
  title: string;
  type: GroupValidationType;
  cards: CardInstance[];
  pts: number;
}

/**
 * Organizes a player's 13 cards into clean, readable Rummy groups of 3–5 cards max.
 * Never produces a single 13-card clump.
 */
function organizePlayerShowdownCards(
  cards: CardInstance[],
  wildJoker: CardInstance | null,
  isWinner: boolean,
  declaredGroups?: { cards: CardInstance[] }[]
): FormattedShowdownGroup[] {
  // 1. If player formally declared and has valid declared groups (2-5 cards each)
  if (
    isWinner &&
    declaredGroups &&
    declaredGroups.length >= 2 &&
    declaredGroups.every((g) => g.cards && g.cards.length >= 2 && g.cards.length <= 6)
  ) {
    return declaredGroups.map((g) => {
      const type = evaluateCardGroup(g.cards, wildJoker);
      return {
        title: '',
        type,
        cards: g.cards,
        pts: 0,
      };
    });
  }

  if (!cards || cards.length === 0) return [];

  // 2. Sort all cards by suit and rank
  const sorted = [...cards].sort((a, b) => {
    const s = (SUIT_ORDER[a.suit] ?? 99) - (SUIT_ORDER[b.suit] ?? 99);
    if (s !== 0) return s;
    return (RANK_ORDER[a.rank] ?? 99) - (RANK_ORDER[b.rank] ?? 99);
  });

  // 3. Bucket by suit
  const suitBuckets: Record<string, CardInstance[]> = {};
  for (const c of sorted) {
    const key = c.printedJoker ? 'JOKER' : c.suit;
    if (!suitBuckets[key]) suitBuckets[key] = [];
    suitBuckets[key].push(c);
  }

  const resultGroups: FormattedShowdownGroup[] = [];

  for (const [key, groupCards] of Object.entries(suitBuckets)) {
    // If a suit bucket has more than 4 cards (e.g. 5, 6 or 7 cards), split it into sub-chunks of 3-4 cards
    if (groupCards.length > 4) {
      const mid = Math.ceil(groupCards.length / 2);
      const chunk1 = groupCards.slice(0, mid);
      const chunk2 = groupCards.slice(mid);
      [chunk1, chunk2].forEach((chunk) => {
        const type = evaluateCardGroup(chunk, wildJoker);
        const pts = isWinner ? 0 : chunk.reduce((sum, c) => sum + getCardScore(c, wildJoker), 0);
        const defaultTitle = SUIT_NAMES[key] ? `${SUIT_NAMES[key]} (${chunk.length})` : key;
        resultGroups.push({
          title: defaultTitle,
          type,
          cards: chunk,
          pts,
        });
      });
    } else {
      const type = evaluateCardGroup(groupCards, wildJoker);
      const pts = isWinner ? 0 : groupCards.reduce((sum, c) => sum + getCardScore(c, wildJoker), 0);
      const defaultTitle = SUIT_NAMES[key] ? `${SUIT_NAMES[key]} (${groupCards.length})` : key;
      resultGroups.push({
        title: defaultTitle,
        type,
        cards: groupCards,
        pts,
      });
    }
  }

  return resultGroups;
}

const ShowdownCardTray: React.FC<{
  groups: FormattedShowdownGroup[];
  wildJoker: CardInstance | null;
  cardCount: number;
}> = ({ groups, wildJoker, cardCount }) => {
  if (!groups || groups.length === 0) {
    return (
      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          gap: '8px',
          padding: '10px 14px',
          borderRadius: '12px',
          background: 'rgba(255, 255, 255, 0.03)',
          border: '1px dashed rgba(255, 255, 255, 0.12)',
          color: '#94a3b8',
          fontSize: '12px',
          marginTop: '8px',
        }}
      >
        <Layers size={15} color="#64748b" />
        <span>{cardCount > 0 ? `${cardCount} cards held at showdown` : 'Cards concealed'}</span>
      </div>
    );
  }

  return (
    <div className="showdown-grid">
      {groups.map((group, gIdx) => {
        const type = group.type || 'INVALID';
        const labelInfo = GROUP_LABELS[type] || GROUP_LABELS.INVALID;
        const displayLabel = group.title && type === 'INVALID' ? group.title : labelInfo.title;

        return (
          <div
            key={gIdx}
            className="showdown-group-pod"
            style={{
              border: `1.5px solid ${labelInfo.color}`,
            }}
          >
            {/* Group Label Header */}
            <div
              style={{
                display: 'flex',
                justifyContent: 'space-between',
                alignItems: 'center',
                gap: '4px',
                padding: '0 2px',
              }}
            >
              <span
                style={{
                  fontSize: '9.5px',
                  fontWeight: 900,
                  textTransform: 'uppercase',
                  letterSpacing: '0.04em',
                  color: labelInfo.color,
                  backgroundColor: labelInfo.bg,
                  padding: '2px 6px',
                  borderRadius: '5px',
                  whiteSpace: 'nowrap',
                  overflow: 'hidden',
                  textOverflow: 'ellipsis',
                }}
              >
                {displayLabel}
              </span>

              {typeof group.pts === 'number' && group.pts > 0 && (
                <span
                  style={{
                    fontSize: '9.5px',
                    color: '#f87171',
                    fontWeight: 800,
                    whiteSpace: 'nowrap',
                  }}
                >
                  {group.pts} pts
                </span>
              )}
            </div>

            {/* Overlapping Cards Strip */}
            <div className="showdown-cards-tray">
              {group.cards.map((c, cIdx) => (
                <div
                  key={c.instanceId || `${gIdx}-${cIdx}`}
                  className="showdown-card-wrapper"
                  style={{
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
    lastKnownHand,
    lastGameConfig,
    leaveTable,
    setAutoMatchmakePending,
    setLastGameConfig,
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
        particleCount: 140,
        spread: 80,
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
    ? (displayName || 'You')
    : opponents.find((p) => p.playerId === winnerId)?.displayName ?? 'Opponent';

  const myScore = isWinner ? 0 : (gameState.viewerScore ?? (opponents.length > 0 ? opponents[0].score : 80));
  const myStatus = isWinner ? 'WON' : (gameState.viewerStatus ?? 'LOST');

  // Viewer's 13 cards (with fallback to lastKnownHand or visual groups if dropped)
  const myShowdownCards: CardInstance[] =
    gameState.hand && gameState.hand.length > 0
      ? gameState.hand
      : lastKnownHand && lastKnownHand.length > 0
        ? lastKnownHand
        : myVisualGroups && myVisualGroups.length > 0
          ? myVisualGroups.flatMap((g) => g.cards)
          : [];

  // Winner's cards (with fallback across gameState, opponents, or winningGroups)
  const winnerOpponent = opponents.find((p) => p.playerId === winnerId);
  const rawWinnerCards: CardInstance[] = isWinner
    ? myShowdownCards
    : winnerOpponent?.hand && winnerOpponent.hand.length > 0
      ? winnerOpponent.hand
      : gameState.winningGroups && gameState.winningGroups.length > 0
        ? gameState.winningGroups.flatMap((wg) => wg.cards)
        : [];

  // Build unified players list, sorted so Winner is #1 at the top
  const unrankedPlayers = [
    {
      playerId,
      name: displayName || 'You',
      isMe: true,
      isWinner,
      status: myStatus,
      score: myScore,
      hand: isWinner && rawWinnerCards.length > 0 ? rawWinnerCards : myShowdownCards,
      isBot: false,
    },
    ...opponents.map((opp) => {
      const isThisOpponentWinner = opp.playerId === winnerId;
      const oppHand = isThisOpponentWinner && rawWinnerCards.length > 0
        ? rawWinnerCards
        : (opp.hand || []);
      return {
        playerId: opp.playerId,
        name: opp.displayName,
        isMe: false,
        isWinner: isThisOpponentWinner,
        status: isThisOpponentWinner ? 'WON' : opp.status,
        score: opp.score,
        hand: oppHand,
        isBot: opp.isBot,
      };
    }),
  ];

  // Winner always at index 0, followed by score ascending
  const allPlayers = [...unrankedPlayers].sort((a, b) => {
    if (a.isWinner) return -1;
    if (b.isWinner) return 1;
    return a.score - b.score;
  });

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

    // Capture table size BEFORE leaveTable clears gameState.
    // Prefer saved config; fall back to seated count (opponents + self).
    const seated = (gameState?.opponents?.length ?? 0) + 1;
    const maxPlayers = Math.max(2, lastGameConfig?.maxPlayers ?? 0, seated);
    setLastGameConfig({
      rulesetId: lastGameConfig?.rulesetId ?? 'POINTS_13',
      entryFee: lastGameConfig?.entryFee ?? 8,
      maxPlayers,
    });

    clearSelection();
    setAutoMatchmakePending(true);
    void clearActiveSessionRemote(playerId);
    socketClient.disconnect();
    leaveTable();
  };

  const handleLeave = () => {
    soundEngine.play('click');
    void clearActiveSessionRemote(playerId);
    socketClient.disconnect();
    leaveTable();
  };

  return (
    <div className="showdown-backdrop">
      <div className={`showdown-modal-container ${isWinner ? 'is-winner-modal' : ''}`}>
        {/* Header Banner */}
        <div className={`showdown-header ${isWinner ? 'winner-header' : 'regular-header'}`}>
          <div
            style={{
              display: 'inline-flex',
              alignItems: 'center',
              justifyContent: 'center',
              width: '52px',
              height: '52px',
              borderRadius: '50%',
              background: isWinner
                ? 'linear-gradient(135deg, #fbbf24, #d97706)'
                : 'linear-gradient(135deg, #475569, #1e293b)',
              boxShadow: isWinner ? '0 0 25px rgba(251, 191, 36, 0.6)' : 'none',
              marginBottom: '8px',
            }}
          >
            {isWinner ? <Trophy size={26} color="#1e1b4b" /> : <Award size={26} color="#94a3b8" />}
          </div>

          <h2
            className="showdown-header-title"
            style={{
              margin: '0 0 4px',
              fontSize: '22px',
              fontWeight: 900,
              color: isWinner ? '#fef08a' : '#f8fafc',
              letterSpacing: '-0.01em',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              gap: '8px',
            }}
          >
            {isWinner ? (
              <>
                <Sparkles size={20} color="#fbbf24" /> You Won the Hand!
              </>
            ) : (
              `${winnerName} Won the Hand`
            )}
          </h2>

          <p style={{ margin: 0, fontSize: '12px', color: '#94a3b8' }}>
            {isWinner
              ? 'Valid declaration! 0 penalty points. All players’ 13 cards are revealed below.'
              : 'Hand completed. Review every player’s 13 cards and score below.'}
          </p>
        </div>

        {/* Scrollable Content: All Players Showdown Cards */}
        <div className="showdown-scoreboard-body">
          {/* Section Header Toolbar */}
          <div
            style={{
              fontSize: '11px',
              fontWeight: 800,
              textTransform: 'uppercase',
              letterSpacing: '0.08em',
              color: '#94a3b8',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'space-between',
              padding: '0 4px',
            }}
          >
            <span style={{ display: 'flex', alignItems: 'center', gap: '6px', color: '#fef08a' }}>
              <Layers size={14} color="#fbbf24" />
              13-Card Showdown Scoreboard (सर्व खेळाडूंचे पत्ते)
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
                background: 'rgba(56, 189, 248, 0.1)',
                border: '1px solid rgba(56, 189, 248, 0.3)',
                borderRadius: '8px',
                color: '#38bdf8',
                fontSize: '11px',
                fontWeight: 700,
                cursor: 'pointer',
                display: 'flex',
                alignItems: 'center',
                gap: '5px',
                padding: '4px 10px',
                transition: 'all 0.15s ease',
              }}
            >
              {showAllCards ? (
                <>
                  <EyeOff size={13} /> Hide Cards
                </>
              ) : (
                <>
                  <Eye size={13} /> Show All 13 Cards
                </>
              )}
            </button>
          </div>

          {/* Players List with Revealed 13 Cards */}
          <div style={{ display: 'flex', flexDirection: 'column', gap: '10px' }}>
            {allPlayers.map((p, pRankIdx) => {
              const won = p.isWinner;
              const expanded = isPlayerExpanded(p.playerId);

              // Calculate player's card groups for display
              let playerGroups: FormattedShowdownGroup[] = [];
              if (won) {
                playerGroups = organizePlayerShowdownCards(
                  p.hand,
                  gameState.cutJoker,
                  true,
                  gameState.winningGroups
                );
              } else if (
                p.isMe &&
                myVisualGroups &&
                myVisualGroups.length > 0 &&
                myVisualGroups.some((g) => g.cards.length > 0)
              ) {
                // Preserves user's visual arrangement if available
                playerGroups = myVisualGroups.map((g) => ({
                  title: '',
                  type: g.groupType,
                  cards: g.cards,
                  pts: g.deadwoodPoints,
                }));
              } else {
                playerGroups = organizePlayerShowdownCards(
                  p.hand,
                  gameState.cutJoker,
                  false
                );
              }

              const totalDisplayedCards =
                playerGroups.reduce((sum, g) => sum + g.cards.length, 0) || p.hand.length;

              return (
                <div
                  key={p.playerId}
                  className={`showdown-player-pod ${won ? 'winner' : ''}`}
                >
                  {/* Player Summary Row */}
                  <div
                    style={{
                      display: 'flex',
                      alignItems: 'center',
                      justifyContent: 'space-between',
                      cursor: 'pointer',
                      userSelect: 'none',
                    }}
                    onClick={() => togglePlayerExpanded(p.playerId)}
                  >
                    <div style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
                      {/* Rank Indicator Badge */}
                      <div
                        style={{
                          width: '32px',
                          height: '32px',
                          borderRadius: '50%',
                          background: won
                            ? 'linear-gradient(135deg, #fbbf24, #d97706)'
                            : '#334155',
                          color: won ? '#1a1a1a' : '#cbd5e1',
                          display: 'flex',
                          alignItems: 'center',
                          justifyContent: 'center',
                          fontWeight: 900,
                          fontSize: won ? '14px' : '12px',
                          flexShrink: 0,
                          boxShadow: won ? '0 0 10px rgba(251, 191, 36, 0.5)' : 'none',
                        }}
                      >
                        {won ? <Crown size={18} color="#1a1a1a" /> : `#${pRankIdx + 1}`}
                      </div>

                      <div>
                        <div
                          style={{
                            fontWeight: 800,
                            fontSize: '15px',
                            color: '#ffffff',
                            display: 'flex',
                            alignItems: 'center',
                            gap: '6px',
                          }}
                        >
                          <span>{p.name}</span>
                          {p.isMe && (
                            <span
                              style={{
                                color: '#fbbf24',
                                background: 'rgba(251, 191, 36, 0.15)',
                                border: '1px solid rgba(251, 191, 36, 0.4)',
                                fontSize: '10px',
                                fontWeight: 900,
                                padding: '1px 6px',
                                borderRadius: '6px',
                              }}
                            >
                              YOU
                            </span>
                          )}
                        </div>

                        <div
                          style={{
                            fontSize: '11px',
                            color: '#94a3b8',
                            display: 'flex',
                            alignItems: 'center',
                            gap: '6px',
                            marginTop: '2px',
                          }}
                        >
                          {won ? (
                            <span
                              style={{
                                color: '#86efac',
                                display: 'flex',
                                alignItems: 'center',
                                gap: '3px',
                                fontWeight: 800,
                              }}
                            >
                              <CheckCircle2 size={13} /> Winner (0 pts)
                            </span>
                          ) : p.status === 'DROPPED' ? (
                            <span
                              style={{
                                color: '#fbbf24',
                                display: 'flex',
                                alignItems: 'center',
                                gap: '3px',
                                fontWeight: 800,
                              }}
                            >
                              <AlertCircle size={13} /> Dropped Hand ({p.score} penalty)
                            </span>
                          ) : (
                            <span
                              style={{
                                color: '#fca5a5',
                                display: 'flex',
                                alignItems: 'center',
                                gap: '3px',
                                fontWeight: 700,
                              }}
                            >
                              <AlertCircle size={13} /> Lost ({p.score} penalty)
                            </span>
                          )}
                        </div>
                      </div>
                    </div>

                    <div style={{ display: 'flex', alignItems: 'center', gap: '14px' }}>
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
                        <div style={{ fontSize: '10px', color: '#94a3b8', fontWeight: 700 }}>
                          {totalDisplayedCards > 0 ? `${totalDisplayedCards} cards` : '13 cards'}
                        </div>
                      </div>

                      <div
                        style={{
                          color: '#94a3b8',
                          background: 'rgba(255, 255, 255, 0.06)',
                          borderRadius: '50%',
                          padding: '4px',
                          display: 'flex',
                          alignItems: 'center',
                          justifyContent: 'center',
                        }}
                      >
                        {expanded ? <ChevronUp size={16} /> : <ChevronDown size={16} />}
                      </div>
                    </div>
                  </div>

                  {/* Expanded 13 Cards Tray for this Player */}
                  {expanded && (
                    <div style={{ marginTop: '6px', animation: 'fadeIn 0.2s ease-out' }}>
                      <ShowdownCardTray
                        groups={playerGroups}
                        wildJoker={gameState.cutJoker}
                        cardCount={totalDisplayedCards}
                      />
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
            padding: '14px 18px 16px',
            display: 'flex',
            gap: '12px',
            background: 'rgba(0, 0, 0, 0.65)',
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
              background: 'linear-gradient(135deg, #fbbf24 0%, #d97706 100%)',
              boxShadow: '0 4px 16px rgba(251, 191, 36, 0.35)',
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
