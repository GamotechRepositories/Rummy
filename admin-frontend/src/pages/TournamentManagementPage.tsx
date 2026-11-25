import React, { useState, useEffect, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import { api } from '../api/client';
import {
  Trophy,
  Plus,
  RefreshCw,
  Users,
  Clock,
  AlertTriangle,
  CheckCircle,
  XCircle,
  Edit3,
  Eye,
  Send,
  Ban,
} from 'lucide-react';

// ── Types ──────────────────────────────────────────────────────────────────
interface Tournament {
  tournamentId: string;
  title: string;
  variant: string;
  entryFeeRs: number;
  fixedPrizeRs: number;
  checkInWindowMinutes?: number;
  grossPoolRs: number;
  platformProfitRs: number;
  minPlayers: number;
  maxPlayers: number;
  registered?: number; // from registrations.length
  registrations?: { playerId: string }[];
  startsAt: string;
  registrationStartsAt: string;
  registrationEndsAt: string;
  status: string;
  operatorScope: string;
  cancelledReason?: string;
  finalRankings?: { rank: number; playerId: string; displayName: string; prizeRs: number }[];
}

interface CreateForm {
  title: string;
  minPlayers: number;
  maxPlayers: number;
  entryFeeRs: number;
  fixedPrizeRs: number;
  checkInWindowMinutes: number;
  registrationStartsAt: string;
  registrationEndsAt: string;
  startsAt: string;
  operatorScope: string;
}

// ── Helpers ────────────────────────────────────────────────────────────────
const STATUS_BADGE: Record<string, { label: string; cls: string }> = {
  DRAFT:        { label: 'Draft',        cls: 'badge-dim' },
  REGISTERING:  { label: 'Registering',  cls: 'badge-emerald' },
  STARTING:     { label: 'Starting…',    cls: 'badge-amber' },
  RUNNING:      { label: 'Live',         cls: 'badge-cyan' },
  COMPLETED:    { label: 'Completed',    cls: 'badge-violet' },
  CANCELLED:    { label: 'Cancelled',    cls: 'badge-rose' },
};

function fmtDate(iso: string) {
  return new Date(iso).toLocaleString('en-IN', { dateStyle: 'medium', timeStyle: 'short' });
}

function isoLocal(date = new Date()) {
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

// default start 2 hours from now
function defaultStartISO() {
  const d = new Date(Date.now() + 2 * 3600 * 1000);
  return isoLocal(d);
}
function defaultRegOpenISO() { return isoLocal(); }
function defaultRegCloseISO() {
  const d = new Date(Date.now() + 1.5 * 3600 * 1000);
  return isoLocal(d);
}

const EMPTY_FORM: CreateForm = {
  title: '',
  minPlayers: 36,
  maxPlayers: 120,
  entryFeeRs: 100,
  fixedPrizeRs: 5000,
  checkInWindowMinutes: 5,
  registrationStartsAt: defaultRegOpenISO(),
  registrationEndsAt: defaultRegCloseISO(),
  startsAt: defaultStartISO(),
  operatorScope: 'ALL',
};

// ── Component ──────────────────────────────────────────────────────────────
export const TournamentManagementPage: React.FC = () => {
  const navigate = useNavigate();
  const [tournaments, setTournaments] = useState<Tournament[]>([]);
  const [loading, setLoading] = useState(true);
  const [statusFilter, setStatusFilter] = useState<string>('ALL');
  const [showCreate, setShowCreate] = useState(false);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [form, setForm] = useState<CreateForm>(EMPTY_FORM);
  const [saving, setSaving] = useState(false);
  const [actionLoading, setActionLoading] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState<string | null>(null);

  const selectedTournament = tournaments.find((t) => t.tournamentId === selectedId) ?? null;

  const notify = (msg: string, isErr = false) => {
    if (isErr) { setError(msg); setSuccess(null); }
    else { setSuccess(msg); setError(null); }
    setTimeout(() => { setError(null); setSuccess(null); }, 4000);
  };

  const fetchTournaments = useCallback(async () => {
    setLoading(true);
    try {
      const res = await api.get('/tournaments');
      const list: Tournament[] = (res.data.tournaments || []).map((t: Tournament) => ({
        ...t,
        registered: t.registrations?.length ?? 0,
      }));
      setTournaments(list);
    } catch {
      setTournaments([]);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { fetchTournaments(); }, [fetchTournaments]);

  // ── Create / Edit ──────────────────────────────────────────────────────
  const openEditForm = (t: Tournament) => {
    setEditingId(t.tournamentId);
    setSelectedId(null);
    setShowCreate(true);
    setForm({
      title: t.title,
      minPlayers: t.minPlayers,
      maxPlayers: t.maxPlayers,
      entryFeeRs: t.entryFeeRs,
      fixedPrizeRs: t.fixedPrizeRs ?? 0,
      checkInWindowMinutes: t.checkInWindowMinutes ?? 5,
      registrationStartsAt: t.registrationStartsAt ? isoLocal(new Date(t.registrationStartsAt)) : defaultRegOpenISO(),
      registrationEndsAt: t.registrationEndsAt ? isoLocal(new Date(t.registrationEndsAt)) : defaultRegCloseISO(),
      startsAt: t.startsAt ? isoLocal(new Date(t.startsAt)) : defaultStartISO(),
      operatorScope: t.operatorScope || 'ALL',
    });
  };

  const handleCreate = async (e: React.FormEvent) => {
    e.preventDefault();
    if (form.minPlayers % 6 !== 0 || form.maxPlayers % 6 !== 0) {
      notify('Min Players and Max Players must be multiples of 6.', true);
      return;
    }
    if (form.minPlayers < 6 || form.maxPlayers < form.minPlayers) {
      notify('Min Players must be ≥ 6 and Max Players must be ≥ Min Players.', true);
      return;
    }
    setSaving(true);
    try {
      if (editingId) {
        const res = await api.patch(`/tournaments/${editingId}`, form);
        notify(`✅ Tournament "${res.data.tournament.title}" updated!`);
      } else {
        const res = await api.post('/tournaments', form);
        notify(`✅ Tournament "${res.data.tournament.title}" created (ID: ${res.data.tournament.tournamentId})`);
      }
      setShowCreate(false);
      setEditingId(null);
      setForm(EMPTY_FORM);
      await fetchTournaments();
    } catch (err: any) {
      notify(err.response?.data?.message || err.message, true);
    } finally {
      setSaving(false);
    }
  };

  // ── Publish ─────────────────────────────────────────────────────────────
  const handlePublish = async (id: string) => {
    if (!window.confirm('Publish this tournament? Players will see it and can register.')) return;
    setActionLoading(id + '_publish');
    try {
      await api.post(`/tournaments/${id}/publish`);
      notify('🟢 Tournament published! Registration is now open.');
      await fetchTournaments();
    } catch (err: any) {
      notify(err.response?.data?.message || err.message, true);
    } finally {
      setActionLoading(null);
    }
  };

  // ── Cancel ──────────────────────────────────────────────────────────────
  const handleCancel = async (id: string) => {
    const reason = window.prompt('Reason for cancellation (players will see this):');
    if (reason === null) return; // user pressed Cancel on prompt
    setActionLoading(id + '_cancel');
    try {
      const res = await api.post(`/tournaments/${id}/cancel`, { reason });
      notify(`🔴 Tournament cancelled. ${res.data.refundCount} players will be refunded.`);
      await fetchTournaments();
    } catch (err: any) {
      notify(err.response?.data?.message || err.message, true);
    } finally {
      setActionLoading(null);
    }
  };

  // ── Render ─────────────────────────────────────────────────────────────
  const filteredTournaments = statusFilter === 'ALL'
    ? tournaments
    : tournaments.filter((t) => t.status === statusFilter);

  const activeCount = tournaments.filter((t) => ['REGISTERING', 'STARTING', 'RUNNING'].includes(t.status)).length;
  const totalCollected = tournaments
    .filter((t) => t.status !== 'CANCELLED')
    .reduce((sum, t) => sum + ((t.registrations?.length ?? t.registered ?? 0) * t.entryFeeRs), 0);
  const totalNetProfit = tournaments
    .filter((t) => t.status !== 'CANCELLED')
    .reduce((sum, t) => {
      const col = (t.registrations?.length ?? t.registered ?? 0) * t.entryFeeRs;
      return sum + (col - (t.fixedPrizeRs ?? 0));
    }, 0);

  return (
    <div className="admin-page-root">
      {/* ── Page Header ── */}
      <div className="admin-page-header">
        <div>
          <h1 className="admin-page-title">
            <Trophy size={22} style={{ color: 'var(--accent-gold)', marginRight: 8 }} />
            Tournament Management
          </h1>
          <p className="admin-page-subtitle">
            Create, manage and monitor Deals Rummy (3 Deals · 6 Players) tournaments.
          </p>
        </div>
        <div style={{ display: 'flex', gap: 8 }}>
          <button className="btn" onClick={fetchTournaments} title="Refresh">
            <RefreshCw size={14} />
            Refresh
          </button>
          <button className="btn btn-primary" onClick={() => { setEditingId(null); setForm(EMPTY_FORM); setShowCreate(true); setSelectedId(null); }}>
            <Plus size={14} />
            Create Tournament
          </button>
        </div>
      </div>

      {/* ── KPI Summary Row ── */}
      <div className="grid-kpis" style={{ marginBottom: 4 }}>
        <div className="kpi-card">
          <div className="kpi-card-header">
            <span className="kpi-card-title">Total Tournaments</span>
            <Trophy size={15} color="var(--accent-gold)" />
          </div>
          <div className="kpi-card-value">{tournaments.length}</div>
          <div className="kpi-card-delta" style={{ color: 'var(--text-dim)' }}>
            All scheduled &amp; past events
          </div>
        </div>

        <div className="kpi-card">
          <div className="kpi-card-header">
            <span className="kpi-card-title">Active / Open</span>
            <Clock size={15} color="var(--accent-emerald)" />
          </div>
          <div className="kpi-card-value" style={{ color: 'var(--accent-emerald)' }}>{activeCount}</div>
          <div className="kpi-card-delta" style={{ color: 'var(--text-dim)' }}>
            Registering, Check-In or Live
          </div>
        </div>

        <div className="kpi-card">
          <div className="kpi-card-header">
            <span className="kpi-card-title">Entry Collected</span>
            <Users size={15} color="var(--accent-cyan)" />
          </div>
          <div className="kpi-card-value">₹{totalCollected.toLocaleString()}</div>
          <div className="kpi-card-delta" style={{ color: 'var(--text-dim)' }}>
            Excluding cancelled refunds
          </div>
        </div>

        <div className="kpi-card">
          <div className="kpi-card-header">
            <span className="kpi-card-title">Net Profit / Overlay</span>
            <AlertTriangle size={15} color={totalNetProfit >= 0 ? 'var(--accent-emerald)' : '#f43f5e'} />
          </div>
          <div className="kpi-card-value" style={{ color: totalNetProfit >= 0 ? 'var(--accent-emerald)' : '#f43f5e' }}>
            {totalNetProfit >= 0 ? `+₹${totalNetProfit.toLocaleString()}` : `-₹${Math.abs(totalNetProfit).toLocaleString()}`}
          </div>
          <div className="kpi-card-delta" style={{ color: 'var(--text-dim)' }}>
            Collected minus Fixed Prizes
          </div>
        </div>
      </div>

      {/* ── Toast ── */}
      {error && (
        <div className="admin-alert admin-alert-error">
          <AlertTriangle size={14} /> {error}
        </div>
      )}
      {success && (
        <div className="admin-alert admin-alert-success">
          <CheckCircle size={14} /> {success}
        </div>
      )}

      <div style={{ display: 'grid', gridTemplateColumns: showCreate || selectedId ? '1fr 400px' : '1fr', gap: 18, alignItems: 'start' }}>
        {/* ── Tournament List ── */}
        <div className="table-panel">
          <div className="table-panel-header" style={{ flexWrap: 'wrap', gap: 10 }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
              <span className="table-panel-title">All Tournaments</span>
              <span className="badge badge-dim">{filteredTournaments.length}</span>
            </div>
            <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
              {['ALL', 'REGISTERING', 'STARTING', 'RUNNING', 'COMPLETED', 'CANCELLED'].map((st) => (
                <button
                  key={st}
                  type="button"
                  onClick={() => setStatusFilter(st)}
                  className={`btn ${statusFilter === st ? 'btn-primary' : ''}`}
                  style={{ padding: '3px 9px', fontSize: 11 }}
                >
                  {st === 'ALL' ? 'All' : STATUS_BADGE[st]?.label ?? st}
                </button>
              ))}
            </div>
          </div>

          {loading ? (
            <div style={{ padding: 36, textAlign: 'center', color: 'var(--text-dim)' }}>
              Loading tournaments…
            </div>
          ) : filteredTournaments.length === 0 ? (
            <div style={{ padding: 36, textAlign: 'center', color: 'var(--text-dim)' }}>
              No tournaments match this filter. Click <strong>"Create Tournament"</strong> to get started.
            </div>
          ) : (
            <div style={{ overflowX: 'auto' }}>
              <table className="data-table">
                <thead>
                  <tr>
                    <th>ID</th>
                    <th>Title</th>
                    <th>Reg. Opens</th>
                    <th>Reg. Closes</th>
                    <th>Start Time</th>
                    <th>Min / Max</th>
                    <th>Registered</th>
                    <th>Entry</th>
                    <th>Fixed Prize</th>
                    <th>Profit / Loss</th>
                    <th>Status</th>
                    <th>Actions</th>
                  </tr>
                </thead>
                <tbody>
                  {filteredTournaments.map((t) => {
                    const badge = STATUS_BADGE[t.status] ?? { label: t.status, cls: 'badge-dim' };
                    const regCount = t.registrations?.length ?? t.registered ?? 0;
                    const collected = regCount * t.entryFeeRs;
                    const profit = collected - (t.fixedPrizeRs ?? 0);

                    return (
                      <tr
                        key={t.tournamentId}
                        onClick={() => navigate(`/tournaments/${t.tournamentId}`)}
                        style={{ cursor: 'pointer', background: selectedId === t.tournamentId ? 'rgba(212,175,55,0.07)' : '' }}
                      >
                        <td>
                          <code style={{ fontSize: 11 }}>{t.tournamentId}</code>
                        </td>
                        <td style={{ fontWeight: 600 }}>{t.title}</td>
                        <td style={{ fontSize: 12, color: 'var(--text-dim)' }}>
                          {t.registrationStartsAt ? fmtDate(t.registrationStartsAt) : '—'}
                        </td>
                        <td style={{ fontSize: 12, color: 'var(--text-dim)' }}>
                          {t.registrationEndsAt ? fmtDate(t.registrationEndsAt) : '—'}
                        </td>
                        <td style={{ fontSize: 12, color: 'var(--text-dim)' }}>
                          {fmtDate(t.startsAt)}
                        </td>
                        <td>{t.minPlayers} / {t.maxPlayers}</td>
                        <td>
                          <span style={{ color: regCount >= t.minPlayers ? 'var(--accent-emerald)' : 'var(--accent-amber)', fontWeight: 600 }}>
                            {regCount}
                          </span>
                          <span style={{ color: 'var(--text-dim)', fontSize: 11 }}> / {t.maxPlayers}</span>
                        </td>
                        <td>₹{t.entryFeeRs}</td>
                        <td style={{ fontWeight: 700, color: 'var(--accent-gold)' }}>
                          ₹{(t.fixedPrizeRs ?? 0).toLocaleString()}
                        </td>
                        <td style={{ fontWeight: 700, color: profit >= 0 ? 'var(--accent-emerald)' : '#f43f5e' }}>
                          {profit >= 0 ? `+₹${profit.toLocaleString()}` : `-₹${Math.abs(profit).toLocaleString()}`}
                        </td>
                        <td><span className={`badge ${badge.cls}`}>{badge.label}</span></td>
                        <td>
                          <div style={{ display: 'flex', gap: 4 }}>
                            <button
                              className="btn"
                              style={{ padding: '3px 7px' }}
                              title="View Full Tournament Details"
                              onClick={(e) => { e.stopPropagation(); navigate(`/tournaments/${t.tournamentId}`); }}
                            >
                              <Eye size={12} />
                            </button>
                            {['DRAFT', 'REGISTERING'].includes(t.status) && (
                              <button
                                className="btn"
                                style={{ padding: '3px 7px' }}
                                title="Edit Tournament"
                                onClick={(e) => { e.stopPropagation(); openEditForm(t); }}
                              >
                                <Edit3 size={12} />
                              </button>
                            )}
                            {t.status === 'DRAFT' && (
                              <button
                                className="btn btn-primary"
                                style={{ padding: '3px 7px', fontSize: 11 }}
                                title="Publish (open registration)"
                                disabled={actionLoading === t.tournamentId + '_publish'}
                                onClick={(e) => { e.stopPropagation(); handlePublish(t.tournamentId); }}
                              >
                                <Send size={12} />
                                Publish
                              </button>
                            )}
                            {!['COMPLETED', 'CANCELLED'].includes(t.status) && (
                              <button
                                className="btn btn-danger"
                                style={{ padding: '3px 7px', fontSize: 11 }}
                                title="Cancel Tournament"
                                disabled={actionLoading === t.tournamentId + '_cancel'}
                                onClick={(e) => { e.stopPropagation(); handleCancel(t.tournamentId); }}
                              >
                                <Ban size={12} />
                              </button>
                            )}
                          </div>
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          )}
        </div>

        {/* ── Detail / Create Panel ── */}
        {(showCreate || selectedId) && (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>

            {/* Create / Edit Form */}
            {showCreate && (
              <div className="admin-card">
                <div className="admin-card-header">
                  <span>
                    {editingId ? <Edit3 size={14} style={{ marginRight: 6 }} /> : <Plus size={14} style={{ marginRight: 6 }} />}
                    {editingId ? `Edit Tournament (${editingId})` : 'New Tournament'}
                  </span>
                  <button className="btn" style={{ padding: '2px 8px' }} onClick={() => { setShowCreate(false); setEditingId(null); }}>✕</button>
                </div>
                <form onSubmit={handleCreate} style={{ display: 'flex', flexDirection: 'column', gap: 12, padding: '4px 0' }}>
                  <label className="admin-field-label">
                    Tournament Title *
                    <input
                      className="admin-input"
                      required
                      placeholder="e.g. Evening Deals Mania"
                      value={form.title}
                      onChange={(e) => setForm((f) => ({ ...f, title: e.target.value }))}
                    />
                  </label>

                  <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 10 }}>
                    <label className="admin-field-label">
                      Min Players * <span style={{ fontSize: 10, color: 'var(--text-dim)' }}>(multiple of 6)</span>
                      <input
                        className="admin-input"
                        type="number"
                        required
                        min={6}
                        step={6}
                        value={form.minPlayers}
                        onChange={(e) => setForm((f) => ({ ...f, minPlayers: Number(e.target.value) }))}
                      />
                    </label>
                    <label className="admin-field-label">
                      Max Players * <span style={{ fontSize: 10, color: 'var(--text-dim)' }}>(multiple of 6)</span>
                      <input
                        className="admin-input"
                        type="number"
                        required
                        min={6}
                        step={6}
                        value={form.maxPlayers}
                        onChange={(e) => setForm((f) => ({ ...f, maxPlayers: Number(e.target.value) }))}
                      />
                    </label>
                  </div>

                  <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 10 }}>
                    <label className="admin-field-label">
                      Entry Fee (₹) *
                      <input
                        className="admin-input"
                        type="number"
                        min={0}
                        required
                        value={form.entryFeeRs}
                        onChange={(e) => setForm((f) => ({ ...f, entryFeeRs: Number(e.target.value) }))}
                      />
                    </label>
                    <label className="admin-field-label">
                      Fixed Prize Pool (₹) *
                      <input
                        className="admin-input"
                        type="number"
                        min={0}
                        required
                        value={form.fixedPrizeRs}
                        onChange={(e) => setForm((f) => ({ ...f, fixedPrizeRs: Number(e.target.value) }))}
                      />
                    </label>
                  </div>

                  <label className="admin-field-label">
                    Check-In Window (Minutes) * <span style={{ fontSize: 10, color: 'var(--text-dim)' }}>(waiting lobby duration after Start Time)</span>
                    <input
                      className="admin-input"
                      type="number"
                      min={1}
                      max={30}
                      required
                      value={form.checkInWindowMinutes}
                      onChange={(e) => setForm((f) => ({ ...f, checkInWindowMinutes: Number(e.target.value) }))}
                    />
                  </label>

                  <label className="admin-field-label">
                    Registration Opens *
                    <input
                      className="admin-input"
                      type="datetime-local"
                      required
                      value={form.registrationStartsAt}
                      onChange={(e) => setForm((f) => ({ ...f, registrationStartsAt: e.target.value }))}
                    />
                  </label>
                  <label className="admin-field-label">
                    Registration Closes *
                    <input
                      className="admin-input"
                      type="datetime-local"
                      required
                      value={form.registrationEndsAt}
                      onChange={(e) => setForm((f) => ({ ...f, registrationEndsAt: e.target.value }))}
                    />
                  </label>
                  <label className="admin-field-label">
                    Tournament Start Time *
                    <input
                      className="admin-input"
                      type="datetime-local"
                      required
                      value={form.startsAt}
                      onChange={(e) => setForm((f) => ({ ...f, startsAt: e.target.value }))}
                    />
                  </label>

                  <label className="admin-field-label">
                    Operator Scope
                    <input
                      className="admin-input"
                      placeholder="ALL (or specific operatorId)"
                      value={form.operatorScope}
                      onChange={(e) => setForm((f) => ({ ...f, operatorScope: e.target.value }))}
                    />
                  </label>

                  {/* Live Collection vs Fixed Prize Preview */}
                  {(form.entryFeeRs > 0 || form.fixedPrizeRs > 0) && (() => {
                    const minGross = form.minPlayers * form.entryFeeRs;
                    const maxGross = form.maxPlayers * form.entryFeeRs;
                    const minProfit = minGross - form.fixedPrizeRs;
                    const maxProfit = maxGross - form.fixedPrizeRs;
                    return (
                      <div style={{ background: 'rgba(212,175,55,0.1)', border: '1px solid rgba(212,175,55,0.3)', borderRadius: 8, padding: '10px 14px', fontSize: 12 }}>
                        <strong style={{ color: 'var(--accent-gold)' }}>Collection &amp; Profit Preview</strong>
                        <div style={{ marginTop: 6, display: 'flex', flexDirection: 'column', gap: 4 }}>
                          <span>Min Collection ({form.minPlayers} players): <strong>₹{minGross.toLocaleString()}</strong></span>
                          <span>Max Collection ({form.maxPlayers} players): <strong>₹{maxGross.toLocaleString()}</strong></span>
                          <span>Fixed Prize to Winners: <strong style={{ color: 'var(--accent-gold)' }}>₹{form.fixedPrizeRs.toLocaleString()}</strong></span>
                          <span style={{ color: minProfit >= 0 ? 'var(--accent-emerald)' : '#f43f5e', fontWeight: 700 }}>
                            {minProfit >= 0
                              ? `Min Profit: +₹${minProfit.toLocaleString()}`
                              : `⚠ Loss at Min Players: -₹${Math.abs(minProfit).toLocaleString()} (can cancel before start)`}
                          </span>
                          <span style={{ color: maxProfit >= 0 ? 'var(--accent-emerald)' : '#f43f5e', fontWeight: 700 }}>
                            {maxProfit >= 0
                              ? `Max Profit: +₹${maxProfit.toLocaleString()}`
                              : `⚠ Loss at Max Players: -₹${Math.abs(maxProfit).toLocaleString()}`}
                          </span>
                        </div>
                      </div>
                    );
                  })()}

                  <div style={{ display: 'flex', gap: 8, marginTop: 4 }}>
                    <button type="submit" className="btn btn-primary" disabled={saving} style={{ flex: 1 }}>
                      {saving ? (editingId ? 'Saving…' : 'Creating…') : (editingId ? 'Save Changes' : 'Create Tournament')}
                    </button>
                    <button type="button" className="btn" onClick={() => { setShowCreate(false); setEditingId(null); }}>Cancel</button>
                  </div>
                </form>
              </div>
            )}

            {/* Detail Panel */}
            {selectedTournament && (
              <div className="admin-card">
                <div className="admin-card-header">
                  <span><Eye size={14} style={{ marginRight: 6 }} />{selectedTournament.title}</span>
                  <button className="btn" style={{ padding: '2px 8px' }} onClick={() => setSelectedId(null)}>✕</button>
                </div>

                <div style={{ display: 'flex', flexDirection: 'column', gap: 10, fontSize: 13 }}>
                  <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 8 }}>
                    <StatBox label="Status" value={<span className={`badge ${STATUS_BADGE[selectedTournament.status]?.cls}`}>{STATUS_BADGE[selectedTournament.status]?.label}</span>} />
                    <StatBox label="Variant" value="Deals Rummy (3 Deals · 6P)" />
                    <StatBox label="Entry Fee" value={`₹${selectedTournament.entryFeeRs}`} />
                    <StatBox label="Fixed Prize" value={<span style={{ color: 'var(--accent-gold)' }}>₹{(selectedTournament.fixedPrizeRs ?? 0).toLocaleString()}</span>} />
                    <StatBox label="Min Players" value={selectedTournament.minPlayers} />
                    <StatBox label="Max Players" value={selectedTournament.maxPlayers} />
                    <StatBox
                      label="Registered"
                      value={
                        <span style={{ color: (selectedTournament.registrations?.length ?? 0) >= selectedTournament.minPlayers ? 'var(--accent-emerald)' : 'var(--accent-amber)', fontWeight: 700 }}>
                          {selectedTournament.registrations?.length ?? 0} / {selectedTournament.maxPlayers}
                        </span>
                      }
                    />
                    <StatBox
                      label="Min Players Met?"
                      value={
                        (selectedTournament.registrations?.length ?? 0) >= selectedTournament.minPlayers
                          ? <span style={{ color: 'var(--accent-emerald)' }}>✅ Yes</span>
                          : <span style={{ color: '#f43f5e' }}>❌ No</span>
                      }
                    />
                    {(() => {
                      const regCount = selectedTournament.registrations?.length ?? 0;
                      const collected = regCount * selectedTournament.entryFeeRs;
                      const profit = collected - (selectedTournament.fixedPrizeRs ?? 0);
                      return (
                        <>
                          <StatBox label="Collected So Far" value={`₹${collected.toLocaleString()}`} />
                          <StatBox
                            label="Current Profit / Loss"
                            value={
                              <span style={{ color: profit >= 0 ? 'var(--accent-emerald)' : '#f43f5e' }}>
                                {profit >= 0 ? `+₹${profit.toLocaleString()}` : `-₹${Math.abs(profit).toLocaleString()} (Loss)`}
                              </span>
                            }
                          />
                        </>
                      );
                    })()}
                    <StatBox label="Reg. Opens" value={selectedTournament.registrationStartsAt ? fmtDate(selectedTournament.registrationStartsAt) : '—'} />
                    <StatBox label="Reg. Closes" value={selectedTournament.registrationEndsAt ? fmtDate(selectedTournament.registrationEndsAt) : '—'} />
                    <StatBox label="Start Time" value={fmtDate(selectedTournament.startsAt)} colSpan={2} />
                  </div>

                  {!['COMPLETED', 'CANCELLED', 'RUNNING'].includes(selectedTournament.status) && (
                    <button
                      className="btn btn-danger"
                      style={{ width: '100%', padding: '9px 12px', justifyContent: 'center', fontWeight: 700 }}
                      disabled={actionLoading === selectedTournament.tournamentId + '_cancel'}
                      onClick={() => handleCancel(selectedTournament.tournamentId)}
                    >
                      <Ban size={14} style={{ marginRight: 6 }} />
                      Cancel Tournament &amp; Refund All ({selectedTournament.registrations?.length ?? 0})
                    </button>
                  )}

                  {selectedTournament.cancelledReason && (
                    <div style={{ background: 'rgba(244,63,94,0.1)', border: '1px solid rgba(244,63,94,0.3)', borderRadius: 8, padding: '8px 12px', color: '#f43f5e', fontSize: 12 }}>
                      <XCircle size={12} style={{ marginRight: 4 }} />
                      Cancelled: {selectedTournament.cancelledReason}
                    </div>
                  )}

                  {/* Registered Players */}
                  {(selectedTournament.registrations?.length ?? 0) > 0 && (
                    <div>
                      <div style={{ fontWeight: 600, marginBottom: 6, color: 'var(--text-dim)', fontSize: 11, textTransform: 'uppercase' }}>
                        Registered Players ({selectedTournament.registrations?.length})
                      </div>
                      <div style={{ maxHeight: 160, overflowY: 'auto', display: 'flex', flexDirection: 'column', gap: 4 }}>
                        {selectedTournament.registrations?.map((r: any, i: number) => (
                          <div key={r.playerId} style={{ display: 'flex', justifyContent: 'space-between', fontSize: 12, padding: '4px 8px', background: 'rgba(255,255,255,0.03)', borderRadius: 6 }}>
                            <span style={{ color: 'var(--text-dim)' }}>#{i + 1}</span>
                            <span style={{ fontWeight: 600 }}>{r.displayName || r.playerId}</span>
                            <span style={{ color: 'var(--text-dim)', fontSize: 10 }}>{r.refunded ? '↩ Refunded' : '✓ Registered'}</span>
                          </div>
                        ))}
                      </div>
                    </div>
                  )}

                  {/* Final Rankings */}
                  {(selectedTournament.finalRankings?.length ?? 0) > 0 && (
                    <div>
                      <div style={{ fontWeight: 600, marginBottom: 6, color: 'var(--text-dim)', fontSize: 11, textTransform: 'uppercase' }}>
                        🏆 Final Rankings
                      </div>
                      {selectedTournament.finalRankings?.slice(0, 12).map((r) => (
                        <div key={r.rank} style={{ display: 'flex', justifyContent: 'space-between', fontSize: 12, padding: '4px 8px', background: r.rank <= 3 ? 'rgba(212,175,55,0.1)' : 'rgba(255,255,255,0.03)', borderRadius: 6, marginBottom: 3 }}>
                          <span style={{ color: 'var(--accent-gold)', fontWeight: 700 }}>#{r.rank}</span>
                          <span style={{ fontWeight: 600 }}>{r.displayName || r.playerId}</span>
                          <span style={{ color: 'var(--accent-emerald)', fontWeight: 600 }}>₹{r.prizeRs.toFixed(0)}</span>
                        </div>
                      ))}
                    </div>
                  )}
                </div>
              </div>
            )}
          </div>
        )}
      </div>
    </div>
  );
};

// ── Helper Sub-component ──────────────────────────────────────────────────
const StatBox: React.FC<{ label: string; value: React.ReactNode; colSpan?: number }> = ({ label, value, colSpan }) => (
  <div style={{ gridColumn: colSpan === 2 ? 'span 2' : undefined, background: 'rgba(255,255,255,0.03)', borderRadius: 8, padding: '8px 10px', border: '1px solid var(--border-subtle)' }}>
    <div style={{ fontSize: 10, color: 'var(--text-dim)', textTransform: 'uppercase', letterSpacing: '0.6px', marginBottom: 3 }}>{label}</div>
    <div style={{ fontWeight: 700, fontSize: 13 }}>{value}</div>
  </div>
);
