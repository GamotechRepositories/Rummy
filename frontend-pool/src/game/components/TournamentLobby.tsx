import React, { useState, useEffect, useCallback, useRef } from 'react';
import { Trophy, RefreshCw, Clock, Users, AlertTriangle, CheckCircle } from 'lucide-react';
import { getApiBaseUrl } from '../utils/apiConfig';
import { authFetch } from '../utils/authClient';
import { useGameStore } from '../store/useGameStore';
import { socketClient } from '../websocket/GameSocketClient';
import { soundEngine } from '../audio/soundEngine';
import { tryLockLandscape } from '../hooks/useRequiresLandscape';
import { InsufficientBalanceModal, type InsufficientBalanceInfo } from './InsufficientBalanceModal';

// ── Types ───────────────────────────────────────────────────────────────────
interface PublicTournament {
  tournamentId: string;
  title: string;
  variant: string;
  entryFeeRs: number;
  prizePoolRs: number;
  isGuaranteed: boolean;
  minPlayers: number;
  maxPlayers: number;
  registered: number;
  checkedInCount?: number;
  checkInWindowMinutes?: number;
  checkInEndsAt?: string;
  registrationStartsAt?: string;
  registrationEndsAt?: string;
  startsAt: string;
  status: string;
  cancelledReason?: string;
  isRegistered?: boolean;
  isCheckedIn?: boolean;
  myRank?: number | null;
  myPrizeRs?: number;
  assignedTableId?: string | null;
}

// ── Helpers ──────────────────────────────────────────────────────────────────
function CountdownTimer({ startsAt }: { startsAt: string }) {
  const [text, setText] = useState('');

  useEffect(() => {
    const update = () => {
      const diff = new Date(startsAt).getTime() - Date.now();
      if (diff <= 0) { setText('Starting…'); return; }
      const h = Math.floor(diff / 3600000);
      const m = Math.floor((diff % 3600000) / 60000);
      const s = Math.floor((diff % 60000) / 1000);
      setText(h > 0 ? `${h}h ${m}m ${s}s` : `${m}m ${s}s`);
    };
    update();
    const id = setInterval(update, 1000);
    return () => clearInterval(id);
  }, [startsAt]);

  return <span>{text}</span>;
}

function fmtDate(iso: string) {
  return new Date(iso).toLocaleString('en-IN', { dateStyle: 'medium', timeStyle: 'short' });
}

function fmtShortDate(iso: string) {
  return new Date(iso).toLocaleString('en-IN', { day: 'numeric', month: 'short', hour: 'numeric', minute: '2-digit' });
}

// ── Component ────────────────────────────────────────────────────────────────
export const TournamentLobby: React.FC<{
  playerId?: string;
  operatorId?: string;
  activeWaitingId: string | null;
  onSelectWaitingId: (id: string | null) => void;
}> = ({
  playerId,
  operatorId,
  activeWaitingId,
  onSelectWaitingId,
}) => {
  const { displayName, setSession, setHasJoinedTable, connectionStatus, setWalletBalance } = useGameStore();
  const [tournaments, setTournaments] = useState<PublicTournament[]>([]);
  const [loading, setLoading] = useState(true);
  const [registering, setRegistering] = useState<string | null>(null);
  const [checkingIn, setCheckingIn] = useState<string | null>(null);
  const [confirmTarget, setConfirmTarget] = useState<PublicTournament | null>(null);
  const [confirmedModal, setConfirmedModal] = useState<{ title: string; registered: number; maxPlayers: number } | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [insufficientBalance, setInsufficientBalance] = useState<InsufficientBalanceInfo | null>(null);
  const [nowMs, setNowMs] = useState(() => Date.now());
  const enteredTableRef = useRef<string | null>(null);

  useEffect(() => {
    const id = setInterval(() => setNowMs(Date.now()), 1000);
    return () => clearInterval(id);
  }, []);

  const notify = (msg: string, isErr = false) => {
    if (isErr) { setError(msg); }
    setTimeout(() => { setError(null); }, 4000);
  };

  const enterAssignedTable = useCallback((tableId: string) => {
    if (!playerId || enteredTableRef.current === tableId) return;
    enteredTableRef.current = tableId;
    soundEngine.play('deal');
    setSession(tableId, playerId, displayName || playerId);
    setHasJoinedTable(true);
    if (connectionStatus === 'CONNECTED') {
      socketClient.joinTable(0);
    } else {
      socketClient.connect();
    }
  }, [playerId, displayName, setSession, setHasJoinedTable, connectionStatus]);

  const fetchTournaments = useCallback(async (silent = false) => {
    if (!silent) setLoading(true);
    try {
      const qs = playerId ? `?playerId=${encodeURIComponent(playerId)}` : '';
      const res = await authFetch(`${getApiBaseUrl()}/api/tournaments${qs}`);
      if (!res.ok) {
        if (!silent) {
          notify(`Could not load tournaments (HTTP ${res.status}). Make sure game-service is restarted.`, true);
          setTournaments([]);
        }
        return;
      }
      const data = await res.json();
      const list: PublicTournament[] = data.tournaments || [];
      setTournaments(list);

      // Auto-seat player if they are registered and assigned to a live tournament table
      const activeAssigned = list.find((t) => t.isRegistered && t.assignedTableId);
      if (activeAssigned?.assignedTableId) {
        enterAssignedTable(activeAssigned.assignedTableId);
        return;
      }

      // Clear waiting state if tournament is no longer STARTING
      if (activeWaitingId) {
        const stillStarting = list.find(
          (t) => t.tournamentId === activeWaitingId && t.status === 'STARTING' && t.isCheckedIn
        );
        if (!stillStarting) {
          onSelectWaitingId(null);
        }
      }
    } catch {
      if (!silent) setTournaments([]);
    } finally {
      if (!silent) setLoading(false);
    }
  }, [playerId, enterAssignedTable, activeWaitingId, onSelectWaitingId]);

  useEffect(() => { fetchTournaments(); }, [fetchTournaments]);

  // Poll every 4s while user has any active registration so auto-seating triggers immediately
  useEffect(() => {
    const hasReg = tournaments.some((t) => t.isRegistered);
    if (!hasReg) return;
    const id = setInterval(() => fetchTournaments(true), 4000);
    return () => clearInterval(id);
  }, [tournaments, fetchTournaments]);

  const handleCheckIn = async (t: PublicTournament) => {
    if (!playerId) return;
    void tryLockLandscape();
    setCheckingIn(t.tournamentId);
    try {
      const res = await authFetch(`${getApiBaseUrl()}/api/tournaments/${t.tournamentId}/checkin`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ playerId }),
      });
      const data = await res.json();
      if (data.success) {
        setTournaments((prev) =>
          prev.map((x) =>
            x.tournamentId === t.tournamentId
              ? { ...x, isCheckedIn: true, checkedInCount: data.checkedInCount }
              : x
          )
        );
        onSelectWaitingId(t.tournamentId);
      } else {
        notify(data.message || 'Check-in failed.', true);
      }
    } catch (err: any) {
      notify(err.message || 'Network error', true);
    } finally {
      setCheckingIn(null);
    }
  };

  const handleRegister = (t: PublicTournament) => {
    if (!playerId) { notify('Please log in to register.', true); return; }
    if (t.isRegistered) { notify('You are already registered for this tournament.', true); return; }
    setConfirmTarget(t);
  };

  const executeRegister = async (t: PublicTournament) => {
    if (!playerId) return;
    setConfirmTarget(null);
    setRegistering(t.tournamentId);
    try {
      const res = await authFetch(`${getApiBaseUrl()}/api/tournaments/${t.tournamentId}/register`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          playerId,
          displayName: displayName || playerId,
          operatorId: operatorId || 'DEFAULT',
        }),
      });
      const data = await res.json();
      if (data.success) {
        setConfirmedModal({ title: t.title, registered: data.registered, maxPlayers: t.maxPlayers });
        setTournaments((prev) =>
          prev.map((x) =>
            x.tournamentId === t.tournamentId
              ? { ...x, registered: data.registered, isRegistered: true }
              : x
          )
        );
        // Refresh wallet balance after entry fee debit
        try {
          const balRes = await authFetch(`${getApiBaseUrl()}/api/wallet/balance?playerId=${encodeURIComponent(playerId)}`);
          if (balRes.ok) {
            const balData = await balRes.json();
            setWalletBalance(Number(balData.balance ?? 0));
          }
        } catch {
          // ignore silent balance refresh error
        }
      } else if (res.status === 402 || data.error === 'INSUFFICIENT_BALANCE') {
        let latestBal: number | null = useGameStore.getState().walletBalance;
        try {
          const balRes = await authFetch(`${getApiBaseUrl()}/api/wallet/balance?playerId=${encodeURIComponent(playerId)}`);
          if (balRes.ok) {
            const balData = await balRes.json();
            if (typeof balData.balance === 'number') {
              latestBal = balData.balance;
              setWalletBalance(balData.balance);
            }
          }
        } catch {
          // ignore silent balance refresh error
        }
        setInsufficientBalance({
          requiredAmount: t.entryFeeRs > 0 ? t.entryFeeRs : undefined,
          currentBalance: latestBal,
          contextLabel: 'register for this tournament',
        });
      } else {
        notify(data.message || data.error || `Registration failed (HTTP ${res.status}).`, true);
      }
    } catch (err: any) {
      notify(err.message || 'Network error', true);
    } finally {
      setRegistering(null);
    }
  };

  const liveTournaments = tournaments.filter((t) => ['REGISTERING', 'STARTING', 'RUNNING'].includes(t.status));

  const waitingTournament = activeWaitingId
    ? liveTournaments.find((t) => t.tournamentId === activeWaitingId && t.status === 'STARTING' && t.isCheckedIn)
    : null;

  if (waitingTournament) {
    return (
      <div className="trn-waiting-page">
        <button
          type="button"
          className="trn-waiting-back"
          onClick={() => onSelectWaitingId(null)}
        >
          ← Back to Tournaments
        </button>

        <div className="trn-card trn-waiting-hero">
          <span className="stake-suit stake-suit-tl" aria-hidden>♠</span>
          <span className="stake-suit stake-suit-tr" aria-hidden>♦</span>
          <span className="stake-suit stake-suit-bl" aria-hidden>♣</span>
          <span className="stake-suit stake-suit-br" aria-hidden>♥</span>

          <div className="stake-emblem trn-emblem">
            <div className="stake-ribbon trn-ribbon">
              <h2>{waitingTournament.title.toUpperCase()}</h2>
              <p>TOURNAMENT WAITING LOBBY</p>
            </div>
          </div>

          <div className="trn-waiting-pulse-row">
            <span className="trn-pulse-dot" />
            <span>You are checked in &amp; ready!</span>
          </div>

          <div className="trn-waiting-timer-box">
            <div className="trn-waiting-timer-label">MATCH STARTS IN</div>
            <div className="trn-waiting-timer-val">
              {waitingTournament.checkInEndsAt ? (
                <CountdownTimer startsAt={waitingTournament.checkInEndsAt} />
              ) : (
                'Starting soon…'
              )}
            </div>
          </div>

          <div className="trn-stats-row">
            <div className="trn-stat">
              <span className="trn-stat-label">Checked In</span>
              <span className="trn-stat-val" style={{ color: '#6ee7b7' }}>
                {waitingTournament.checkedInCount ?? 1} / {waitingTournament.registered}
              </span>
            </div>
            <div className="trn-stat trn-stat--highlight">
              <span className="trn-stat-label">Prize Pool</span>
              <span className="trn-stat-val trn-prize">
                ₹{Math.round(waitingTournament.prizePoolRs).toLocaleString()}
              </span>
            </div>
            <div className="trn-stat">
              <span className="trn-stat-label">Format</span>
              <span className="trn-stat-val" style={{ fontSize: 13 }}>
                3 Deals · 6P
              </span>
            </div>
          </div>

          <div className="trn-waiting-note">
            Please stay on this screen. As soon as the timer reaches <strong>0:00</strong>,
            you will be automatically seated at your Deals Rummy table.
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="tournament-lobby">
      {/* Centered Royal Heading (matching Choose Your Rummy) */}
      <div className="lobby-choose-heading">
        <span className="lobby-choose-eyebrow">Select a tournament</span>
        <h1 className="lobby-choose-title">
          <em>Tournaments</em>
        </h1>
        <span className="lobby-choose-ornament" aria-hidden>
          <i />
          <span>♠</span>
          <i />
        </span>
      </div>

      {/* Error Toast */}
      {error && (
        <div className="trn-toast trn-toast-error">
          <AlertTriangle size={13} /> {error}
        </div>
      )}

      {/* Loading */}
      {loading && (
        <div className="trn-empty">
          <RefreshCw size={18} className="spin" />
          <span>Loading tournaments…</span>
        </div>
      )}

      {/* Empty */}
      {!loading && liveTournaments.length === 0 && (
        <div className="trn-empty">
          <Trophy size={36} className="trn-empty-icon" />
          <p>No upcoming tournaments right now.</p>
          <p className="trn-empty-sub">Check back soon — new tournaments are added daily!</p>
        </div>
      )}

      {/* Tournament Cards */}
      {!loading && liveTournaments.length > 0 && (
        <div className={liveTournaments.length === 1 ? 'trn-cards-grid trn-cards-grid--single' : 'trn-cards-grid'}>
          {liveTournaments.map((t) => {
        const fillPct = Math.min(100, Math.round((t.registered / t.maxPlayers) * 100));
        const meetsMin = t.registered >= t.minPlayers;
        const isFull = t.registered >= t.maxPlayers;
        const isStarting = t.status === 'STARTING';
        const isRunning = t.status === 'RUNNING';
        const isCompleted = t.status === 'COMPLETED';
        const isCancelled = t.status === 'CANCELLED';
        const regClosesMs = t.registrationEndsAt ? new Date(t.registrationEndsAt).getTime() : null;
        const regOpensMs = t.registrationStartsAt ? new Date(t.registrationStartsAt).getTime() : null;
        const isRegClosed = !isRunning && !isStarting && !isCancelled && !isCompleted && regClosesMs !== null && nowMs >= regClosesMs;
        const isRegNotOpenYet = !isRunning && !isStarting && !isCancelled && !isCompleted && regOpensMs !== null && nowMs < regOpensMs;
        const prize = t.prizePoolRs;

        return (
          <div key={t.tournamentId} className={`trn-card ${t.isRegistered ? 'trn-card--registered' : ''} ${isCancelled ? 'trn-card--cancelled' : ''}`}>
            <span className="stake-suit stake-suit-tl" aria-hidden>♠</span>
            <span className="stake-suit stake-suit-tr" aria-hidden>♦</span>
            <span className="stake-suit stake-suit-bl" aria-hidden>♣</span>
            <span className="stake-suit stake-suit-br" aria-hidden>♥</span>

            {/* Royal Ribbon Header */}
            <div className="stake-emblem trn-emblem">
              <div className="stake-ribbon trn-ribbon">
                <h2>{t.title.toUpperCase()}</h2>
                <p>DEALS RUMMY · 3 DEALS · 6 PLAYERS</p>
              </div>
            </div>

            {/* Top badge */}
            <div className="trn-card-badges">
              {t.isGuaranteed && !isCancelled && !isCompleted && <span className="trn-badge trn-badge-gtd">🏆 FIXED PRIZE POOL</span>}
              {isCompleted && (t.myPrizeRs ?? 0) > 0 && <span className="trn-badge trn-badge-gtd">🏆 WON ₹{Math.round(t.myPrizeRs ?? 0).toLocaleString()}</span>}
              {isCompleted && !(t.myPrizeRs ?? 0) && <span className="trn-badge trn-badge-upcoming">{t.isCheckedIn ? 'PLAYED · LOST' : 'MISSED CHECK-IN'}</span>}
              {isCancelled && <span className="trn-badge trn-badge-live">❌ CANCELLED</span>}
              {isStarting && <span className="trn-badge trn-badge-live">⏳ CHECK-IN OPEN</span>}
              {isRunning && <span className="trn-badge trn-badge-live">🔴 LIVE NOW</span>}
            </div>

            {/* Stats Row */}
            <div className="trn-stats-row">
              <div className="trn-stat">
                <span className="trn-stat-label">Entry Fee</span>
                <span className="trn-stat-val">
                  {t.entryFeeRs === 0 ? <span className="trn-free-badge">FREE</span> : `₹${t.entryFeeRs}`}
                </span>
              </div>
              <div className="trn-stat trn-stat--highlight">
                <span className="trn-stat-label">Prize Pool</span>
                <span className="trn-stat-val trn-prize">₹{Math.round(prize).toLocaleString()}</span>
              </div>
              <div className="trn-stat">
                <span className="trn-stat-label">
                  <Clock size={11} style={{ marginRight: 3 }} />
                  {isCompleted ? 'Result' : isCancelled ? 'Status' : isStarting && t.checkInEndsAt ? 'Lobby Closes' : t.isRegistered ? 'Starts In' : 'Reg. Closes'}
                </span>
                <span className="trn-stat-val trn-countdown" style={{ color: isCancelled || (isCompleted && !(t.myPrizeRs ?? 0)) || (!t.isRegistered && !isStarting && isRegClosed) ? '#fda4af' : isCompleted && (t.myPrizeRs ?? 0) > 0 ? '#6ee7b7' : undefined }}>
                  {isCompleted ? (
                    (t.myPrizeRs ?? 0) > 0 ? `Won #${t.myRank ?? 1}` : t.isCheckedIn ? `Rank #${t.myRank ?? '-'}` : 'No-Show'
                  ) : isCancelled ? (
                    'Cancelled'
                  ) : isStarting && t.checkInEndsAt ? (
                    <CountdownTimer startsAt={t.checkInEndsAt} />
                  ) : t.isRegistered ? (
                    <CountdownTimer startsAt={t.startsAt} />
                  ) : isRegClosed ? (
                    'Closed'
                  ) : t.registrationEndsAt ? (
                    fmtShortDate(t.registrationEndsAt)
                  ) : (
                    '—'
                  )}
                </span>
              </div>
            </div>

            {/* Players bar */}
            <div className="trn-players-row">
              <Users size={12} />
              <span className="trn-players-text">
                <strong style={{ color: meetsMin ? 'var(--accent-emerald, #10b981)' : 'var(--accent-amber, #f59e0b)' }}>
                  {t.registered}
                </strong>
                {' / '}{t.maxPlayers} players joined
                {!meetsMin && (
                  <span className="trn-min-warn"> (min {t.minPlayers} needed)</span>
                )}
              </span>
            </div>
            <div className="trn-progress-bar">
              <div
                className="trn-progress-fill"
                style={{
                  width: `${fillPct}%`,
                  background: fillPct >= 100
                    ? 'var(--accent-rose, #f43f5e)'
                    : fillPct >= 70
                    ? 'var(--accent-emerald, #10b981)'
                    : 'var(--accent-gold, #d4af37)',
                }}
              />
            </div>

            {/* Action / Status area */}
            {isCompleted ? (
              <div className="trn-already-registered" style={{ color: (t.myPrizeRs ?? 0) > 0 ? '#6ee7b7' : '#fda4af' }}>
                {(t.myPrizeRs ?? 0) > 0
                  ? `🏆 You Won! Rank #${t.myRank ?? 1} · +₹${Math.round(t.myPrizeRs ?? 0).toLocaleString()} credited`
                  : t.isCheckedIn
                  ? `You played · Lost (Rank #${t.myRank ?? '-'})`
                  : 'You registered but missed check-in'}
              </div>
            ) : isCancelled ? (
              <div className="trn-cancelled-box">
                <div className="trn-cancelled-title">❌ Tournament Cancelled</div>
                {t.isRegistered && t.entryFeeRs > 0 && (
                  <div className="trn-cancelled-refund">
                    ↩️ Your ₹{t.entryFeeRs} entry fee has been refunded to your wallet.
                  </div>
                )}
              </div>
            ) : isStarting && t.isRegistered ? (
              t.isCheckedIn ? (
                <button
                  className="trn-register-btn trn-join-lobby-btn"
                  onClick={() => { void tryLockLandscape(); onSelectWaitingId(t.tournamentId); }}
                >
                  ⏳ Open Waiting Lobby ({t.checkedInCount ?? 1} Ready)
                </button>
              ) : (
                <button
                  className="trn-register-btn trn-join-lobby-btn"
                  disabled={checkingIn === t.tournamentId}
                  onClick={() => handleCheckIn(t)}
                >
                  {checkingIn === t.tournamentId ? 'Joining Lobby…' : '🟢 JOIN TOURNAMENT LOBBY'}
                </button>
              )
            ) : isRunning && t.isRegistered && !t.isCheckedIn ? (
              <div className="trn-missed-checkin">
                ⏰ You are late! Check-in closed — you cannot join now.
              </div>
            ) : t.isRegistered ? (
              <div className="trn-already-registered">
                <CheckCircle size={15} />
                <span>You are already registered</span>
              </div>
            ) : (
              <button
                className={`trn-register-btn ${isRegClosed || isStarting ? 'trn-register-btn--closed' : ''}`}
                disabled={isFull || isRunning || isStarting || isRegClosed || isRegNotOpenYet || registering === t.tournamentId}
                onClick={() => handleRegister(t)}
              >
                {registering === t.tournamentId ? 'Registering…' :
                 isRunning ? 'In Progress' :
                 isStarting ? 'Check-In Open (Registered Only)' :
                 isRegClosed ? '🔒 Registration Closed' :
                 isRegNotOpenYet ? 'Registration Opens Soon' :
                 isFull ? 'Registration Full' :
                 t.entryFeeRs === 0 ? 'Register FREE' :
                 `Register · ₹${t.entryFeeRs}`}
              </button>
            )}

            {/* Start time */}
            <div className="trn-starttime">
              Starts: {fmtDate(t.startsAt)}
            </div>
          </div>
        );
      })}
        </div>
      )}

      {/* Registration Confirmation Modal */}
      {confirmTarget && (
        <div className="trn-modal-backdrop" onClick={() => setConfirmTarget(null)}>
          <div className="trn-modal-card" onClick={(e) => e.stopPropagation()}>
            <div className="trn-modal-title">
              <Trophy size={18} style={{ color: '#fde047' }} />
              <span>Confirm Registration</span>
            </div>
            <div className="trn-modal-sub">
              {confirmTarget.title} · {confirmTarget.entryFeeRs === 0 ? 'FREE Entry' : `Entry Fee: ₹${confirmTarget.entryFeeRs}`}
            </div>
            <div className="trn-modal-warning">
              <AlertTriangle size={16} style={{ flexShrink: 0, color: '#fbbf24' }} />
              <div>
                <strong>Are you sure you want to register?</strong>
                <p style={{ marginTop: 4, opacity: 0.9 }}>
                  Once registered, you <strong>cannot cancel</strong> or withdraw from this tournament.
                  {confirmTarget.entryFeeRs > 0 && ` ₹${confirmTarget.entryFeeRs} will be debited from your wallet immediately (auto-refunded only if the tournament is cancelled).`}
                </p>
              </div>
            </div>
            <div className="trn-modal-actions">
              <button
                type="button"
                className="trn-modal-cancel"
                onClick={() => setConfirmTarget(null)}
              >
                Cancel
              </button>
              <button
                type="button"
                className="trn-modal-confirm"
                onClick={() => executeRegister(confirmTarget)}
              >
                {confirmTarget.entryFeeRs === 0 ? 'Yes, Register FREE' : `Yes, Pay ₹${confirmTarget.entryFeeRs}`}
              </button>
            </div>
          </div>
        </div>
      )}
      {/* Registration Confirmed Modal */}
      {confirmedModal && (
        <div className="trn-modal-backdrop" onClick={() => setConfirmedModal(null)}>
          <div className="trn-modal-card" onClick={(e) => e.stopPropagation()}>
            <div className="trn-modal-title" style={{ color: '#6ee7b7' }}>
              <CheckCircle size={20} style={{ color: '#10b981' }} />
              <span>Your Registration is Confirmed!</span>
            </div>
            <div className="trn-modal-sub">
              {confirmedModal.title} · {confirmedModal.registered} / {confirmedModal.maxPlayers} players joined
            </div>
            <div className="trn-modal-warning" style={{ color: '#e2e8f0' }}>
              Please remain online when the tournament starts — you will be automatically seated at your Deals Rummy table.
            </div>
            <div className="trn-modal-actions">
              <button
                type="button"
                className="trn-modal-confirm"
                onClick={() => setConfirmedModal(null)}
              >
                OK
              </button>
            </div>
          </div>
        </div>
      )}

      <InsufficientBalanceModal
        info={insufficientBalance}
        onClose={() => setInsufficientBalance(null)}
      />
    </div>
  );
};
