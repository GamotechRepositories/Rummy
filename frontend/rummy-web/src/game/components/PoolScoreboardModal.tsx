import React from 'react';
import { useGameStore } from '../store/useGameStore';
import { X, Trophy, ShieldAlert, ShieldCheck, User, Flame, Sparkles, Scale } from 'lucide-react';
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

  const threshold = gameState.eliminationThreshold || (gameState.rulesetId?.includes('201') ? 201 : 101);
  const currentDeal = gameState.dealNumber ?? 1;
  const history = gameState.dealHistory ?? [];
  const maxRecordedDeals = Math.max(history.length, 1);

  // Collate all seated players (viewer + opponents)
  interface RowPlayer {
    id: string;
    name: string;
    isViewer: boolean;
    isBot: boolean;
    seatIndex: number;
    cumulativeScore: number;
    isEliminated: boolean;
    roundScores: (number | null)[];
    avatarPhoto?: string | null;
  }

  const allPlayers: RowPlayer[] = [];

  // 1. Viewer
  const viewerCum = gameState.viewerCumulativeScore ?? 0;
  const viewerElim = gameState.viewerIsEliminated || gameState.viewerStatus === 'ELIMINATED';
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
    isEliminated: viewerElim,
    roundScores: viewerRounds,
    avatarPhoto: photoForCharacter(avatarId),
  });

  // 2. Opponents
  for (const opp of gameState.opponents ?? []) {
    const oppCum = opp.cumulativeScore ?? opp.score ?? 0;
    const oppElim = opp.isEliminated || opp.status === 'ELIMINATED';
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
    allPlayers.push({
      id: opp.playerId,
      name: opp.displayName,
      isViewer: false,
      isBot: opp.isBot,
      seatIndex: opp.seatIndex,
      cumulativeScore: oppCum,
      isEliminated: oppElim,
      roundScores: oppRounds,
      avatarPhoto: av.photo,
    });
  }

  // Sort: Active first, ordered by lowest cumulative score (leaders at top)
  allPlayers.sort((a, b) => {
    if (a.isEliminated && !b.isEliminated) return 1;
    if (!a.isEliminated && b.isEliminated) return -1;
    return a.cumulativeScore - b.cumulativeScore;
  });

  const activeSurvivors = allPlayers.filter(p => !p.isEliminated).length;

  return (
    <div
      className="pool-modal-overlay"
      onClick={onClose}
    >
      <div
        className="pool-modal-card"
        onClick={(e) => e.stopPropagation()}
      >
        {/* Modal Header */}
        <div className="pool-modal-header">
          <div className="pool-header-left">
            <div className="pool-header-trophy">
              <Trophy size={24} />
            </div>
            <div className="pool-header-title-block">
              <div className="pool-header-title-row">
                <h2 className="pool-header-title">
                  Pool {threshold} Tournament
                </h2>
              </div>
              <div className="pool-header-chips">
                <span className="pool-chip pool-chip--deal">
                  🎯 Deal {currentDeal}
                </span>
                <span className="pool-chip pool-chip--limit">
                  💀 Limit: {threshold} pts
                </span>
                <span className="pool-chip pool-chip--active">
                  👥 {activeSurvivors}/{allPlayers.length} Active Survivors
                </span>
              </div>
            </div>
          </div>

          <button
            type="button"
            className="pool-modal-close"
            onClick={() => {
              soundEngine.play('click');
              onClose();
            }}
            title="Close scoreboard"
            aria-label="Close"
          >
            <X size={18} />
          </button>
        </div>

        {/* Table Container */}
        <div className="pool-table-body-wrap">
          <table className="pool-table">
            <thead>
              <tr>
                <th className="pool-th" style={{ width: '38%' }}>Player</th>
                {Array.from({ length: maxRecordedDeals }).map((_, idx) => (
                  <th key={idx} className="pool-th" style={{ textAlign: 'center' }}>
                    Deal {idx + 1}
                  </th>
                ))}
                <th className="pool-th" style={{ textAlign: 'center', width: '22%' }}>Total Penalty</th>
                <th className="pool-th" style={{ textAlign: 'right', width: '18%' }}>Status</th>
              </tr>
            </thead>
            <tbody>
              {allPlayers.map((p, rankIdx) => {
                const isLeader = rankIdx === 0 && !p.isEliminated;
                const isDealer = p.seatIndex === gameState.dealerSeatIndex;
                const isDanger = !p.isEliminated && p.cumulativeScore >= threshold * 0.75;
                const percent = Math.min(100, Math.round((p.cumulativeScore / threshold) * 100));

                let barColor = 'linear-gradient(90deg, #10b981, #34d399)';
                let textColor = '#34d399';
                let subtext = `${threshold - p.cumulativeScore} pts to elimination`;
                let subtextColor = '#94a3b8';

                if (p.isEliminated) {
                  barColor = 'linear-gradient(90deg, #b91c1c, #ef4444)';
                  textColor = '#ef4444';
                  subtext = 'ELIMINATED';
                  subtextColor = '#ef4444';
                } else if (isDanger) {
                  barColor = 'linear-gradient(90deg, #f97316, #ef4444)';
                  textColor = '#f87171';
                  subtext = `🔥 DANGER ZONE (${threshold - p.cumulativeScore} pts left)`;
                  subtextColor = '#f87171';
                } else if (p.cumulativeScore >= threshold * 0.5) {
                  barColor = 'linear-gradient(90deg, #d97706, #fbbf24)';
                  textColor = '#fbbf24';
                  subtext = `${threshold - p.cumulativeScore} pts to elimination`;
                  subtextColor = '#fbbf24';
                }

                const trClass = `pool-tr ${p.isViewer ? 'pool-tr--viewer' : ''} ${isDanger ? 'pool-tr--danger' : ''} ${p.isEliminated ? 'pool-tr--eliminated' : ''}`;

                return (
                  <tr key={p.id} className={trClass}>
                    {/* Player Info */}
                    <td className="pool-player-cell">
                      <div className="pool-player-inner">
                        <div className={`pool-rank-badge ${isLeader ? 'pool-rank-badge--crown' : ''}`}>
                          {isLeader ? '👑' : `#${rankIdx + 1}`}
                        </div>

                        <div className={`pool-avatar-ring ${isLeader ? 'pool-avatar-ring--leader' : ''} ${isDanger ? 'pool-avatar-ring--danger' : ''}`}>
                          {p.avatarPhoto ? (
                            <img src={p.avatarPhoto} alt="" style={{ width: '100%', height: '100%', objectFit: 'cover' }} />
                          ) : (
                            <User size={18} color="#94a3b8" />
                          )}
                        </div>

                        <div className="pool-player-details">
                          <div className="pool-player-name-line">
                            <span className="pool-player-name" style={{ color: p.isViewer ? '#fef08a' : '#f8fafc' }}>
                              {p.name}
                            </span>
                            {p.isViewer && <span className="pool-badge-you">YOU</span>}
                          </div>

                          <div className="pool-player-badges-row">
                            {isDealer && (
                              <span className="pool-badge-dealer" title="Dealer for current deal">
                                D
                              </span>
                            )}
                            {isDanger && (
                              <span className="pool-badge-danger">
                                🔥 DANGER
                              </span>
                            )}
                            {p.isBot && <span style={{ fontSize: '10px', color: '#64748b' }}>AI Player</span>}
                            {isLeader && !p.isEliminated && (
                              <span style={{ fontSize: '10px', fontWeight: 800, color: '#fbbf24' }}>Table Leader</span>
                            )}
                          </div>
                        </div>
                      </div>
                    </td>

                    {/* Deal Scores */}
                    {p.roundScores.map((pts, dIdx) => (
                      <td key={dIdx} style={{ padding: '10px 8px', textAlign: 'center' }}>
                        {pts === 0 ? (
                          <span className="pool-deal-score-pill pool-deal-score-pill--won">✓ 0</span>
                        ) : pts !== null ? (
                          <span className="pool-deal-score-pill pool-deal-score-pill--penalty">+{pts}</span>
                        ) : (
                          <span className="pool-deal-score-pill pool-deal-score-pill--empty">—</span>
                        )}
                      </td>
                    ))}

                    {/* Penalty Score Bar */}
                    <td className="pool-score-col">
                      <div className="pool-score-num" style={{ color: textColor }}>
                        <span>{p.cumulativeScore}</span>
                        <span className="pool-score-max">/ {threshold}</span>
                      </div>
                      <div className="pool-progress-track">
                        <div
                          className="pool-progress-fill"
                          style={{
                            width: `${percent}%`,
                            background: barColor,
                          }}
                        />
                      </div>
                      <div className="pool-score-subtext" style={{ color: subtextColor }}>
                        {subtext}
                      </div>
                    </td>

                    {/* Status */}
                    <td className="pool-status-cell">
                      {p.isEliminated ? (
                        <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'flex-end', gap: '5px' }}>
                          <span className="pool-status-pill pool-status-pill--eliminated">
                            <ShieldAlert size={12} />
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
                                display: 'inline-flex',
                                alignItems: 'center',
                                gap: '4px',
                                background: 'linear-gradient(135deg, #10b981, #059669)',
                                border: '1px solid #6ee7b7',
                                color: '#fff',
                                fontSize: '11px',
                                fontWeight: 800,
                                padding: '4px 10px',
                                borderRadius: '8px',
                                cursor: 'pointer',
                                boxShadow: '0 0 10px rgba(16, 185, 129, 0.45)',
                              }}
                            >
                              <Sparkles size={12} />
                              Re-Join @ {gameState.rejoinScore}
                            </button>
                          )}
                        </div>
                      ) : (
                        <span className="pool-status-pill pool-status-pill--active">
                          <ShieldCheck size={12} />
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

        {/* Footer */}
        <div className="pool-modal-footer">
          <div className="pool-footer-cards">
            <div className="pool-footer-card">
              <Flame size={15} color="#f59e0b" />
              <span>
                <strong>Drop Penalties:</strong> 1st = {threshold === 201 ? '25' : '20'} · Mid = {threshold === 201 ? '50' : '40'} · Cap = 80 pts
              </span>
            </div>
            <div className="pool-footer-card">
              <Scale size={15} color="#34d399" />
              <span>
                <strong>Re-Join Rule:</strong> Allowed if leader ≤ {threshold === 201 ? '174' : '79'} pts (Re-enter at Leader + 1)
              </span>
            </div>
          </div>

          <button
            type="button"
            id="btn-close-scoreboard"
            className="pool-footer-close-btn"
            onClick={() => {
              soundEngine.play('click');
              onClose();
            }}
          >
            Close Scoreboard
          </button>
        </div>
      </div>
    </div>
  );
};
