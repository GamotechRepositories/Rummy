import React, { useState } from 'react';
import { useGameStore } from '../store/useGameStore';
import { socketClient } from '../websocket/GameSocketClient';
import { HistoryModal } from './HistoryModal';
import { ShieldCheck, Play, Sparkles, Trophy } from 'lucide-react';

export const LobbyScreen: React.FC = () => {
  const { tableId, displayName, setSession, connectionStatus, playerId } = useGameStore();
  const [localName, setLocalName] = useState(displayName);
  const [localTable, setLocalTable] = useState(tableId);
  const [selectedSeat, setSelectedSeat] = useState(0);
  const [isHistoryOpen, setIsHistoryOpen] = useState(false);

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

          <button
            id="btn-lobby-history"
            type="button"
            onClick={() => setIsHistoryOpen(true)}
            className="btn-secondary"
            style={{
              width: '100%',
              padding: '12px',
              fontSize: '14px',
              justifyContent: 'center',
              marginTop: '8px',
            }}
          >
            📜 View Career Match History
          </button>
        </form>

        <HistoryModal
          playerId={playerId}
          isOpen={isHistoryOpen}
          onClose={() => setIsHistoryOpen(false)}
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
