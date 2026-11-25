import React, { useState, useEffect, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import { api } from '../api/client';
import {
  DoorOpen,
  RefreshCw,
  TrendingUp,
  Coins,
  Award,
  RotateCcw,
  Search,
  Eye,
  Ban,
  Users,
} from 'lucide-react';

interface RoomPlayer {
  playerId: string;
  displayName: string;
  seat: number;
  entryTxnId?: string;
  debitedAmount: number;
  refunded: boolean;
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

interface VariantBreakdown {
  rooms: number;
  collectionRs: number;
  rakeProfitRs: number;
  payoutRs: number;
}

interface Summary {
  totalRooms: number;
  waitingRooms: number;
  runningRooms: number;
  completedRooms: number;
  cancelledOrExpiredRooms: number;
  totalGrossCollectionRs: number;
  totalPlatformRakeRs: number;
  totalWinnerPayoutRs: number;
  totalRefundedRs: number;
  byVariant: Record<string, VariantBreakdown>;
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

export const PrivateRoomsPage: React.FC = () => {
  const navigate = useNavigate();
  const [rooms, setRooms] = useState<PrivateRoom[]>([]);
  const [summary, setSummary] = useState<Summary | null>(null);
  const [loading, setLoading] = useState(true);
  const [statusFilter, setStatusFilter] = useState<string>('ALL');
  const [variantFilter, setVariantFilter] = useState<string>('ALL');
  const [datePreset, setDatePreset] = useState<'ALL' | 'TODAY' | '7D' | 'CUSTOM'>('ALL');
  const [fromDate, setFromDate] = useState('');
  const [toDate, setToDate] = useState('');
  const [search, setSearch] = useState('');
  const [actionLoading, setActionLoading] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState<string | null>(null);

  const notify = (msg: string, isErr = false) => {
    if (isErr) { setError(msg); setSuccess(null); }
    else { setSuccess(msg); setError(null); }
    setTimeout(() => { setError(null); setSuccess(null); }, 4000);
  };

  const fetchRooms = useCallback(async () => {
    setLoading(true);
    try {
      const params: Record<string, string> = {};
      if (statusFilter !== 'ALL') params.status = statusFilter;
      if (variantFilter !== 'ALL') params.variant = variantFilter;
      if (search.trim()) params.search = search.trim();
      if (datePreset === 'TODAY') {
        const today = new Date().toISOString().slice(0, 10);
        params.from = today;
        params.to = today;
      } else if (datePreset === '7D') {
        const d = new Date(Date.now() - 6 * 86400 * 1000).toISOString().slice(0, 10);
        params.from = d;
        params.to = new Date().toISOString().slice(0, 10);
      } else if (datePreset === 'CUSTOM') {
        if (fromDate) params.from = fromDate;
        if (toDate) params.to = toDate;
      }
      const res = await api.get('/private-rooms', { params });
      setRooms(res.data.rooms || []);
      setSummary(res.data.summary || null);
    } catch (err: any) {
      notify(err?.response?.data?.message || 'Failed to load private rooms', true);
    } finally {
      setLoading(false);
    }
  }, [statusFilter, variantFilter, search, datePreset, fromDate, toDate]);

  useEffect(() => {
    fetchRooms();
    const id = setInterval(fetchRooms, 10000);
    return () => clearInterval(id);
  }, [fetchRooms]);

  const handleCancel = async (roomCode: string, status: string) => {
    const label = status === 'RUNNING' ? 'Force Abort Running Room' : 'Cancel Waiting Room';
    const reason = window.prompt(`${label} ${roomCode}? Enter reason (players will be refunded):`, status === 'RUNNING' ? 'Force aborted by administrator' : 'Cancelled by administrator');
    if (reason === null) return;
    setActionLoading(roomCode);
    try {
      await api.post(`/private-rooms/${roomCode}/cancel`, { reason });
      notify(`Room ${roomCode} cancelled. Players will be refunded automatically.`);
      await fetchRooms();
    } catch (err: any) {
      notify(err?.response?.data?.message || 'Failed to cancel room', true);
    } finally {
      setActionLoading(null);
    }
  };

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: '20px' }}>
      {/* Header */}
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', flexWrap: 'wrap', gap: '12px' }}>
        <div>
          <h1 style={{ margin: 0, fontSize: '20px', fontWeight: 700, display: 'flex', alignItems: 'center', gap: '10px' }}>
            <DoorOpen size={20} color="var(--accent-emerald)" />
            Private Rooms — Management &amp; Profit / Loss
          </h1>
          <p style={{ margin: '4px 0 0', fontSize: '12px', color: 'var(--text-dim)' }}>
            Monitor player-created rooms across all variants, track entry collections, platform rake profit, winner payouts, and refunds.
          </p>
        </div>
        <button className="btn" onClick={fetchRooms} disabled={loading}>
          <RefreshCw size={14} className={loading ? 'spin' : ''} /> Refresh
        </button>
      </div>

      {/* Notifications */}
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

      {/* Profit & Loss KPIs */}
      {summary && (
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(190px, 1fr))', gap: '14px' }}>
          <div className="kpi-card">
            <div className="kpi-label"><Coins size={14} color="var(--accent-gold)" /> Total Collection</div>
            <div className="kpi-value">{fmtRs(summary.totalGrossCollectionRs)}</div>
            <div className="kpi-sub">{summary.totalRooms} total rooms ({summary.runningRooms} live, {summary.waitingRooms} waiting)</div>
          </div>
          <div className="kpi-card">
            <div className="kpi-label"><TrendingUp size={14} color="var(--accent-emerald)" /> Platform Profit (Rake)</div>
            <div className="kpi-value" style={{ color: 'var(--accent-emerald)' }}>{fmtRs(summary.totalPlatformRakeRs)}</div>
            <div className="kpi-sub">Net commission earned across {summary.completedRooms} completed rooms</div>
          </div>
          <div className="kpi-card">
            <div className="kpi-label"><Award size={14} color="var(--accent-cyan)" /> Winner Payouts</div>
            <div className="kpi-value" style={{ color: 'var(--accent-cyan)' }}>{fmtRs(summary.totalWinnerPayoutRs)}</div>
            <div className="kpi-sub">Net prize credited to room winners</div>
          </div>
          <div className="kpi-card">
            <div className="kpi-label"><RotateCcw size={14} color="var(--accent-amber)" /> Refunded</div>
            <div className="kpi-value" style={{ color: 'var(--accent-amber)' }}>{fmtRs(summary.totalRefundedRs)}</div>
            <div className="kpi-sub">{summary.cancelledOrExpiredRooms} cancelled / expired rooms</div>
          </div>
        </div>
      )}

      {/* Variant-wise P&L Breakdown */}
      {summary && Object.keys(summary.byVariant).length > 0 && (
        <div className="table-panel" style={{ padding: '14px 16px' }}>
          <div style={{ fontSize: '12px', fontWeight: 700, color: 'var(--text-dim)', textTransform: 'uppercase', letterSpacing: '0.06em', marginBottom: '10px' }}>
            Variant-wise Profit &amp; Loss Breakdown
          </div>
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(180px, 1fr))', gap: '10px' }}>
            {Object.entries(summary.byVariant).map(([vKey, v]) => (
              <div
                key={vKey}
                style={{
                  padding: '10px 12px',
                  borderRadius: '8px',
                  background: 'rgba(255,255,255,0.03)',
                  border: '1px solid var(--border)',
                  display: 'flex',
                  flexDirection: 'column',
                  gap: '4px',
                }}
              >
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                  <strong style={{ fontSize: '13px' }}>{VARIANT_LABELS[vKey] || vKey}</strong>
                  <span style={{ fontSize: '11px', color: 'var(--text-dim)' }}>{v.rooms} rooms</span>
                </div>
                <div style={{ fontSize: '12px', color: 'var(--text-dim)' }}>
                  Collection: <strong style={{ color: 'var(--text)' }}>{fmtRs(v.collectionRs)}</strong>
                </div>
                <div style={{ fontSize: '12px', color: 'var(--text-dim)' }}>
                  Rake Profit: <strong style={{ color: 'var(--accent-emerald)' }}>{fmtRs(v.rakeProfitRs)}</strong>
                </div>
                <div style={{ fontSize: '12px', color: 'var(--text-dim)' }}>
                  Payouts: <strong style={{ color: 'var(--accent-cyan)' }}>{fmtRs(v.payoutRs)}</strong>
                </div>
              </div>
            ))}
          </div>
        </div>
      )}

      {/* Filters */}
      <div style={{ display: 'flex', gap: '10px', flexWrap: 'wrap', alignItems: 'center', justifyContent: 'space-between' }}>
        <div style={{ display: 'flex', gap: '6px', flexWrap: 'wrap' }}>
          {['ALL', 'WAITING', 'RUNNING', 'COMPLETED', 'CANCELLED', 'EXPIRED'].map((st) => (
            <button
              key={st}
              className={`btn ${statusFilter === st ? 'btn-primary' : ''}`}
              onClick={() => setStatusFilter(st)}
              style={{ fontSize: '12px', padding: '5px 11px' }}
            >
              {st === 'ALL' ? 'All' : STATUS_BADGE[st]?.label || st}
            </button>
          ))}
        </div>

        <div style={{ display: 'flex', gap: '8px', alignItems: 'center', flexWrap: 'wrap' }}>
          <div style={{ display: 'flex', gap: '4px' }}>
            {([
              { id: 'ALL', label: 'All Time' },
              { id: 'TODAY', label: 'Today' },
              { id: '7D', label: '7 Days' },
              { id: 'CUSTOM', label: 'Custom' },
            ] as const).map((d) => (
              <button
                key={d.id}
                className={`btn ${datePreset === d.id ? 'btn-primary' : ''}`}
                onClick={() => setDatePreset(d.id)}
                style={{ fontSize: '11px', padding: '5px 9px' }}
              >
                {d.label}
              </button>
            ))}
          </div>
          {datePreset === 'CUSTOM' && (
            <>
              <input type="date" className="admin-input" value={fromDate} onChange={(e) => setFromDate(e.target.value)} style={{ width: '132px', padding: '5px 8px', fontSize: '11px' }} />
              <input type="date" className="admin-input" value={toDate} onChange={(e) => setToDate(e.target.value)} style={{ width: '132px', padding: '5px 8px', fontSize: '11px' }} />
            </>
          )}
          <select
            className="admin-input"
            value={variantFilter}
            onChange={(e) => setVariantFilter(e.target.value)}
            style={{ width: '160px', padding: '6px 10px', fontSize: '12px' }}
          >
            <option value="ALL">All Variants</option>
            {Object.entries(VARIANT_LABELS).map(([k, lbl]) => (
              <option key={k} value={k}>{lbl}</option>
            ))}
          </select>

          <div style={{ position: 'relative' }}>
            <Search size={14} style={{ position: 'absolute', left: 10, top: '50%', transform: 'translateY(-50%)', color: 'var(--text-dim)' }} />
            <input
              type="text"
              className="admin-input"
              placeholder="Search code or host…"
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              style={{ paddingLeft: '30px', width: '200px', fontSize: '12px' }}
            />
          </div>
        </div>
      </div>

      {/* Rooms Table */}
      <div className="table-panel">
        <div className="table-scroll">
          <table className="data-table">
            <thead>
              <tr>
                <th>Room Code</th>
                <th>Variant</th>
                <th>Host</th>
                <th>Players</th>
                <th>Stake / Entry</th>
                <th>Collection</th>
                <th>Platform Profit</th>
                <th>Winner &amp; Payout</th>
                <th>Status</th>
                <th>Created</th>
                <th>Actions</th>
              </tr>
            </thead>
            <tbody>
              {rooms.length === 0 ? (
                <tr>
                  <td colSpan={11} style={{ textAlign: 'center', padding: '28px', color: 'var(--text-dim)' }}>
                    {loading ? 'Loading private rooms…' : 'No private rooms found for the selected filter.'}
                  </td>
                </tr>
              ) : (
                rooms.map((r) => {
                  const badge = STATUS_BADGE[r.status] || { label: r.status, cls: 'badge-dim' };
                  return (
                    <tr key={r.roomCode} onClick={() => navigate(`/private-rooms/${r.roomCode}`)} style={{ cursor: 'pointer' }}>
                      <td><strong style={{ fontFamily: 'monospace', fontSize: '13px', letterSpacing: '0.06em' }}>{r.roomCode}</strong></td>
                      <td>{VARIANT_LABELS[r.rulesetId] || r.rulesetId}</td>
                      <td>{r.hostDisplayName || r.hostPlayerId}</td>
                      <td><Users size={12} style={{ marginRight: 4 }} />{r.players?.length ?? 0} / {r.maxPlayers}</td>
                      <td>{r.stakeTier > 0 ? fmtRs(r.stakeTier) : <span className="badge badge-dim">FREE</span>}</td>
                      <td>{fmtRs(r.grossCollectionRs)}</td>
                      <td style={{ color: (r.platformRakeRs || 0) > 0 ? 'var(--accent-emerald)' : 'var(--text-dim)', fontWeight: 600 }}>
                        {fmtRs(r.platformRakeRs)}
                      </td>
                      <td>
                        {r.winnerDisplayName ? (
                          <span>
                            <strong>{r.winnerDisplayName}</strong> · <span style={{ color: 'var(--accent-cyan)' }}>{fmtRs(r.winnerPayoutRs)}</span>
                          </span>
                        ) : '—'}
                      </td>
                      <td><span className={`badge ${badge.cls}`}>{badge.label}</span></td>
                      <td style={{ fontSize: '12px', color: 'var(--text-dim)' }}>{fmtDate(r.createdAt)}</td>
                      <td onClick={(e) => e.stopPropagation()}>
                        <div style={{ display: 'flex', gap: '6px' }}>
                          <button className="btn" style={{ padding: '4px 8px', fontSize: '11px' }} onClick={() => navigate(`/private-rooms/${r.roomCode}`)} title="View Details">
                            <Eye size={13} />
                          </button>
                          {(r.status === 'WAITING' || r.status === 'RUNNING') && (
                            <button
                              className="btn btn-danger"
                              style={{ padding: '4px 8px', fontSize: '11px' }}
                              disabled={actionLoading === r.roomCode}
                              onClick={() => handleCancel(r.roomCode, r.status)}
                              title={r.status === 'RUNNING' ? 'Force Abort & Refund' : 'Force Cancel & Refund'}
                            >
                              <Ban size={13} />
                            </button>
                          )}
                        </div>
                      </td>
                    </tr>
                  );
                })
              )}
            </tbody>
          </table>
        </div>
      </div>
    </div>
  );
};
