import React, { useState, useEffect, useCallback, useRef } from 'react';
import { Copy, Check, DoorOpen, Play, LogOut, Users, Sparkles, Plus, KeyRound, Clock, X } from 'lucide-react';
import { useGameStore } from '../store/useGameStore';
import { authFetch } from '../utils/authClient';
import { getApiBaseUrl } from '../utils/apiConfig';
import { socketClient } from '../websocket/GameSocketClient';
import { soundEngine } from '../audio/soundEngine';
import { tryLockLandscape } from '../hooks/useRequiresLandscape';
import { InsufficientBalanceModal, type InsufficientBalanceInfo } from './InsufficientBalanceModal';

interface RoomPlayer {
  playerId: string;
  displayName: string;
  seat: number;
  debitedAmount: number;
}

interface PrivateRoomData {
  roomCode: string;
  tableId: string;
  hostPlayerId: string;
  hostDisplayName: string;
  rulesetId: string;
  maxPlayers: number;
  stakeTier: number;
  status: 'WAITING' | 'RUNNING' | 'COMPLETED' | 'CANCELLED' | 'EXPIRED';
  isHost: boolean;
  players: RoomPlayer[];
  expiresAt?: string;
  winnerDisplayName?: string;
  winnerPayoutRs?: number;
  createdAt?: string;
}

const VARIANTS = [
  { id: 'POINTS_13', label: 'Points Rummy', stakes: [0, 4, 8, 20, 40, 80, 160, 400] },
  { id: 'POOL_101', label: 'Pool 101', stakes: [0, 10, 25, 50, 100, 250] },
  { id: 'POOL_201', label: 'Pool 201', stakes: [0, 10, 25, 50, 100, 250] },
  { id: 'DEALS_2', label: '2 Deals (2P)', stakes: [0, 10, 25, 50, 100] },
  { id: 'DEALS_3', label: '3 Deals', stakes: [0, 10, 25, 50, 100] },
  { id: 'RUMMY_21', label: '21-Card Rummy', stakes: [0, 25, 50, 100, 250] },
] as const;

const VARIANT_LABEL: Record<string, string> = {
  POINTS_13: 'Points Rummy',
  POOL_101: 'Pool 101',
  POOL_201: 'Pool 201',
  DEALS_2: '2 Deals',
  DEALS_3: '3 Deals',
  RUMMY_21: '21-Card Rummy',
};

export const PrivateRoomLobby: React.FC<{
  playerId?: string;
  activeRoomCode: string | null;
  onSelectRoomCode: (code: string | null) => void;
}> = ({ playerId: propPlayerId, activeRoomCode, onSelectRoomCode }) => {
  const storePlayerId = useGameStore((s) => s.playerId);
  const displayName = useGameStore((s) => s.displayName);
  const setSession = useGameStore((s) => s.setSession);
  const setHasJoinedTable = useGameStore((s) => s.setHasJoinedTable);
  const setWalletBalance = useGameStore((s) => s.setWalletBalance);
  const connectionStatus = useGameStore((s) => s.connectionStatus);

  const playerId = propPlayerId || storePlayerId || '';
  const enteredTableRef = useRef<string | null>(null);

  const [mode, setMode] = useState<'CREATE' | 'JOIN'>('CREATE');
  const [rulesetId, setRulesetId] = useState<string>('POINTS_13');
  const [maxPlayers, setMaxPlayers] = useState<number>(6);
  const [stakeTier, setStakeTier] = useState<number>(20);
  const [joinCode, setJoinCode] = useState('');

  const [room, setRoom] = useState<PrivateRoomData | null>(null);
  const [myRooms, setMyRooms] = useState<PrivateRoomData[]>([]);
  const [historyRooms, setHistoryRooms] = useState<PrivateRoomData[]>([]);
  const [nowMs, setNowMs] = useState(() => Date.now());
  const [busy, setBusy] = useState(false);
  const [copied, setCopied] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [insufficientBalance, setInsufficientBalance] = useState<InsufficientBalanceInfo | null>(null);

  const activeVariant = VARIANTS.find((v) => v.id === rulesetId) || VARIANTS[0];
  const isTwoPlayerOnly = rulesetId === 'DEALS_2';

  const notifyErr = (msg: string) => {
    setError(msg);
    setTimeout(() => setError(null), 4500);
  };

  const refreshWallet = useCallback(async () => {
    if (!playerId) return;
    try {
      const res = await authFetch(`${getApiBaseUrl()}/api/wallet/balance?playerId=${encodeURIComponent(playerId)}`);
      if (res.ok) {
        const data = await res.json();
        if (typeof data.balance === 'number') setWalletBalance(data.balance);
      }
    } catch { /* ignore */ }
  }, [playerId, setWalletBalance]);

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

  useEffect(() => {
    if (!activeRoomCode) return;
    const id = setInterval(() => setNowMs(Date.now()), 1000);
    return () => clearInterval(id);
  }, [activeRoomCode]);

  const fetchMyRooms = useCallback(async () => {
    if (!playerId) return;
    try {
      const res = await authFetch(`${getApiBaseUrl()}/api/rooms/my?playerId=${encodeURIComponent(playerId)}`);
      if (res.ok) {
        const data = await res.json();
        const list: PrivateRoomData[] = data.rooms || [];
        setMyRooms(list.filter((r) => r.status === 'WAITING' || r.status === 'RUNNING').slice(0, 4));
        setHistoryRooms(list.filter((r) => r.status === 'COMPLETED' || r.status === 'CANCELLED' || r.status === 'EXPIRED').slice(0, 6));
      }
    } catch { /* ignore */ }
  }, [playerId]);

  useEffect(() => {
    if (!activeRoomCode) fetchMyRooms();
  }, [activeRoomCode, fetchMyRooms]);

  // Poll active room while waiting
  useEffect(() => {
    if (!activeRoomCode) return;
    let cancelled = false;
    const poll = async () => {
      try {
        const res = await authFetch(
          `${getApiBaseUrl()}/api/rooms/${encodeURIComponent(activeRoomCode)}?playerId=${encodeURIComponent(playerId)}`
        );
        if (!res.ok) return;
        const data = await res.json();
        if (cancelled) return;
        const r: PrivateRoomData | undefined = data.room;
        if (!r) return;
        setRoom(r);
        if (r.status === 'RUNNING' && r.tableId) {
          enterAssignedTable(r.tableId);
        } else if (r.status === 'CANCELLED' || r.status === 'EXPIRED') {
          await refreshWallet();
          onSelectRoomCode(null);
          setRoom(null);
          notifyErr(`Room ${r.roomCode} was ${r.status.toLowerCase()}. Your entry stake has been refunded.`);
        }
      } catch { /* ignore */ }
    };
    poll();
    const id = setInterval(poll, 2500);
    return () => { cancelled = true; clearInterval(id); };
  }, [activeRoomCode, playerId, enterAssignedTable, onSelectRoomCode, refreshWallet]);

  const handleSelectVariant = (id: string) => {
    setRulesetId(id);
    const v = VARIANTS.find((x) => x.id === id);
    if (v && !v.stakes.includes(stakeTier as never)) {
      setStakeTier(v.stakes[1] ?? 0);
    }
    if (id === 'DEALS_2') setMaxPlayers(2);
  };

  const handleCreate = async () => {
    if (!playerId) return notifyErr('Please wait for your session to initialize.');
    void tryLockLandscape();
    setBusy(true);
    try {
      const res = await authFetch(`${getApiBaseUrl()}/api/rooms/create`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          playerId,
          displayName: displayName || playerId,
          rulesetId,
          maxPlayers: isTwoPlayerOnly ? 2 : maxPlayers,
          stakeTier,
        }),
      });
      const data = await res.json();
      if (!res.ok || !data.success) {
        if (res.status === 402 || data.error === 'INSUFFICIENT_BALANCE') {
          await refreshWallet();
          setInsufficientBalance({
            requiredAmount: stakeTier > 0 ? stakeTier : undefined,
            currentBalance: useGameStore.getState().walletBalance,
            contextLabel: 'create this private room',
          });
          return;
        }
        notifyErr(data.message || 'Could not create room.');
        return;
      }
      soundEngine.play('click');
      setRoom(data.room);
      onSelectRoomCode(data.room.roomCode);
      await refreshWallet();
    } catch {
      notifyErr('Network error while creating room.');
    } finally {
      setBusy(false);
    }
  };

  const handleJoin = async (codeOverride?: string) => {
    const code = (codeOverride ?? joinCode).trim().toUpperCase();
    if (!code) return notifyErr('Please enter a 6-character Room Code.');
    if (!playerId) return notifyErr('Please wait for your session to initialize.');
    void tryLockLandscape();
    setBusy(true);
    try {
      const res = await authFetch(`${getApiBaseUrl()}/api/rooms/join/${encodeURIComponent(code)}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ playerId, displayName: displayName || playerId }),
      });
      const data = await res.json();
      if (!res.ok || !data.success) {
        if (res.status === 402 || data.error === 'INSUFFICIENT_BALANCE') {
          await refreshWallet();
          setInsufficientBalance({
            currentBalance: useGameStore.getState().walletBalance,
            contextLabel: 'join this private room',
          });
          return;
        }
        notifyErr(data.message || 'Could not join room.');
        return;
      }
      soundEngine.play('click');
      setRoom(data.room);
      onSelectRoomCode(data.room.roomCode);
      await refreshWallet();
      if (data.room.status === 'RUNNING' && data.room.tableId) {
        enterAssignedTable(data.room.tableId);
      }
    } catch {
      notifyErr('Network error while joining room.');
    } finally {
      setBusy(false);
    }
  };

  const handleStart = async () => {
    if (!room) return;
    void tryLockLandscape();
    setBusy(true);
    try {
      const res = await authFetch(`${getApiBaseUrl()}/api/rooms/${encodeURIComponent(room.roomCode)}/start`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ playerId }),
      });
      const data = await res.json();
      if (!res.ok || !data.success) {
        notifyErr(data.message || 'Could not start room.');
        return;
      }
      if (data.tableId) enterAssignedTable(data.tableId);
    } catch {
      notifyErr('Network error starting room.');
    } finally {
      setBusy(false);
    }
  };

  const handleLeave = async () => {
    if (!room) { onSelectRoomCode(null); return; }
    setBusy(true);
    try {
      await authFetch(`${getApiBaseUrl()}/api/rooms/${encodeURIComponent(room.roomCode)}/leave`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ playerId }),
      });
      await refreshWallet();
    } catch { /* ignore */ } finally {
      setBusy(false);
      setRoom(null);
      onSelectRoomCode(null);
    }
  };

  const copyCode = async () => {
    if (!room) return;
    const text = room.roomCode;
    let ok = false;
    try {
      if (navigator.clipboard && window.isSecureContext) {
        await navigator.clipboard.writeText(text);
        ok = true;
      }
    } catch { /* fallback below */ }

    if (!ok) {
      try {
        const ta = document.createElement('textarea');
        ta.value = text;
        ta.setAttribute('readonly', '');
        ta.style.position = 'fixed';
        ta.style.top = '-9999px';
        ta.style.left = '-9999px';
        document.body.appendChild(ta);
        ta.focus();
        ta.select();
        ta.setSelectionRange(0, text.length);
        ok = document.execCommand('copy');
        document.body.removeChild(ta);
      } catch { /* ignore */ }
    }

    if (ok) {
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    }
  };

  const handleKick = async (targetPlayerId: string) => {
    if (!room || !room.isHost) return;
    setBusy(true);
    try {
      const res = await authFetch(`${getApiBaseUrl()}/api/rooms/${encodeURIComponent(room.roomCode)}/kick`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ playerId, targetPlayerId }),
      });
      const data = await res.json();
      if (!res.ok || !data.success) {
        notifyErr(data.message || 'Could not remove player.');
        return;
      }
      if (data.room) setRoom(data.room);
    } catch {
      notifyErr('Network error removing player.');
    } finally {
      setBusy(false);
    }
  };

  // ── WAITING ROOM VIEW ─────────────────────────────────────────────────────
  if (activeRoomCode && room) {
    const canStart = room.isHost && room.players.length >= 2;
    const expiresMs = room.expiresAt ? new Date(room.expiresAt).getTime() : 0;
    const secLeft = expiresMs > 0 ? Math.max(0, Math.floor((expiresMs - nowMs) / 1000)) : 0;
    const expMin = Math.floor(secLeft / 60);
    const expSec = String(secLeft % 60).padStart(2, '0');
    const emptySeatsCount = Math.max(0, room.maxPlayers - room.players.length);

    return (
      <div className="prv-wrap prv-waiting">
        <div className="prv-card">
          <div className="prv-waiting-top">
            <div>
              <span className="prv-eyebrow">PRIVATE ROOM LOBBY</span>
              <h2 className="prv-title">{VARIANT_LABEL[room.rulesetId] || room.rulesetId}</h2>
            </div>
            <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
              {expiresMs > 0 && (
                <span className="prv-expiry-pill" title="Auto-expires with full refund if not started">
                  <Clock size={12} /> {expMin}m {expSec}s
                </span>
              )}
              <button type="button" className="prv-leave-btn" onClick={handleLeave} disabled={busy}>
                <LogOut size={14} /> {room.isHost ? 'Close Room' : 'Leave Room'}
              </button>
            </div>
          </div>

          {error && <div className="prv-error">{error}</div>}

          <div className="prv-code-box">
            <span className="prv-code-label">SHARE THIS ROOM CODE WITH FRIENDS</span>
            <div className="prv-code-row">
              <span className="prv-code-value">{room.roomCode}</span>
              <button type="button" className="prv-copy-btn" onClick={copyCode}>
                {copied ? <><Check size={14} /> Copied</> : <><Copy size={14} /> Copy</>}
              </button>
            </div>
            <div className="prv-meta-strip">
              <span><Users size={13} /> {room.players.length} / {room.maxPlayers} Players</span>
              <span>•</span>
              <span>{room.stakeTier > 0 ? `Stake: ₹${room.stakeTier}` : 'Free Practice Room'}</span>
              {room.stakeTier > 0 && (
                <>
                  <span>•</span>
                  <span>Est. Pool: ₹{Math.round(room.stakeTier * room.players.length * 0.85)}</span>
                </>
              )}
            </div>
          </div>

          <div className="prv-players-list">
            {room.players.map((p) => (
              <div key={p.playerId} className="prv-player-chip">
                <span className="prv-seat-badge">{p.seat + 1}.</span>
                <span className="prv-player-name">{p.displayName}</span>
                {p.playerId === room.hostPlayerId && <span className="prv-host-tag">HOST</span>}
                {room.isHost && p.playerId !== room.hostPlayerId && (
                  <button
                    type="button"
                    className="prv-kick-btn"
                    title={`Kick ${p.displayName} (auto-refund)`}
                    disabled={busy}
                    onClick={() => handleKick(p.playerId)}
                  >
                    <X size={12} />
                  </button>
                )}
              </div>
            ))}
            {Array.from({ length: emptySeatsCount }).map((_, idx) => (
              <div key={`empty-${idx}`} className="prv-player-chip prv-empty-seat">
                <span className="prv-seat-badge">{room.players.length + idx + 1}.</span>
                <span>Empty Seat</span>
              </div>
            ))}
          </div>

          {room.isHost ? (
            <button
              type="button"
              className="prv-primary-btn"
              disabled={!canStart || busy}
              onClick={handleStart}
            >
              <Play size={15} />
              {busy ? 'Starting Table…' : room.players.length < 2 ? 'Waiting for at least 1 friend to join…' : `Start Game (${room.players.length} Players)`}
            </button>
          ) : (
            <div className="prv-guest-wait">
              <Sparkles size={15} /> Waiting for host <strong>{room.hostDisplayName}</strong> to start the game…
            </div>
          )}
        </div>
      </div>
    );
  }

  // ── CREATE / JOIN HUB ─────────────────────────────────────────────────────
  return (
    <div className="prv-wrap">
      <div className="lobby-choose-heading">
        <span className="lobby-choose-eyebrow">Play with Friends</span>
        <h1 className="lobby-choose-title">
          Private <em>Room</em>
        </h1>
      </div>

      <div className="prv-card">
        <div className="prv-mode-switch">
          <button
            type="button"
            className={`prv-mode-btn ${mode === 'CREATE' ? 'active' : ''}`}
            onClick={() => setMode('CREATE')}
          >
            <Plus size={14} /> Create Room
          </button>
          <button
            type="button"
            className={`prv-mode-btn ${mode === 'JOIN' ? 'active' : ''}`}
            onClick={() => setMode('JOIN')}
          >
            <KeyRound size={14} /> Join with Code
          </button>
        </div>

        {error && <div className="prv-error">{error}</div>}

        {mode === 'CREATE' ? (
          <div className="prv-form">
            <div className="prv-field">
              <label>Game Variant</label>
              <div className="prv-pill-grid">
                {VARIANTS.map((v) => (
                  <button
                    key={v.id}
                    type="button"
                    className={`prv-pill ${rulesetId === v.id ? 'active' : ''}`}
                    onClick={() => handleSelectVariant(v.id)}
                  >
                    {v.label}
                  </button>
                ))}
              </div>
            </div>

            <div className="prv-field-row">
              <div className="prv-field">
                <label>Max Players {isTwoPlayerOnly ? '(Fixed 2P)' : ''}</label>
                <div className="prv-pill-row">
                  {[2, 3, 4, 5, 6].map((n) => (
                    <button
                      key={n}
                      type="button"
                      disabled={isTwoPlayerOnly && n !== 2}
                      className={`prv-pill ${(isTwoPlayerOnly ? 2 : maxPlayers) === n ? 'active' : ''}`}
                      onClick={() => setMaxPlayers(n)}
                    >
                      {n}P
                    </button>
                  ))}
                </div>
              </div>

              <div className="prv-field">
                <label>{rulesetId === 'POINTS_13' ? 'Buy-in / Max Loss' : 'Entry Fee'}</label>
                <div className="prv-pill-row">
                  {activeVariant.stakes.map((s) => (
                    <button
                      key={s}
                      type="button"
                      className={`prv-pill ${stakeTier === s ? 'active' : ''}`}
                      onClick={() => setStakeTier(s)}
                    >
                      {s === 0 ? 'Free' : `₹${s}`}
                    </button>
                  ))}
                </div>
              </div>
            </div>

            <button type="button" className="prv-primary-btn" disabled={busy} onClick={handleCreate}>
              <DoorOpen size={16} /> {busy ? 'Creating Room…' : stakeTier > 0 ? `Create Room · ₹${stakeTier}` : 'Create Free Room'}
            </button>
          </div>
        ) : (
          <div className="prv-form">
            <div className="prv-field">
              <label>Enter 6-Character Room Code</label>
              <input
                type="text"
                maxLength={8}
                className="prv-code-input"
                placeholder="e.g. RMY482"
                value={joinCode}
                onChange={(e) => setJoinCode(e.target.value.toUpperCase())}
              />
            </div>
            <button type="button" className="prv-primary-btn" disabled={busy} onClick={() => handleJoin()}>
              <KeyRound size={15} /> {busy ? 'Joining…' : 'Join Private Room'}
            </button>
          </div>
        )}

        {myRooms.length > 0 && (
          <div className="prv-recent">
            <div className="prv-recent-title">Active Rooms</div>
            <div className="prv-recent-list">
              {myRooms.map((r) => (
                <button
                  key={r.roomCode}
                  type="button"
                  className="prv-recent-item"
                  onClick={() => {
                    if (r.status === 'RUNNING' && r.tableId) enterAssignedTable(r.tableId);
                    else { setRoom(r); onSelectRoomCode(r.roomCode); }
                  }}
                >
                  <span className="prv-recent-code">{r.roomCode}</span>
                  <span>{VARIANT_LABEL[r.rulesetId] || r.rulesetId}</span>
                  <span>{r.players.length}/{r.maxPlayers}P</span>
                  <span className="prv-recent-status">{r.status}</span>
                </button>
              ))}
            </div>
          </div>
        )}
      </div>

      {historyRooms.length > 0 && (
        <div className="prv-card" style={{ padding: '14px 18px', gap: '10px' }}>
          <div className="prv-recent-title" style={{ marginBottom: 0 }}>Recent Private Room History</div>
          <div className="prv-recent-list">
            {historyRooms.map((r) => (
              <div key={r.roomCode} className="prv-recent-item prv-history-row">
                <span className="prv-recent-code">{r.roomCode}</span>
                <span>{VARIANT_LABEL[r.rulesetId] || r.rulesetId}</span>
                <span>{r.stakeTier > 0 ? `₹${r.stakeTier}` : 'Free'}</span>
                <span style={{ color: r.status === 'COMPLETED' ? '#6ee7b7' : '#fda4af', fontWeight: 700, fontSize: '11px' }}>
                  {r.status === 'COMPLETED'
                    ? (r.winnerDisplayName ? `🏆 ${r.winnerDisplayName}` : 'COMPLETED')
                    : `${r.status} (Refunded)`}
                </span>
              </div>
            ))}
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
