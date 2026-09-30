import React from 'react';
import { useGameStore } from '../store/useGameStore';
import { X, Trophy } from 'lucide-react';
import { soundEngine } from '../audio/soundEngine';
import { socketClient } from '../websocket/GameSocketClient';
import { photoForCharacter, getAvatarForPlayer } from '../utils/avatarUtils';

interface PoolScoreboardModalProps {
  isOpen: boolean;
  onClose: () => void;
}

export const PoolScoreboardModal: React.FC<PoolScoreboardModalProps> = ({ isOpen, onClose }) => {
  const { gameState, playerId, displayName, avatarId } = useGameStore();

  if (!isOpen || !gameState) return null;

  const isDeals = (gameState.totalDeals ?? 0) > 1 || (gameState.rulesetId ?? '').toUpperCase().includes('DEAL');
  const scheduledDeals = gameState.rulesetId?.includes('3') ? 3 : 2;
  const totalDeals = gameState.totalDeals ?? scheduledDeals;
  const isTieBreaker = isDeals && (totalDeals > scheduledDeals || (gameState.dealNumber ?? 1) > scheduledDeals);
  const threshold = gameState.eliminationThreshold || (gameState.rulesetId?.includes('201') ? 201 : 101);
  const currentDeal = gameState.dealNumber ?? 1;
  const history = gameState.dealHistory ?? [];
  const maxRecordedDeals = Math.max(history.length, isDeals ? totalDeals : 1, currentDeal);

  // Collate all seated players (viewer + opponents)
  interface RowPlayer {
    id: string;
    name: string;
    isViewer: boolean;
    isBot: boolean;
    seatIndex: number;
    cumulativeScore: number;
    chipBalance: number;
    isEliminated: boolean;
    roundScores: (number | null)[];
    avatarPhoto?: string | null;
  }

  const allPlayers: RowPlayer[] = [];

  // 1. Viewer
  const viewerCum = gameState.viewerCumulativeScore ?? 0;
  const viewerElim = gameState.viewerIsEliminated || gameState.viewerStatus === 'ELIMINATED';
  const viewerChips = gameState.viewerChipBalance ?? 0;
  const viewerRounds: (number | null)[] = [];
  for (let d = 1; d <= maxRecordedDeals; d++) {
    const rec = history.find(h => h.dealNumber === d);
    if (rec && rec.roundScores && rec.roundScores[playerId] !== undefined) {
      viewerRounds.push(rec.roundScores[playerId]);
    } else {
      viewerRounds.push(null);
    }
  }

  allPlayers.push({
    id: playerId,
    name: displayName,
    isViewer: true,
    isBot: false,
    seatIndex: gameState.viewerSeatIndex ?? 0,
    cumulativeScore: viewerCum,
    chipBalance: viewerChips,
    isEliminated: viewerElim,
    roundScores: viewerRounds,
    avatarPhoto: photoForCharacter(avatarId),
  });

  // 2. Opponents
  for (const opp of gameState.opponents ?? []) {
    const oppCum = opp.cumulativeScore ?? opp.score ?? 0;
    const oppElim = opp.isEliminated || opp.status === 'ELIMINATED';
    const oppStanding = gameState.standings?.find(s => s.playerId === opp.playerId);
    const oppChips = opp.chipBalance ?? oppStanding?.chipBalance ?? 0;
    const oppRounds: (number | null)[] = [];
    for (let d = 1; d <= maxRecordedDeals; d++) {
      const rec = history.find(h => h.dealNumber === d);
      if (rec && rec.roundScores && rec.roundScores[opp.playerId] !== undefined) {
        oppRounds.push(rec.roundScores[opp.playerId]);
      } else {
        oppRounds.push(null);
      }
    }

    const av = getAvatarForPlayer(opp.displayName || opp.playerId);
    const avPhoto = opp.avatarId ? photoForCharacter(opp.avatarId) : av.photo;
    allPlayers.push({
      id: opp.playerId,
      name: opp.displayName,
      isViewer: false,
      isBot: opp.isBot,
      seatIndex: opp.seatIndex,
      cumulativeScore: oppCum,
      chipBalance: oppChips,
      isEliminated: oppElim,
      roundScores: oppRounds,
      avatarPhoto: avPhoto,
    });
  }

  // Sort: For Deals Rummy, highest chip balance first. For Pool, lowest cumulative score first
  if (isDeals) {
    allPlayers.sort((a, b) => b.chipBalance - a.chipBalance || a.cumulativeScore - b.cumulativeScore);
  } else {
    allPlayers.sort((a, b) => {
      if (a.isEliminated && !b.isEliminated) return 1;
      if (!a.isEliminated && b.isEliminated) return -1;
      return a.cumulativeScore - b.cumulativeScore;
    });
  }

  const activeSurvivors = allPlayers.filter(p => !p.isEliminated).length;

  return (
    <div
      className="royal-dialog-backdrop"
      role="presentation"
      onClick={onClose}
    >
      <div
        className="royal-dialog-card royal-dialog-card--scoreboard"
        style={{ width: 'min(880px, 95vw)', maxWidth: '880px' }}
        role="dialog"
        aria-label="Pool Scoreboard"
        onClick={(e) => e.stopPropagation()}
      >
        {/* Close Button */}
        <button
          type="button"
          className="royal-dialog-close"
          onClick={() => {
            soundEngine.play('click');
            onClose();
          }}
          aria-label="Close"
        >
          <X size={18} />
        </button>

        {/* Crest */}
        <div className="royal-dialog-crest-wrap">
          <div className="royal-dialog-crest-glow royal-dialog-crest-glow--amber" />
          <div className="royal-dialog-crest-badge">
            <Trophy size={26} color="#fbbf24" />
          </div>
        </div>

        {/* Title & Subtitle */}
        <h3 className="royal-dialog-title">
          {isDeals
            ? (isTieBreaker ? '⚡ Deals Sudden-Death Playoff ⚡' : `${totalDeals} Deals Scoreboard`)
            : `Pool ${threshold} Scoreboard`}
        </h3>
        <p className="royal-dialog-subtitle">
          {isDeals
            ? (isTieBreaker
                ? `Playoff Deal ${currentDeal} in progress · Tied leaders playing sudden-death deal to decide the champion!`
                : `Deal ${currentDeal} of ${totalDeals} · Player with most chips after ${totalDeals} deals wins!`)
            : `Deal ${currentDeal} in progress · ${activeSurvivors} of ${allPlayers.length} players active`}
        </p>

        {/* Variant Badge */}
        <div className="table-menu-badge-wrap" style={{ padding: '0 0 14px', display: 'flex', justifyContent: 'center' }}>
          <span className="table-menu-name">
            {isDeals
              ? (isTieBreaker
                  ? `⚡ Playoff Active · Starting Chips: ${totalDeals * 80}`
                  : `🪙 Deals Rummy · ${totalDeals} Deals · Starting Chips: ${totalDeals * 80}`)
              : `♠ Pool ${threshold} · Elimination at ${threshold} pts`}
          </span>
        </div>

        {/* Scoreboard Table Section */}
        <div className="royal-scoreboard-wrap">
          <table className="royal-scoreboard-table">
            <thead>
              <tr>
                <th style={{ textAlign: 'left', minWidth: '150px' }}>Player</th>
                {Array.from({ length: maxRecordedDeals }).map((_, idx) => (
                  <th key={idx} style={{ textAlign: 'center', minWidth: '60px' }}>
                    {isDeals && idx + 1 > scheduledDeals ? (
                      <span style={{ color: '#fbbf24' }}>Playoff {idx + 1}</span>
                    ) : (
                      `Deal ${idx + 1}`
                    )}
                  </th>
                ))}
                <th style={{ textAlign: 'center', minWidth: '110px' }}>
                  {isDeals ? 'Chips Balance' : 'Total Points'}
                </th>
                <th style={{ textAlign: 'right', minWidth: '85px' }}>Status</th>
              </tr>
            </thead>
            <tbody>
              {allPlayers.map((p, rankIdx) => {
                const isLeader = rankIdx === 0 && !p.isEliminated;
                const isDealer = p.seatIndex === gameState.dealerSeatIndex;
                const isDanger = !p.isEliminated && p.cumulativeScore >= threshold * 0.75;
                const percent = Math.min(100, Math.round((p.cumulativeScore / threshold) * 100));

                let scoreColor = '#34d399';
                if (p.isEliminated) scoreColor = '#ef4444';
                else if (isDanger) scoreColor = '#f87171';
                else if (p.cumulativeScore >= threshold * 0.5) scoreColor = '#fbbf24';

                const rowClass = `${p.isViewer ? 'royal-scoreboard-row--viewer' : ''} ${p.isEliminated ? 'royal-scoreboard-row--eliminated' : ''}`;

                return (
                  <tr key={p.id} className={rowClass}>
                    {/* Player Info */}
                    <td>
                      <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
                        <div
                          style={{
                            width: '28px',
                            height: '28px',
                            borderRadius: '50%',
                            overflow: 'hidden',
                            border: `1.5px solid ${isLeader ? '#fbbf24' : 'rgba(255,255,255,0.2)'}`,
                            background: '#1e293b',
                            display: 'flex',
                            alignItems: 'center',
                            justifyContent: 'center',
                            flexShrink: 0,
                          }}
                        >
                          {p.avatarPhoto ? (
                            <img src={p.avatarPhoto} alt="" style={{ width: '100%', height: '100%', objectFit: 'cover' }} />
                          ) : (
                            <span style={{ fontSize: '11px', color: '#94a3b8' }}>#</span>
                          )}
                        </div>

                        <div style={{ textAlign: 'left' }}>
                          <div style={{ fontWeight: 800, color: p.isViewer ? '#fde047' : '#f8fafc', fontSize: '12.5px' }}>
                            {p.name}
                            {p.isViewer && <span style={{ color: '#fbbf24', marginLeft: '4px', fontSize: '10.5px' }}>(You)</span>}
                          </div>
                          <div style={{ fontSize: '10.5px', color: '#94a3b8', marginTop: '1px' }}>
                            {isLeader && <span style={{ color: '#fbbf24', marginRight: '6px' }}>Leader</span>}
                            {isDealer && <span style={{ color: '#fde047', marginRight: '6px' }}>Dealer</span>}
                            {!isDeals && isDanger && <span style={{ color: '#f87171', marginRight: '6px' }}>Danger</span>}
                            {isDeals && <span style={{ color: '#fbbf24', marginRight: '6px' }}>🪙 {p.chipBalance}</span>}
                            {p.isBot && <span>AI</span>}
                          </div>
                        </div>
                      </div>
                    </td>

                    {/* Deal Breakdown */}
                    {p.roundScores.map((pts, dIdx) => (
                      <td key={dIdx} style={{ textAlign: 'center' }}>
                        {pts === 0 ? (
                          <span className="royal-score-pill royal-score-pill--win">0</span>
                        ) : pts !== null ? (
                          <span className="royal-score-pill royal-score-pill--penalty">+{pts}</span>
                        ) : (
                          <span style={{ color: '#64748b' }}>—</span>
                        )}
                      </td>
                    ))}

                    {/* Total Penalty Points or Chips */}
                    <td style={{ textAlign: 'center' }}>
                      {isDeals ? (
                        <div style={{ fontWeight: 900, fontSize: '13.5px', color: '#fbbf24', display: 'inline-flex', alignItems: 'center', gap: '4px' }}>
                          <span>🪙</span>
                          <span>{p.chipBalance}</span>
                        </div>
                      ) : (
                        <>
                          <div style={{ fontWeight: 800, fontSize: '13px', color: scoreColor }}>
                            {p.cumulativeScore} <span style={{ fontSize: '10.5px', color: '#64748b' }}>/ {threshold}</span>
                          </div>
                          <div className="royal-score-bar-bg">
                            <div
                              className={`royal-score-bar-fill ${isDanger || p.isEliminated ? 'royal-score-bar-fill--danger' : ''}`}
                              style={{ width: `${percent}%` }}
                            />
                          </div>
                        </>
                      )}
                    </td>

                    {/* Status */}
                    <td style={{ textAlign: 'right' }}>
                      {isDeals ? (
                        <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'flex-end', gap: '2px' }}>
                          <span style={{ color: isLeader ? '#fbbf24' : '#94a3b8', fontWeight: 800, fontSize: '11.5px' }}>
                            {isLeader ? '👑 Leader' : `Rank #${rankIdx + 1}`}
                          </span>
                        </div>
                      ) : p.isEliminated ? (
                        <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'flex-end', gap: '3px' }}>
                          <span style={{ color: '#ef4444', fontWeight: 800, fontSize: '11px' }}>
                            ELIMINATED
                          </span>
                          {p.isViewer && gameState.canRejoin && (
                            <button
                              type="button"
                              onClick={() => {
                                soundEngine.play('click');
                                socketClient.rejoinTable();
                              }}
                              style={{
                                background: 'transparent',
                                border: '1px solid #34d399',
                                color: '#34d399',
                                borderRadius: '4px',
                                padding: '2px 6px',
                                fontSize: '10px',
                                fontWeight: 800,
                                cursor: 'pointer',
                              }}
                            >
                              Re-Join @ {gameState.rejoinScore}
                            </button>
                          )}
                        </div>
                      ) : (
                        <span style={{ color: '#34d399', fontWeight: 800, fontSize: '11px' }}>
                          ACTIVE
                        </span>
                      )}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>



        {/* Action Button matching Leave Table modal */}
        <div className="royal-dialog-actions" style={{ maxWidth: '320px', margin: '0 auto', width: '100%' }}>
          <button
            type="button"
            className="royal-btn-gold"
            onClick={() => {
              soundEngine.play('click');
              onClose();
            }}
          >
            Resume Game
          </button>
        </div>
      </div>
    </div>
  );
};
