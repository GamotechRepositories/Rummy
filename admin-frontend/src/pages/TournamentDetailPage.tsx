import React, { useState, useEffect, useCallback } from 'react';
import { useParams, useNavigate, Link } from 'react-router-dom';
import { api } from '../api/client';
import {
  ArrowLeft,
  Trophy,
  Users,
  RefreshCw,
  Layers,
  CheckCircle,
  XCircle,
  Clock,
  Ban,
  Search,
  ExternalLink,
  Award,
} from 'lucide-react';

interface TablePlayer {
  playerId: string;
  displayName: string;
  finalScore: number;
  won: boolean;
  qualified: boolean;
  status: string;
}

interface RoundTable {
  tableId: string;
  gameId: string | null;
  status: string;
  winnerPlayerId: string | null;
  winnerName: string | null;
  players: TablePlayer[];
}

interface RoundDetail {
  roundNumber: number;
  status: string;
  startedAt?: string;
  finishedAt?: string;
  tableCount: number;
  qualifiedPlayerIds: string[];
  tables: RoundTable[];
}

interface PlayerRoundEntry {
  roundNumber: number;
  tableId: string;
  gameId: string | null;
  finalScore: number;
  won: boolean;
  qualified: boolean;
  status: string;
}

interface PlayerProfile {
  playerId: string;
  displayName: string;
  operatorId: string;
  registeredAt: string;
  checkedIn: boolean;
  refunded: boolean;
  finalRank: number | null;
  prizeRs: number;
  roundHistory: PlayerRoundEntry[];
}

const STATUS_BADGE: Record<string, { label: string; cls: string }> = {
  DRAFT:       { label: 'Draft',       cls: 'badge-dim' },
  REGISTERING: { label: 'Registering', cls: 'badge-emerald' },
  STARTING:    { label: 'Check-In',    cls: 'badge-amber' },
  RUNNING:     { label: 'Live',        cls: 'badge-cyan' },
  COMPLETED:   { label: 'Completed',   cls: 'badge-violet' },
  CANCELLED:   { label: 'Cancelled',   cls: 'badge-rose' },
};

function fmtDate(iso?: string) {
  if (!iso) return '—';
  return new Date(iso).toLocaleString('en-IN', { dateStyle: 'medium', timeStyle: 'short' });
}

export const TournamentDetailPage: React.FC = () => {
  const { tournamentId } = useParams<{ tournamentId: string }>();
  const navigate = useNavigate();

  const [tournament, setTournament] = useState<any>(null);
  const [roundDetails, setRoundDetails] = useState<RoundDetail[]>([]);
  const [playerProfiles, setPlayerProfiles] = useState<PlayerProfile[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [activeTab, setActiveTab] = useState<'ROUNDS' | 'PLAYERS' | 'RANKINGS'>('ROUNDS');
  const [search, setSearch] = useState('');
  const [selectedPlayer, setSelectedPlayer] = useState<PlayerProfile | null>(null);

  const fetchDetail = useCallback(async () => {
    if (!tournamentId) return;
    setLoading(true);
    setError(null);
    try {
      const res = await api.get(`/tournaments/${tournamentId}`);
      setTournament(res.data.tournament);
      setRoundDetails(res.data.roundDetails || []);
      setPlayerProfiles(res.data.playerProfiles || []);
      if (selectedPlayer) {
        const updated = (res.data.playerProfiles || []).find((p: PlayerProfile) => p.playerId === selectedPlayer.playerId);
        if (updated) setSelectedPlayer(updated);
      }
    } catch (err: any) {
      setError(err.response?.data?.message || err.message || 'Failed to load tournament details');
    } finally {
      setLoading(false);
    }
  }, [tournamentId]);

  useEffect(() => {
    fetchDetail();
  }, [fetchDetail]);

  const handleCancel = async () => {
    if (!tournamentId) return;
    const reason = window.prompt('Reason for cancellation (players will see this):');
    if (reason === null) return;
    try {
      await api.post(`/tournaments/${tournamentId}/cancel`, { reason });
      await fetchDetail();
    } catch (err: any) {
      alert(err.response?.data?.message || err.message);
    }
  };

  if (loading) {
    return (
      <div className="admin-page-root">
        <div style={{ padding: 48, textAlign: 'center', color: 'var(--text-dim)' }}>
          Loading tournament details…
        </div>
      </div>
    );
  }

  if (error || !tournament) {
    return (
      <div className="admin-page-root">
        <button className="btn" onClick={() => navigate('/tournaments')} style={{ alignSelf: 'flex-start' }}>
          <ArrowLeft size={14} /> Back to Tournaments
        </button>
        <div className="admin-alert admin-alert-error">{error || 'Tournament not found'}</div>
      </div>
    );
  }

  const badge = STATUS_BADGE[tournament.status] ?? { label: tournament.status, cls: 'badge-dim' };
  const isCancelled = tournament.status === 'CANCELLED';
  const isCompleted = tournament.status === 'COMPLETED';
  const registeredCount = playerProfiles.length;
  const checkedInCount = playerProfiles.filter((p) => p.checkedIn).length;
  const missedCount = registeredCount - checkedInCount;
  const rawCollected = registeredCount * (tournament.entryFeeRs ?? 0);
  const refundedTotal = playerProfiles.filter((p) => p.refunded).length * (tournament.entryFeeRs ?? 0);
  const netCollected = isCancelled ? Math.max(0, rawCollected - refundedTotal) : rawCollected;
  const actualWinnerPaid = (tournament.finalRankings || []).reduce((sum: number, r: any) => sum + Number(r.prizeRs || 0), 0);
  const prizeOutflow = isCancelled ? 0 : isCompleted ? actualWinnerPaid : (tournament.fixedPrizeRs ?? 0);
  const remainingProfit = netCollected - prizeOutflow;
  const totalTables = roundDetails.reduce((sum, r) => sum + r.tableCount, 0);

  const filteredPlayers = playerProfiles.filter((p) => {
    const q = search.trim().toLowerCase();
    if (!q) return true;
    return p.displayName.toLowerCase().includes(q) || p.playerId.toLowerCase().includes(q);
  });

  return (
    <div className="admin-page-root">
      {/* Header */}
      <div className="admin-page-header">
        <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
          <button className="btn" onClick={() => navigate('/tournaments')}>
            <ArrowLeft size={14} /> Back
          </button>
          <div>
            <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
              <h1 className="admin-page-title" style={{ marginBottom: 0 }}>
                <Trophy size={20} style={{ color: 'var(--accent-gold)', marginRight: 8 }} />
                {tournament.title}
              </h1>
              <span className={`badge ${badge.cls}`}>{badge.label}</span>
              <code style={{ fontSize: 11, color: 'var(--text-dim)' }}>{tournament.tournamentId}</code>
            </div>
            <p className="admin-page-subtitle" style={{ marginTop: 4 }}>
              Deals Rummy (3 Deals · 6 Players) · Min {tournament.minPlayers} / Max {tournament.maxPlayers} · Check-In Window: {tournament.checkInWindowMinutes ?? 5} mins
            </p>
          </div>
        </div>

        <div style={{ display: 'flex', gap: 8 }}>
          <button className="btn" onClick={fetchDetail}>
            <RefreshCw size={14} /> Refresh
          </button>
          {!['COMPLETED', 'CANCELLED', 'RUNNING'].includes(tournament.status) && (
            <button className="btn btn-danger" onClick={handleCancel}>
              <Ban size={14} /> Cancel &amp; Refund
            </button>
          )}
        </div>
      </div>

      {tournament.cancelledReason && (
        <div className="admin-alert admin-alert-error">
          <XCircle size={15} />
          <span><strong>Cancelled Reason:</strong> {tournament.cancelledReason}</span>
        </div>
      )}

      {/* KPI Row */}
      <div className="grid-kpis" style={{ marginBottom: 0 }}>
        <div className="kpi-card">
          <div className="kpi-card-header">
            <span className="kpi-card-title">Registered Players</span>
            <Users size={15} color="var(--accent-cyan)" />
          </div>
          <div className="kpi-card-value">
            {registeredCount} <span style={{ fontSize: 14, color: 'var(--text-dim)' }}>/ {tournament.maxPlayers}</span>
          </div>
          <div className="kpi-card-delta" style={{ color: registeredCount >= tournament.minPlayers ? 'var(--accent-emerald)' : 'var(--accent-amber)' }}>
            Min required: {tournament.minPlayers}
          </div>
        </div>

        <div className="kpi-card">
          <div className="kpi-card-header">
            <span className="kpi-card-title">Joined (Checked-In)</span>
            <CheckCircle size={15} color="var(--accent-emerald)" />
          </div>
          <div className="kpi-card-value" style={{ color: 'var(--accent-emerald)' }}>
            {checkedInCount} <span style={{ fontSize: 14, color: 'var(--text-dim)' }}>/ {registeredCount}</span>
          </div>
          <div className="kpi-card-delta" style={{ color: missedCount > 0 ? '#fda4af' : 'var(--text-dim)' }}>
            {missedCount} missed check-in / no-show
          </div>
        </div>

        <div className="kpi-card">
          <div className="kpi-card-header">
            <span className="kpi-card-title">Total Collection</span>
            <Layers size={15} color="var(--accent-gold)" />
          </div>
          <div className="kpi-card-value" style={{ color: 'var(--accent-cyan)' }}>
            ₹{rawCollected.toLocaleString()}
          </div>
          <div className="kpi-card-delta" style={{ color: 'var(--text-dim)' }}>
            {registeredCount} × ₹{tournament.entryFeeRs} {isCancelled ? `(₹${refundedTotal.toLocaleString()} refunded)` : `· ${roundDetails.length} Rounds · ${totalTables} Tables`}
          </div>
        </div>

        <div className="kpi-card">
          <div className="kpi-card-header">
            <span className="kpi-card-title">{isCompleted ? 'Paid to Winner' : 'Fixed Prize Outflow'}</span>
            <Trophy size={15} color="var(--accent-gold)" />
          </div>
          <div className="kpi-card-value" style={{ color: 'var(--accent-gold)' }}>
            ₹{prizeOutflow.toLocaleString()}
          </div>
          <div className="kpi-card-delta" style={{ color: 'var(--text-dim)' }}>
            {isCancelled ? 'Cancelled (₹0 paid)' : isCompleted ? 'Credited to champion wallet' : `Target Fixed Prize: ₹${(tournament.fixedPrizeRs ?? 0).toLocaleString()}`}
          </div>
        </div>

        <div className="kpi-card">
          <div className="kpi-card-header">
            <span className="kpi-card-title">Platform Remaining (Profit / Loss)</span>
            <Award size={15} color={remainingProfit >= 0 ? 'var(--accent-emerald)' : '#f43f5e'} />
          </div>
          <div className="kpi-card-value" style={{ color: remainingProfit >= 0 ? 'var(--accent-emerald)' : '#f43f5e' }}>
            {remainingProfit >= 0 ? `+₹${remainingProfit.toLocaleString()}` : `-₹${Math.abs(remainingProfit).toLocaleString()}`}
          </div>
          <div className="kpi-card-delta" style={{ color: 'var(--text-dim)' }}>
            {isCancelled ? 'All entries refunded' : remainingProfit >= 0 ? 'Net profit kept by platform' : 'Platform overlay (loss)'}
          </div>
        </div>
      </div>

      {/* Schedule Strip */}
      <div className="admin-card" style={{ padding: '12px 16px', display: 'flex', gap: 24, flexWrap: 'wrap', fontSize: 12 }}>
        <div><Clock size={12} style={{ marginRight: 4, color: 'var(--text-dim)' }} /><strong>Reg. Opens:</strong> {fmtDate(tournament.registrationStartsAt)}</div>
        <div><Clock size={12} style={{ marginRight: 4, color: 'var(--text-dim)' }} /><strong>Reg. Closes:</strong> {fmtDate(tournament.registrationEndsAt)}</div>
        <div><Clock size={12} style={{ marginRight: 4, color: 'var(--accent-gold)' }} /><strong>Start Time:</strong> {fmtDate(tournament.startsAt)}</div>
        {tournament.checkInEndsAt && (
          <div><Clock size={12} style={{ marginRight: 4, color: 'var(--accent-emerald)' }} /><strong>Check-In Ended:</strong> {fmtDate(tournament.checkInEndsAt)}</div>
        )}
      </div>

      {/* Tabs */}
      <div style={{ display: 'flex', gap: 8 }}>
        <button className={`btn ${activeTab === 'ROUNDS' ? 'btn-primary' : ''}`} onClick={() => setActiveTab('ROUNDS')}>
          <Layers size={14} /> Rounds &amp; Tables ({roundDetails.length})
        </button>
        <button className={`btn ${activeTab === 'PLAYERS' ? 'btn-primary' : ''}`} onClick={() => setActiveTab('PLAYERS')}>
          <Users size={14} /> Player Profiles ({playerProfiles.length})
        </button>
        <button className={`btn ${activeTab === 'RANKINGS' ? 'btn-primary' : ''}`} onClick={() => setActiveTab('RANKINGS')}>
          <Trophy size={14} /> Final Rankings ({(tournament.finalRankings || []).length})
        </button>
      </div>

      {/* TAB 1: ROUNDS & TABLES */}
      {activeTab === 'ROUNDS' && (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 18 }}>
          {roundDetails.length === 0 ? (
            <div className="admin-card" style={{ textAlign: 'center', padding: 36, color: 'var(--text-dim)' }}>
              No rounds have started yet.
            </div>
          ) : (
            roundDetails.map((rd) => {
              const isFinal = rd.tableCount === 1 && rd.roundNumber === roundDetails.length && tournament.status === 'COMPLETED';
              return (
                <div key={rd.roundNumber} className="table-panel">
                  <div className="table-panel-header">
                    <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
                      <span className="table-panel-title">
                        {isFinal ? `🏆 Final Round (Round ${rd.roundNumber})` : `Round ${rd.roundNumber}`}
                      </span>
                      <span className={`badge ${rd.status === 'DONE' ? 'badge-emerald' : 'badge-cyan'}`}>{rd.status}</span>
                      <span className="badge badge-dim">{rd.tableCount} {rd.tableCount === 1 ? 'Table' : 'Tables'}</span>
                    </div>
                    <div style={{ fontSize: 12, color: 'var(--text-dim)' }}>
                      Started: {fmtDate(rd.startedAt)} {rd.finishedAt ? `· Finished: ${fmtDate(rd.finishedAt)}` : ''}
                    </div>
                  </div>

                  <div style={{ padding: 14, display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(340px, 1fr))', gap: 14 }}>
                    {rd.tables.map((tbl, idx) => (
                      <div key={tbl.tableId} style={{ background: 'rgba(255,255,255,0.02)', border: '1px solid var(--border-subtle)', borderRadius: 8, overflow: 'hidden' }}>
                        <div style={{ padding: '8px 12px', background: 'rgba(0,0,0,0.25)', borderBottom: '1px solid var(--border-subtle)', display: 'flex', justifyContent: 'space-between', alignItems: 'center', fontSize: 12 }}>
                          <div>
                            <strong>Table #{idx + 1}</strong>{' '}
                            <code style={{ fontSize: 10, color: 'var(--text-dim)' }}>{tbl.tableId}</code>
                          </div>
                          <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                            {tbl.winnerName && (
                              <span className="badge badge-amber" style={{ fontSize: 10 }}>
                                🏆 {tbl.winnerName}
                              </span>
                            )}
                            {tbl.gameId && (
                              <Link to={`/games/${tbl.gameId}`} className="btn" style={{ padding: '2px 7px', fontSize: 10.5 }}>
                                <ExternalLink size={11} /> Game
                              </Link>
                            )}
                          </div>
                        </div>

                        {tbl.players.length === 0 ? (
                          <div style={{ padding: 14, fontSize: 12, color: 'var(--text-dim)' }}>
                            Table active / waiting for deal results…
                          </div>
                        ) : (
                          <table className="data-table">
                            <thead>
                              <tr>
                                <th>Player</th>
                                <th>Score / Chips</th>
                                <th>Result</th>
                              </tr>
                            </thead>
                            <tbody>
                              {tbl.players.map((p) => (
                                <tr key={p.playerId}>
                                  <td>
                                    <div style={{ fontWeight: 600 }}>{p.displayName}</div>
                                    <div style={{ fontSize: 10, color: 'var(--text-dim)' }}>{p.playerId}</div>
                                  </td>
                                  <td style={{ fontFamily: 'var(--font-mono)', fontWeight: 700 }}>{p.finalScore}</td>
                                  <td>
                                    {p.won ? (
                                      <span className="badge badge-amber">🏆 Winner</span>
                                    ) : p.qualified ? (
                                      <span className="badge badge-emerald">✓ Qualified</span>
                                    ) : (
                                      <span className="badge badge-dim">{p.status}</span>
                                    )}
                                  </td>
                                </tr>
                              ))}
                            </tbody>
                          </table>
                        )}
                      </div>
                    ))}
                  </div>
                </div>
              );
            })
          )}
        </div>
      )}

      {/* TAB 2: PLAYER PROFILES */}
      {activeTab === 'PLAYERS' && (
        <div style={{ display: 'grid', gridTemplateColumns: selectedPlayer ? '1fr 380px' : '1fr', gap: 18, alignItems: 'start' }}>
          <div className="table-panel">
            <div className="table-panel-header" style={{ gap: 12 }}>
              <span className="table-panel-title">Registered Player Profiles ({filteredPlayers.length})</span>
              <div style={{ display: 'flex', alignItems: 'center', gap: 6, background: 'rgba(255,255,255,0.05)', padding: '4px 10px', borderRadius: 6, border: '1px solid var(--border-subtle)' }}>
                <Search size={13} color="var(--text-dim)" />
                <input
                  placeholder="Search player name or ID…"
                  value={search}
                  onChange={(e) => setSearch(e.target.value)}
                  style={{ background: 'transparent', border: 'none', outline: 'none', color: '#fff', fontSize: 12, width: 200 }}
                />
              </div>
            </div>

            <div style={{ overflowX: 'auto' }}>
              <table className="data-table">
                <thead>
                  <tr>
                    <th>#</th>
                    <th>Player</th>
                    <th>Operator</th>
                    <th>Check-In</th>
                    <th>Rounds Played</th>
                    <th>Final Rank</th>
                    <th>Prize Won</th>
                    <th>Profile</th>
                  </tr>
                </thead>
                <tbody>
                  {filteredPlayers.map((p, idx) => (
                    <tr
                      key={p.playerId}
                      onClick={() => setSelectedPlayer(p)}
                      style={{ cursor: 'pointer', background: selectedPlayer?.playerId === p.playerId ? 'rgba(212,175,55,0.08)' : undefined }}
                    >
                      <td>{idx + 1}</td>
                      <td>
                        <div style={{ fontWeight: 700 }}>{p.displayName}</div>
                        <div style={{ fontSize: 10.5, color: 'var(--text-dim)' }}>{p.playerId}</div>
                      </td>
                      <td><span className="badge badge-dim">{p.operatorId}</span></td>
                      <td>
                        {p.refunded ? (
                          <span className="badge badge-rose">↩ Refunded</span>
                        ) : p.checkedIn ? (
                          <span className="badge badge-emerald">✓ Joined</span>
                        ) : (
                          <span className="badge badge-amber">Missed</span>
                        )}
                      </td>
                      <td>
                        <div style={{ display: 'flex', gap: 4, flexWrap: 'wrap' }}>
                          {p.roundHistory.length === 0 ? (
                            <span style={{ color: 'var(--text-dim)', fontSize: 11 }}>—</span>
                          ) : (
                            p.roundHistory.map((rh) => (
                              <span
                                key={rh.roundNumber}
                                className={`badge ${rh.won ? 'badge-amber' : rh.qualified ? 'badge-emerald' : 'badge-dim'}`}
                                style={{ fontSize: 10 }}
                              >
                                R{rh.roundNumber}: {rh.finalScore} {rh.won ? '🏆' : rh.qualified ? '✓' : ''}
                              </span>
                            ))
                          )}
                        </div>
                      </td>
                      <td>{p.finalRank ? <strong>#{p.finalRank}</strong> : '—'}</td>
                      <td style={{ fontWeight: 700, color: p.prizeRs > 0 ? 'var(--accent-emerald)' : 'var(--text-dim)' }}>
                        {p.prizeRs > 0 ? `₹${p.prizeRs.toLocaleString()}` : '—'}
                      </td>
                      <td>
                        <button className="btn" style={{ padding: '2px 8px', fontSize: 11 }}>
                          Inspect
                        </button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>

          {/* Selected Player Journey Card */}
          {selectedPlayer && (
            <div className="admin-card">
              <div className="admin-card-header">
                <span>👤 {selectedPlayer.displayName}</span>
                <button className="btn" style={{ padding: '2px 8px' }} onClick={() => setSelectedPlayer(null)}>✕</button>
              </div>
              <div style={{ display: 'flex', flexDirection: 'column', gap: 10, fontSize: 12.5 }}>
                <div style={{ color: 'var(--text-dim)', fontSize: 11 }}>ID: <code>{selectedPlayer.playerId}</code></div>
                <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 8 }}>
                  <div style={{ background: 'rgba(255,255,255,0.03)', padding: 8, borderRadius: 6 }}>
                    <div style={{ fontSize: 10, color: 'var(--text-dim)' }}>CHECK-IN</div>
                    <div style={{ fontWeight: 700 }}>{selectedPlayer.checkedIn ? '✅ Joined' : '❌ Missed'}</div>
                  </div>
                  <div style={{ background: 'rgba(255,255,255,0.03)', padding: 8, borderRadius: 6 }}>
                    <div style={{ fontSize: 10, color: 'var(--text-dim)' }}>PRIZE WON</div>
                    <div style={{ fontWeight: 700, color: 'var(--accent-emerald)' }}>₹{selectedPlayer.prizeRs}</div>
                  </div>
                </div>

                <div style={{ fontWeight: 700, marginTop: 4 }}>Round-by-Round Performance</div>
                {selectedPlayer.roundHistory.length === 0 ? (
                  <div style={{ color: 'var(--text-dim)', fontSize: 12 }}>
                    Did not play in any round.
                  </div>
                ) : (
                  selectedPlayer.roundHistory.map((rh) => (
                    <div key={rh.roundNumber} style={{ background: 'rgba(255,255,255,0.03)', border: '1px solid var(--border-subtle)', borderRadius: 8, padding: 10, display: 'flex', flexDirection: 'column', gap: 4 }}>
                      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                        <strong>Round {rh.roundNumber}</strong>
                        <span className={`badge ${rh.won ? 'badge-amber' : rh.qualified ? 'badge-emerald' : 'badge-dim'}`}>
                          {rh.won ? '🏆 Table Winner' : rh.qualified ? '✓ Qualified' : rh.status}
                        </span>
                      </div>
                      <div style={{ fontSize: 11, color: 'var(--text-dim)' }}>Table: <code>{rh.tableId}</code></div>
                      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginTop: 2 }}>
                        <span>Final Chips/Score: <strong>{rh.finalScore}</strong></span>
                        {rh.gameId && (
                          <Link to={`/games/${rh.gameId}`} className="btn" style={{ padding: '2px 7px', fontSize: 10.5 }}>
                            <ExternalLink size={11} /> View Game
                          </Link>
                        )}
                      </div>
                    </div>
                  ))
                )}
              </div>
            </div>
          )}
        </div>
      )}

      {/* TAB 3: FINAL RANKINGS */}
      {activeTab === 'RANKINGS' && (
        <div className="table-panel">
          <div className="table-panel-header">
            <span className="table-panel-title">🏆 Final Rankings &amp; Prize Payouts</span>
          </div>
          {(tournament.finalRankings || []).length === 0 ? (
            <div style={{ padding: 36, textAlign: 'center', color: 'var(--text-dim)' }}>
              Final rankings will appear once the tournament completes.
            </div>
          ) : (
            <table className="data-table">
              <thead>
                <tr>
                  <th>Rank</th>
                  <th>Player</th>
                  <th>Player ID</th>
                  <th>Prize Payout (₹)</th>
                </tr>
              </thead>
              <tbody>
                {(tournament.finalRankings || []).map((r: any) => (
                  <tr key={r.rank}>
                    <td style={{ fontWeight: 800, color: r.rank <= 3 ? 'var(--accent-gold)' : '#fff' }}>#{r.rank}</td>
                    <td style={{ fontWeight: 700 }}>{r.displayName || r.playerId}</td>
                    <td><code style={{ fontSize: 11 }}>{r.playerId}</code></td>
                    <td style={{ fontWeight: 700, color: 'var(--accent-emerald)' }}>₹{Number(r.prizeRs || 0).toLocaleString()}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
      )}
    </div>
  );
};
