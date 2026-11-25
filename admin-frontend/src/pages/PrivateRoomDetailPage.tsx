import React, { useState, useEffect, useCallback } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { api } from '../api/client';
import {
  ArrowLeft,
  RefreshCw,
  Coins,
  TrendingUp,
  Award,
  RotateCcw,
  Ban,
  Users,
  Crown,
} from 'lucide-react';

interface RoomPlayer {
  playerId: string;
  displayName: string;
  seat: number;
  entryTxnId?: string;
  debitedAmount: number;
  refunded: boolean;
  penaltyPoints?: number;
  lossAmount?: number;
  refundAmount?: number;
  winAmount?: number;
  netWalletDelta?: number;
  joinedAt: string;
}

interface PrivateRoom {
  roomCode: string;
  tableId: string;
  hostPlayerId: string;
  hostDisplayName: string;
  rulesetId: string;
  maxPlayers: number;
  stakeTier: number;
  status: 'WAITING' | 'RUNNING' | 'COMPLETED' | 'CANCELLED' | 'EXPIRED';
  cancelledReason?: string;
  players: RoomPlayer[];
  grossCollectionRs: number;
  platformRakeRs: number;
  rakeRate: number;
  winnerPayoutRs: number;
  refundedTotalRs: number;
  winnerPlayerId?: string;
  winnerDisplayName?: string;
  createdAt: string;
  expiresAt?: string;
  startedAt?: string;
  finishedAt?: string;
}

const STATUS_BADGE: Record<string, { label: string; cls: string }> = {
  WAITING:   { label: 'Waiting',   cls: 'badge-amber' },
  RUNNING:   { label: 'Live',      cls: 'badge-cyan' },
  COMPLETED: { label: 'Completed', cls: 'badge-emerald' },
  CANCELLED: { label: 'Cancelled', cls: 'badge-rose' },
  EXPIRED:   { label: 'Expired',   cls: 'badge-dim' },
};

const VARIANT_LABELS: Record<string, string> = {
  POINTS_13: 'Points Rummy',
  POOL_101: 'Pool 101',
  POOL_201: 'Pool 201',
  DEALS_2: '2 Deals',
  DEALS_3: '3 Deals',
  RUMMY_21: '21-Card Rummy',
};

function fmtRs(val: number | undefined) {
  return `₹${(val ?? 0).toLocaleString('en-IN', { maximumFractionDigits: 2 })}`;
}

function fmtDate(iso?: string) {
  if (!iso) return '—';
  return new Date(iso).toLocaleString('en-IN', { dateStyle: 'medium', timeStyle: 'short' });
}

export const PrivateRoomDetailPage: React.FC = () => {
  const { roomCode } = useParams<{ roomCode: string }>();
  const navigate = useNavigate();

  const [room, setRoom] = useState<PrivateRoom | null>(null);
  const [loading, setLoading] = useState(true);
  const [cancelling, setCancelling] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState<string | null>(null);

  const fetchRoom = useCallback(async () => {
    if (!roomCode) return;
    setLoading(true);
    try {
      const res = await api.get(`/private-rooms/${encodeURIComponent(roomCode)}`);
      setRoom(res.data.room || null);
      setError(null);
    } catch (err: any) {
      setError(err?.response?.data?.message || `Failed to load room ${roomCode}`);
    } finally {
      setLoading(false);
    }
  }, [roomCode]);

  useEffect(() => {
    fetchRoom();
    const id = setInterval(fetchRoom, 8000);
    return () => clearInterval(id);
  }, [fetchRoom]);

  const handleCancel = async () => {
    if (!room) return;
    const isRunning = room.status === 'RUNNING';
    const reason = window.prompt(
      `${isRunning ? 'Force Abort Live Room' : 'Cancel Waiting Room'} ${room.roomCode}? Enter reason:`,
      isRunning ? 'Force aborted by administrator' : 'Cancelled by administrator'
    );
    if (reason === null) return;
    setCancelling(true);
    try {
      await api.post(`/private-rooms/${room.roomCode}/cancel`, { reason });
      setSuccess(`Room ${room.roomCode} cancelled. Players will be refunded automatically.`);
      await fetchRoom();
    } catch (err: any) {
      setError(err?.response?.data?.message || 'Failed to cancel room');
    } finally {
      setCancelling(false);
    }
  };

  if (loading && !room) {
    return (
      <div style={{ padding: '32px', color: 'var(--text-dim)' }}>
        Loading room {roomCode}…
      </div>
    );
  }

  if (!room) {
    return (
      <div style={{ display: 'flex', flexDirection: 'column', gap: '14px' }}>
        <button className="btn" style={{ alignSelf: 'flex-start' }} onClick={() => navigate('/private-rooms')}>
          <ArrowLeft size={14} /> Back to Private Rooms
        </button>
        <div style={{ padding: '16px', borderRadius: 8, background: 'rgba(244,63,94,0.12)', border: '1px solid rgba(244,63,94,0.35)', color: '#fda4af' }}>
          {error || 'Private room not found.'}
        </div>
      </div>
    );
  }

  const badge = STATUS_BADGE[room.status] || { label: room.status, cls: 'badge-dim' };
  const initialSum = (room.players || []).reduce((acc, p) => acc + (p.debitedAmount || 0), 0);
  const rejoinSum = Math.max(0, (room.grossCollectionRs || 0) - initialSum);
  const totalReturnedFromSettlement = (room.players || []).reduce(
    (acc, p) => acc + (p.refunded ? (p.debitedAmount || 0) : ((p.refundAmount || 0) + (p.winAmount || 0))),
    0
  );
  const displayReturnedTotal = room.refundedTotalRs > 0 ? room.refundedTotalRs : totalReturnedFromSettlement;
  const rakePct = room.rakeRate > 0 ? ` (${Math.round(room.rakeRate * 100)}%)` : '';

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: '20px' }}>
      {/* Top Bar */}
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', flexWrap: 'wrap', gap: '12px' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: '12px', flexWrap: 'wrap' }}>
          <button className="btn" onClick={() => navigate('/private-rooms')}>
            <ArrowLeft size={14} /> Back
          </button>
          <div>
            <div style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
              <h1 style={{ margin: 0, fontSize: '20px', fontWeight: 700 }}>
                Room <span style={{ fontFamily: 'monospace', color: 'var(--accent-gold)', letterSpacing: '0.08em' }}>{room.roomCode}</span>
              </h1>
              <span className={`badge ${badge.cls}`}>{badge.label}</span>
            </div>
            <div style={{ fontSize: '12px', color: 'var(--text-dim)', marginTop: '3px' }}>
              {VARIANT_LABELS[room.rulesetId] || room.rulesetId} · Table: <span style={{ fontFamily: 'monospace' }}>{room.tableId}</span> · Host: <strong>{room.hostDisplayName}</strong>
            </div>
          </div>
        </div>

        <div style={{ display: 'flex', gap: '8px' }}>
          <button className="btn" onClick={fetchRoom} disabled={loading}>
            <RefreshCw size={14} className={loading ? 'spin' : ''} /> Refresh
          </button>
          {(room.status === 'WAITING' || room.status === 'RUNNING') && (
            <button className="btn btn-danger" disabled={cancelling} onClick={handleCancel}>
              <Ban size={14} /> {room.status === 'RUNNING' ? 'Force Abort & Refund' : 'Force Cancel & Refund'}
            </button>
          )}
        </div>
      </div>

      {error && (
        <div style={{ padding: '10px 14px', borderRadius: 8, background: 'rgba(244,63,94,0.12)', border: '1px solid rgba(244,63,94,0.35)', color: '#fda4af', fontSize: 13 }}>
          {error}
        </div>
      )}
      {success && (
        <div style={{ padding: '10px 14px', borderRadius: 8, background: 'rgba(16,185,129,0.12)', border: '1px solid rgba(16,185,129,0.35)', color: '#6ee7b7', fontSize: 13 }}>
          {success}
        </div>
      )}

      {/* Financial KPIs */}
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(195px, 1fr))', gap: '14px' }}>
        <div className="kpi-card">
          <div className="kpi-label"><Coins size={14} color="var(--accent-gold)" /> Gross Pot / Collection</div>
          <div className="kpi-value">{fmtRs(room.grossCollectionRs)}</div>
          <div className="kpi-sub">Stake/Buy-in: {room.stakeTier > 0 ? fmtRs(room.stakeTier) : 'FREE'} per player</div>
        </div>

        <div className="kpi-card">
          <div className="kpi-label"><TrendingUp size={14} color="var(--accent-emerald)" /> Platform Profit (Rake{rakePct})</div>
          <div className="kpi-value" style={{ color: 'var(--accent-emerald)' }}>{fmtRs(room.platformRakeRs)}</div>
          <div className="kpi-sub">Commission earned by platform</div>
        </div>

        <div className="kpi-card">
          <div className="kpi-label"><Award size={14} color="var(--accent-cyan)" /> Winner Net Prize</div>
          <div className="kpi-value" style={{ color: 'var(--accent-cyan)' }}>{fmtRs(room.winnerPayoutRs)}</div>
          <div className="kpi-sub">{room.winnerDisplayName ? `Winner: ${room.winnerDisplayName}` : 'No winner yet'}</div>
        </div>

        <div className="kpi-card">
          <div className="kpi-label"><RotateCcw size={14} color="var(--accent-amber)" /> Total Returned / Refunded</div>
          <div className="kpi-value" style={{ color: 'var(--accent-amber)' }}>{fmtRs(displayReturnedTotal)}</div>
          <div className="kpi-sub">Returned to player wallets</div>
        </div>
      </div>

      {/* Metadata & Rejoin Breakdown */}
      <div className="table-panel" style={{ padding: '14px 16px', display: 'flex', flexDirection: 'column', gap: '8px' }}>
        <div style={{ display: 'flex', flexWrap: 'wrap', gap: '20px', fontSize: '12px', color: 'var(--text-dim)' }}>
          <div><strong>Created:</strong> {fmtDate(room.createdAt)}</div>
          <div><strong>Started:</strong> {fmtDate(room.startedAt)}</div>
          <div><strong>Finished:</strong> {fmtDate(room.finishedAt)}</div>
          <div><strong>Players:</strong> {room.players.length} / {room.maxPlayers}</div>
          {room.cancelledReason && <div style={{ color: '#fda4af' }}><strong>Cancel Reason:</strong> {room.cancelledReason}</div>}
        </div>
        {rejoinSum > 0 && room.status === 'COMPLETED' && (
          <div style={{ padding: '8px 12px', borderRadius: 8, background: 'rgba(6,182,212,0.1)', border: '1px solid rgba(6,182,212,0.3)', fontSize: '12px', color: '#a5f3fc' }}>
            <strong>Collection Breakdown:</strong> Initial Entry ({fmtRs(initialSum)}) + Rejoin Fees ({fmtRs(rejoinSum)}) = {fmtRs(room.grossCollectionRs)}
          </div>
        )}
      </div>

      {/* Per-Player Settlement & Return Table */}
      <div className="table-panel">
        <div className="table-panel-header">
          <span className="table-panel-title" style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
            <Users size={15} color="var(--accent-gold)" />
            Player-wise Debit, Return &amp; Settlement Breakdown ({room.players.length} / {room.maxPlayers})
          </span>
        </div>
        <div className="table-scroll">
          <table className="data-table">
            <thead>
              <tr>
                <th>Seat</th>
                <th>Player</th>
                <th>Debited (Entry / Buy-in)</th>
                <th>Penalty Pts</th>
                <th>Lost (₹)</th>
                <th>Returned to Wallet (₹)</th>
                <th>Net P&amp;L</th>
                <th>Status</th>
              </tr>
            </thead>
            <tbody>
              {room.players.map((p) => {
                const isWinner = room.winnerPlayerId === p.playerId;
                const isHost = room.hostPlayerId === p.playerId;
                const hasSettlement =
                  (p.refundAmount ?? 0) > 0 ||
                  (p.winAmount ?? 0) > 0 ||
                  (p.lossAmount ?? 0) > 0 ||
                  (p.netWalletDelta ?? 0) !== 0;

                const returnedToWallet = p.refunded
                  ? p.debitedAmount
                  : hasSettlement
                  ? (p.refundAmount ?? 0) + (p.winAmount ?? 0)
                  : null;

                const netDelta = p.refunded ? 0 : (p.netWalletDelta ?? 0);

                return (
                  <tr key={p.playerId}>
                    <td>{p.seat + 1}.</td>
                    <td>
                      <div style={{ display: 'flex', alignItems: 'center', gap: '6px' }}>
                        <strong>{p.displayName}</strong>
                        {isHost && <span className="badge badge-dim">HOST</span>}
                        {isWinner && (
                          <span className="badge badge-emerald" style={{ display: 'inline-flex', alignItems: 'center', gap: '3px' }}>
                            <Crown size={11} /> WINNER
                          </span>
                        )}
                      </div>
                      <div style={{ fontSize: '11px', color: 'var(--text-dim)' }}>{p.playerId}</div>
                    </td>
                    <td>{fmtRs(p.debitedAmount)}</td>
                    <td>{hasSettlement ? `${p.penaltyPoints ?? 0} pts` : '—'}</td>
                    <td style={{ color: (p.lossAmount ?? 0) > 0 ? '#fda4af' : 'var(--text-dim)' }}>
                      {hasSettlement ? fmtRs(p.lossAmount) : '—'}
                    </td>
                    <td style={{ color: 'var(--accent-amber)', fontWeight: 700 }}>
                      {returnedToWallet !== null ? fmtRs(returnedToWallet) : '—'}
                    </td>
                    <td
                      style={{
                        fontWeight: 700,
                        color: netDelta > 0 ? 'var(--accent-emerald)' : netDelta < 0 ? '#fda4af' : 'var(--text-dim)',
                      }}
                    >
                      {p.refunded
                        ? '₹0 (Refunded)'
                        : hasSettlement
                        ? `${netDelta >= 0 ? '+' : ''}${fmtRs(netDelta)}`
                        : '—'}
                    </td>
                    <td>
                      {p.refunded ? (
                        <span className="badge badge-amber">Refunded</span>
                      ) : isWinner ? (
                        <span className="badge badge-emerald">Winner</span>
                      ) : room.status === 'COMPLETED' ? (
                        <span className="badge badge-dim">Settled</span>
                      ) : (
                        <span className="badge badge-cyan">{room.status}</span>
                      )}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      </div>
    </div>
  );
};
