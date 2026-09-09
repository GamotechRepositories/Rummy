import React, { useState, useEffect, useRef } from 'react';
import { useGameStore } from '../store/useGameStore';
import { socketClient } from '../websocket/GameSocketClient';
import { HistoryModal } from './HistoryModal';
import { WalletModal } from './WalletModal';
import { AdminDashboardModal } from './AdminDashboardModal';
import { ResponsibleGamblingModal } from './ResponsibleGamblingModal';
import { ShieldCheck, Play, Sparkles, Trophy, Users, Zap, Loader2, X, Coins, Activity } from 'lucide-react';

export const LobbyScreen: React.FC = () => {
  const { tableId, displayName, setSession, connectionStatus, playerId } = useGameStore();
  const [localName, setLocalName] = useState(displayName);
  const [localTable, setLocalTable] = useState(tableId);
  const [selectedSeat, setSelectedSeat] = useState(0);
  const [isHistoryOpen, setIsHistoryOpen] = useState(false);
  const [isWalletOpen, setIsWalletOpen] = useState(false);
  const [isAdminOpen, setIsAdminOpen] = useState(false);
  const [isSafePlayOpen, setIsSafePlayOpen] = useState(false);

  // Matchmaking State
  const [isMatchmaking, setIsMatchmaking] = useState(false);
  const [mmTicketId, setMmTicketId] = useState<string | null>(null);
  const [mmQueueTime, setMmQueueTime] = useState(0);
  const [mmVariant, setMmVariant] = useState('INDIAN_POINTS');
  const [mmStake, setMmStake] = useState(100);
  const [mmMaxPlayers, setMmMaxPlayers] = useState(2);
  const pollIntervalRef = useRef<number | null>(null);

  useEffect(() => {
    let timer: number;
    if (isMatchmaking) {
      timer = window.setInterval(() => {
        setMmQueueTime((prev) => prev + 1);
      }, 1000);
    } else {
      setMmQueueTime(0);
    }
    return () => clearInterval(timer);
  }, [isMatchmaking]);

  const handleStartMatchmaking = async () => {
    if (!localName.trim()) return;
    setIsMatchmaking(true);
    setMmQueueTime(0);

    try {
      const res = await fetch('http://localhost:8081/api/matchmaking/join', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          playerId,
          playerName: localName.trim(),
          rulesetId: mmVariant,
          stakeTier: mmStake,
          maxPlayers: mmMaxPlayers,
          allowAiFallback: true,
        }),
      });

      if (!res.ok) throw new Error('Matchmaking join failed');
      const data = await res.json();
      setMmTicketId(data.ticketId);

      // Start polling
      pollIntervalRef.current = window.setInterval(async () => {
        try {
          const pollRes = await fetch(`http://localhost:8081/api/matchmaking/ticket/${data.ticketId}`);
          if (pollRes.ok) {
            const ticketData = await pollRes.json();
            if (ticketData.status === 'MATCHED' && ticketData.matchedTableId) {
              if (pollIntervalRef.current) clearInterval(pollIntervalRef.current);
              setIsMatchmaking(false);
              setSession(ticketData.matchedTableId, playerId, localName.trim());
              socketClient.connect();
            }
          }
        } catch {
          // ignore network transient errors
        }
      }, 1000);
    } catch {
      setIsMatchmaking(false);
    }
  };

  const handleCancelMatchmaking = async () => {
    if (pollIntervalRef.current) clearInterval(pollIntervalRef.current);
    if (mmTicketId) {
      try {
        await fetch(`http://localhost:8081/api/matchmaking/cancel/${mmTicketId}`, { method: 'POST' });
      } catch {
        // ignore
      }
    }
    setIsMatchmaking(false);
    setMmTicketId(null);
  };

  const handleJoin = (e: React.FormEvent) => {
    e.preventDefault();
    if (!localName.trim()) return;

    setSession(localTable, useGameStore.getState().playerId, localName.trim());

    if (connectionStatus === 'CONNECTED') {
      socketClient.joinTable(selectedSeat);
    } else {
      socketClient.connect();
    }
  };

  return (
    <div
      style={{
        display: 'flex',
        flexDirection: 'column',
        alignItems: 'center',
        justifyContent: 'center',
        minHeight: '80vh',
        padding: '24px',
      }}
    >
      <div
        className="glass-panel"
        style={{
          width: '100%',
          maxWidth: '540px',
          padding: '36px',
          border: '1px solid rgba(212, 175, 55, 0.4)',
          position: 'relative',
        }}
      >
        {/* Australian Legal Notice Gate */}
        <div
          style={{
            display: 'flex',
            alignItems: 'flex-start',
            gap: '12px',
            padding: '12px 14px',
            background: 'rgba(16, 185, 129, 0.1)',
            border: '1px solid rgba(16, 185, 129, 0.3)',
            borderRadius: '10px',
            marginBottom: '24px',
          }}
        >
          <ShieldCheck size={22} color="var(--color-pure)" style={{ flexShrink: 0, marginTop: '2px' }} />
          <div style={{ fontSize: '11px', color: '#cbd5e1', lineHeight: 1.4 }}>
            <strong style={{ color: 'var(--color-pure)' }}>Compliant Free-Play Mode:</strong> In accordance with Australia's Interactive Gambling Act 2001 (IGA), this platform operates strictly with free-play virtual tokens. No real currency is deposited, wagered, or paid out.
          </div>
        </div>

        {/* Title */}
        <div style={{ textAlign: 'center', marginBottom: '28px' }}>
          <div
            style={{
              display: 'inline-flex',
              alignItems: 'center',
              gap: '6px',
              color: 'var(--gold-accent)',
              fontSize: '12px',
              fontWeight: 700,
              textTransform: 'uppercase',
              letterSpacing: '1px',
              marginBottom: '6px',
            }}
          >
            <Sparkles size={14} />
            13-Card Indian Rummy Classic
          </div>
          <h1
            style={{
              fontSize: '28px',
              fontWeight: 900,
              background: 'linear-gradient(135deg, #ffffff 0%, #cbd5e1 50%, #fef08a 100%)',
              WebkitBackgroundClip: 'text',
              WebkitTextFillColor: 'transparent',
              marginBottom: '4px',
            }}
          >
            Royal Australian Rummy
          </h1>
          <p style={{ color: 'var(--text-muted)', fontSize: '13px' }}>
            High-concurrency server-authoritative card engine & TableActor cluster
          </p>
        </div>

        {/* Form */}
        <form onSubmit={handleJoin} style={{ display: 'flex', flexDirection: 'column', gap: '18px' }}>
          <div>
            <label
              htmlFor="input-player-name"
              style={{ display: 'block', fontSize: '13px', fontWeight: 600, color: 'var(--text-muted)', marginBottom: '6px' }}
            >
              Player Display Name
            </label>
            <input
              id="input-player-name"
              type="text"
              value={localName}
              onChange={(e) => setLocalName(e.target.value)}
              placeholder="e.g. OutbackKing"
              style={{
                width: '100%',
                padding: '12px 16px',
                background: 'rgba(0, 0, 0, 0.4)',
                border: '1px solid rgba(255, 255, 255, 0.15)',
                borderRadius: '8px',
                color: '#ffffff',
                fontSize: '15px',
                outline: 'none',
              }}
              required
            />
          </div>

          {/* Quick Matchmaking Section */}
          <div
            style={{
              padding: '16px',
              background: 'linear-gradient(135deg, rgba(212, 175, 55, 0.15) 0%, rgba(30, 41, 59, 0.5) 100%)',
              border: '1px solid rgba(212, 175, 55, 0.3)',
              borderRadius: '12px',
              display: 'flex',
              flexDirection: 'column',
              gap: '12px',
            }}
          >
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: '8px', fontWeight: 700, fontSize: '14px', color: 'var(--gold-light)' }}>
                <Zap size={16} color="var(--gold-accent)" />
                Quick Matchmaking (Phase 18)
              </div>
              <div style={{ display: 'flex', gap: '6px' }}>
                <button
                  type="button"
                  onClick={() => setMmMaxPlayers(2)}
                  style={{
                    padding: '4px 10px',
                    borderRadius: '6px',
                    fontSize: '11px',
                    fontWeight: 700,
                    border: mmMaxPlayers === 2 ? '1px solid var(--gold-accent)' : '1px solid rgba(255, 255, 255, 0.1)',
                    background: mmMaxPlayers === 2 ? 'rgba(212, 175, 55, 0.25)' : 'transparent',
                    color: mmMaxPlayers === 2 ? '#ffffff' : 'var(--text-muted)',
                    cursor: 'pointer',
                  }}
                >
                  2 Players
                </button>
                <button
                  type="button"
                  onClick={() => setMmMaxPlayers(6)}
                  style={{
                    padding: '4px 10px',
                    borderRadius: '6px',
                    fontSize: '11px',
                    fontWeight: 700,
                    border: mmMaxPlayers === 6 ? '1px solid var(--gold-accent)' : '1px solid rgba(255, 255, 255, 0.1)',
                    background: mmMaxPlayers === 6 ? 'rgba(212, 175, 55, 0.25)' : 'transparent',
                    color: mmMaxPlayers === 6 ? '#ffffff' : 'var(--text-muted)',
                    cursor: 'pointer',
                  }}
                >
                  6 Players
                </button>
              </div>
            </div>

            {/* Stake and Variant Selectors */}
            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '8px' }}>
              <div>
                <label style={{ display: 'block', fontSize: '11px', color: 'var(--text-muted)', marginBottom: '4px' }}>Ruleset</label>
                <select
                  value={mmVariant}
                  onChange={(e) => setMmVariant(e.target.value)}
                  style={{
                    width: '100%',
                    padding: '8px',
                    borderRadius: '6px',
                    background: '#0f172a',
                    border: '1px solid rgba(255,255,255,0.15)',
                    color: '#ffffff',
                    fontSize: '12px',
                    outline: 'none',
                  }}
                >
                  <option value="INDIAN_POINTS">Points Rummy</option>
                  <option value="POOL_101">Pool 101</option>
                </select>
              </div>
              <div>
                <label style={{ display: 'block', fontSize: '11px', color: 'var(--text-muted)', marginBottom: '4px' }}>Stake Tier</label>
                <select
                  value={mmStake}
                  onChange={(e) => setMmStake(Number(e.target.value))}
                  style={{
                    width: '100%',
                    padding: '8px',
                    borderRadius: '6px',
                    background: '#0f172a',
                    border: '1px solid rgba(255,255,255,0.15)',
                    color: '#ffffff',
                    fontSize: '12px',
                    outline: 'none',
                  }}
                >
                  <option value={100}>100 Free Tokens</option>
                  <option value={500}>500 Free Tokens</option>
                  <option value={1000}>1,000 Free Tokens</option>
                </select>
              </div>
            </div>

            {isMatchmaking ? (
              <div
                style={{
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'space-between',
                  padding: '12px 16px',
                  background: 'rgba(0, 0, 0, 0.4)',
                  borderRadius: '8px',
                  border: '1px solid var(--gold-accent)',
                }}
              >
                <div style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
                  <Loader2 size={20} className="spinner" color="var(--gold-accent)" />
                  <div>
                    <div style={{ fontSize: '13px', fontWeight: 700, color: '#ffffff' }}>Searching Opponents...</div>
                    <div style={{ fontSize: '11px', color: 'var(--text-muted)' }}>Queue time: {mmQueueTime}s (AI fallback ready)</div>
                  </div>
                </div>
                <button
                  type="button"
                  onClick={handleCancelMatchmaking}
                  style={{
                    padding: '6px 12px',
                    borderRadius: '6px',
                    background: 'rgba(239, 68, 68, 0.2)',
                    border: '1px solid rgba(239, 68, 68, 0.4)',
                    color: '#f87171',
                    fontSize: '12px',
                    fontWeight: 700,
                    cursor: 'pointer',
                    display: 'flex',
                    alignItems: 'center',
                    gap: '4px',
                  }}
                >
                  <X size={14} /> Cancel
                </button>
              </div>
            ) : (
              <button
                type="button"
                id="btn-quick-match"
                onClick={handleStartMatchmaking}
                style={{
                  padding: '12px',
                  background: 'linear-gradient(135deg, #d4af37 0%, #b38728 100%)',
                  border: 'none',
                  borderRadius: '8px',
                  color: '#0f172a',
                  fontWeight: 800,
                  fontSize: '14px',
                  cursor: 'pointer',
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'center',
                  gap: '8px',
                  boxShadow: '0 4px 12px rgba(212, 175, 55, 0.3)',
                }}
              >
                <Users size={16} />
                Find Match (Stake: {mmStake} Free Tokens)
              </button>
            )}
          </div>

          <div style={{ textAlign: 'center', color: 'var(--text-muted)', fontSize: '12px', margin: '-4px 0' }}>
            — or join a specific lounge room —
          </div>

          <div>
            <label
              htmlFor="select-table"
              style={{ display: 'block', fontSize: '13px', fontWeight: 600, color: 'var(--text-muted)', marginBottom: '6px' }}
            >
              Select Lounge Table
            </label>
            <select
              id="select-table"
              value={localTable}
              onChange={(e) => setLocalTable(e.target.value)}
              style={{
                width: '100%',
                padding: '12px 16px',
                background: '#0f172a',
                border: '1px solid rgba(255, 255, 255, 0.15)',
                borderRadius: '8px',
                color: '#ffffff',
                fontSize: '14px',
                outline: 'none',
              }}
            >
              <option value="TBL_SYDNEY_01">Sydney Gold Lounge (Table #01)</option>
              <option value="TBL_MELBOURNE_02">Melbourne Platinum Suite (Table #02)</option>
              <option value="TBL_BRISBANE_03">Brisbane Sapphire Club (Table #03)</option>
            </select>
          </div>

          <div>
            <label
              style={{ display: 'block', fontSize: '13px', fontWeight: 600, color: 'var(--text-muted)', marginBottom: '8px' }}
            >
              Select Seat Position
            </label>
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(6, 1fr)', gap: '8px' }}>
              {[0, 1, 2, 3, 4, 5].map((seat) => (
                <button
                  key={seat}
                  type="button"
                  id={`btn-seat-${seat}`}
                  onClick={() => setSelectedSeat(seat)}
                  style={{
                    padding: '10px',
                    borderRadius: '8px',
                    border: selectedSeat === seat ? '2px solid var(--border-gold)' : '1px solid rgba(255, 255, 255, 0.1)',
                    background: selectedSeat === seat ? 'rgba(212, 175, 55, 0.2)' : 'rgba(0, 0, 0, 0.3)',
                    color: selectedSeat === seat ? 'var(--gold-light)' : 'var(--text-muted)',
                    fontWeight: 700,
                    fontSize: '13px',
                    cursor: 'pointer',
                  }}
                >
                  #{seat + 1}
                </button>
              ))}
            </div>
          </div>

          <button
            id="btn-join-table"
            type="submit"
            className="btn-primary"
            style={{
              marginTop: '10px',
              padding: '14px',
              fontSize: '16px',
              justifyContent: 'center',
            }}
          >
            <Play size={18} />
            Enter Game Table
          </button>

          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '8px', marginTop: '8px' }}>
            <button
              id="btn-lobby-wallet"
              type="button"
              onClick={() => setIsWalletOpen(true)}
              className="btn-secondary"
              style={{
                width: '100%',
                padding: '12px',
                fontSize: '13px',
                fontWeight: 700,
                justifyContent: 'center',
                background: 'linear-gradient(135deg, rgba(212, 175, 55, 0.2), rgba(212, 175, 55, 0.05))',
                border: '1px solid rgba(212, 175, 55, 0.4)',
                color: '#f3e5ab',
                display: 'flex',
                alignItems: 'center',
                gap: '6px',
              }}
            >
              <Coins size={16} color="#d4af37" />
              Token Vault
            </button>

            <button
              id="btn-lobby-history"
              type="button"
              onClick={() => setIsHistoryOpen(true)}
              className="btn-secondary"
              style={{
                width: '100%',
                padding: '12px',
                fontSize: '13px',
                justifyContent: 'center',
                display: 'flex',
                alignItems: 'center',
                gap: '6px',
              }}
            >
              📜 Match History
            </button>
          </div>

          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '10px', marginTop: '8px' }}>
            <button
              id="btn-lobby-safe-play"
              type="button"
              onClick={() => setIsSafePlayOpen(true)}
              style={{
                width: '100%',
                padding: '10px',
                fontSize: '12px',
                fontWeight: 600,
                background: 'rgba(16, 185, 129, 0.1)',
                border: '1px solid rgba(16, 185, 129, 0.3)',
                borderRadius: '8px',
                color: '#34d399',
                cursor: 'pointer',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                gap: '6px',
              }}
            >
              <ShieldCheck size={14} />
              🛡️ Safe Play
            </button>

            <button
              id="btn-lobby-admin"
              type="button"
              onClick={() => setIsAdminOpen(true)}
              style={{
                width: '100%',
                padding: '10px',
                fontSize: '12px',
                fontWeight: 600,
                background: 'rgba(59, 130, 246, 0.1)',
                border: '1px solid rgba(59, 130, 246, 0.25)',
                borderRadius: '8px',
                color: '#93c5fd',
                cursor: 'pointer',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                gap: '6px',
              }}
            >
              <Activity size={14} />
              Admin Console
            </button>
          </div>
        </form>

        <WalletModal
          isOpen={isWalletOpen}
          onClose={() => setIsWalletOpen(false)}
        />

        <HistoryModal
          playerId={playerId}
          isOpen={isHistoryOpen}
          onClose={() => setIsHistoryOpen(false)}
        />

        <AdminDashboardModal
          isOpen={isAdminOpen}
          onClose={() => setIsAdminOpen(false)}
        />

        <ResponsibleGamblingModal
          isOpen={isSafePlayOpen}
          onClose={() => setIsSafePlayOpen(false)}
        />

        {/* Feature points */}
        <div
          style={{
            display: 'grid',
            gridTemplateColumns: '1fr 1fr',
            gap: '12px',
            marginTop: '28px',
            paddingTop: '20px',
            borderTop: '1px solid rgba(255, 255, 255, 0.08)',
          }}
        >
          <div style={{ display: 'flex', alignItems: 'center', gap: '8px', fontSize: '11px', color: 'var(--text-muted)' }}>
            <Trophy size={14} color="var(--gold-accent)" />
            <span>Zero-Knowledge Server</span>
          </div>
          <div style={{ display: 'flex', alignItems: 'center', gap: '8px', fontSize: '11px', color: 'var(--text-muted)' }}>
            <Sparkles size={14} color="var(--color-pure)" />
            <span>Pure Sequence Engine</span>
          </div>
        </div>
      </div>
    </div>
  );
};
