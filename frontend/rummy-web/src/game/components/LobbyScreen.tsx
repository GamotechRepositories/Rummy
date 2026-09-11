import React, { useState, useEffect, useRef, useCallback } from 'react';
import { useGameStore } from '../store/useGameStore';
import { socketClient } from '../websocket/GameSocketClient';
import { WalletModal } from './WalletModal';
import {
  Crown,
  Coins,
  Play,
  Loader2,
  X,
  PlusCircle,
  Sparkles,
  Layers,
  Trophy,
} from 'lucide-react';
import { SoundToggle } from './SoundToggle';
import { soundEngine } from '../audio/soundEngine';

/**
 * Beginner-first lobby: one clear path to play Points Rummy (practice).
 */
interface LobbyScreenProps {
  onOpenTutorial?: () => void;
}

export const LobbyScreen: React.FC<LobbyScreenProps> = ({ onOpenTutorial }) => {
  const { displayName, setSession, setHasJoinedTable, connectionStatus, playerId } = useGameStore();

  const [localName, setLocalName] = useState(displayName);
  const [isEditingName, setIsEditingName] = useState(false);
  const [walletBalance, setWalletBalance] = useState<number>(1000);
  const [isWalletOpen, setIsWalletOpen] = useState(false);

  const [isMatchmaking, setIsMatchmaking] = useState(false);
  const [mmTicketId, setMmTicketId] = useState<string | null>(null);
  const [mmQueueTime, setMmQueueTime] = useState(0);
  const [errorMsg, setErrorMsg] = useState<string | null>(null);
  const pollIntervalRef = useRef<number | null>(null);

  const PRACTICE_STAKE = 50;

  const fetchBalance = useCallback(async () => {
    try {
      const res = await fetch(`http://localhost:8081/api/wallet/balance?playerId=${playerId}`);
      if (res.ok) {
        const data = await res.json();
        setWalletBalance(data.freePlayBalance ?? 1000);
      }
    } catch {
      // Backend offline — keep default
    }
  }, [playerId]);

  useEffect(() => {
    fetchBalance();
    const interval = setInterval(fetchBalance, 10000);
    return () => clearInterval(interval);
  }, [fetchBalance]);

  useEffect(() => {
    if (!isMatchmaking) {
      setMmQueueTime(0);
      return;
    }
    const timer = window.setInterval(() => setMmQueueTime((t) => t + 1), 1000);
    return () => clearInterval(timer);
  }, [isMatchmaking]);

  const handlePlay = async () => {
    const name = localName.trim() || 'Player';
    setErrorMsg(null);

    if (walletBalance < PRACTICE_STAKE) {
      setErrorMsg(`You need ${PRACTICE_STAKE} tokens to play. Open Vault to claim free tokens.`);
      setIsWalletOpen(true);
      return;
    }

    setIsMatchmaking(true);
    setMmQueueTime(0);
    soundEngine.play('match');

    try {
      const res = await fetch('http://localhost:8081/api/matchmaking/join', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          playerId,
          playerName: name,
          rulesetId: 'POINTS_13',
          stakeTier: PRACTICE_STAKE,
          maxPlayers: 2,
          allowAiFallback: true,
        }),
      });

      if (!res.ok) {
        const errData = await res.json().catch(() => ({}));
        throw new Error(errData.message || 'Could not start matchmaking');
      }

      const data = await res.json();
      setMmTicketId(data.ticketId);

      pollIntervalRef.current = window.setInterval(async () => {
        try {
          const pollRes = await fetch(`http://localhost:8081/api/matchmaking/ticket/${data.ticketId}`);
          if (!pollRes.ok) return;
          const ticketData = await pollRes.json();
            if (ticketData.status === 'MATCHED' && ticketData.matchedTableId) {
              if (pollIntervalRef.current) clearInterval(pollIntervalRef.current);
              setIsMatchmaking(false);
              soundEngine.play('deal');
              setSession(ticketData.matchedTableId, playerId, name);
            setHasJoinedTable(true);
            if (connectionStatus === 'CONNECTED') {
              socketClient.joinTable(0);
            } else {
              socketClient.connect();
            }
          }
        } catch {
          // retry next poll
        }
      }, 1000);
    } catch (err: unknown) {
      setIsMatchmaking(false);
      setErrorMsg(err instanceof Error ? err.message : 'Could not join a game.');
    }
  };

  const handleCancel = async () => {
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

  return (
    <div className="lobby-frame" style={{ color: '#f8fafc' }}>
      {/* Top bar — name + tokens only */}
      <header
        style={{
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between',
          padding: '10px 16px',
          borderBottom: '1px solid rgba(255,255,255,0.06)',
          flexShrink: 0,
        }}
      >
        <div style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
          <div
            style={{
              width: 36,
              height: 36,
              borderRadius: 10,
              background: 'linear-gradient(135deg, #d4af37, #78350f)',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
            }}
          >
            <Crown size={20} color="#fff" />
          </div>
          <span
            style={{
              fontFamily: 'var(--font-display)',
              fontSize: 18,
              fontWeight: 800,
              letterSpacing: '0.5px',
            }}
          >
            Royal Rummy
          </span>
        </div>

        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <SoundToggle compact />
          {onOpenTutorial && (
            <button
              type="button"
              onClick={() => {
                soundEngine.play('click');
                onOpenTutorial();
              }}
              className="btn-secondary"
              style={{ padding: '6px 10px', fontSize: 12 }}
            >
              How to play
            </button>
          )}
          {isEditingName ? (
            <input
              type="text"
              value={localName}
              onChange={(e) => setLocalName(e.target.value)}
              onBlur={() => setIsEditingName(false)}
              onKeyDown={(e) => e.key === 'Enter' && setIsEditingName(false)}
              autoFocus
              maxLength={16}
              aria-label="Your name"
              style={{
                background: '#0f172a',
                border: '1px solid var(--border-gold)',
                borderRadius: 8,
                color: '#fff',
                fontSize: 13,
                padding: '6px 10px',
                width: 120,
                outline: 'none',
              }}
            />
          ) : (
            <button
              type="button"
              onClick={() => setIsEditingName(true)}
              style={{
                background: 'rgba(255,255,255,0.06)',
                border: '1px solid rgba(255,255,255,0.12)',
                borderRadius: 20,
                color: '#e2e8f0',
                padding: '6px 12px',
                fontSize: 12,
                fontWeight: 600,
                cursor: 'pointer',
              }}
            >
              {localName || 'Player'} ✎
            </button>
          )}

          <button
            type="button"
            onClick={() => setIsWalletOpen(true)}
            style={{
              display: 'flex',
              alignItems: 'center',
              gap: 6,
              background: 'rgba(212,175,55,0.12)',
              border: '1px solid rgba(212,175,55,0.4)',
              borderRadius: 20,
              padding: '4px 6px 4px 10px',
              color: '#fef08a',
              cursor: 'pointer',
              fontWeight: 700,
              fontSize: 12,
            }}
          >
            <Coins size={14} />
            {walletBalance.toLocaleString()}
            <span
              style={{
                background: '#10b981',
                color: '#fff',
                borderRadius: 12,
                padding: '3px 6px',
                fontSize: 10,
                display: 'flex',
                alignItems: 'center',
                gap: 3,
              }}
            >
              <PlusCircle size={11} /> Vault
            </span>
          </button>
        </div>
      </header>

      {/* Hero — one job: start a game */}
      <main className="lobby-main">
        <div style={{ textAlign: 'center', maxWidth: 480 }}>
          <div
            style={{
              display: 'inline-flex',
              alignItems: 'center',
              gap: 6,
              background: 'rgba(16,185,129,0.15)',
              border: '1px solid rgba(16,185,129,0.35)',
              color: '#34d399',
              borderRadius: 20,
              padding: '4px 12px',
              fontSize: 11,
              fontWeight: 700,
              marginBottom: 10,
            }}
          >
            <Sparkles size={12} /> Free practice · vs player or AI
          </div>
          <h1
            style={{
              fontFamily: 'var(--font-display)',
              fontSize: 'clamp(24px, 6vw, 36px)',
              fontWeight: 900,
              lineHeight: 1.15,
              marginBottom: 8,
            }}
          >
            Make groups. Discard. Win.
          </h1>
          <p style={{ color: '#94a3b8', fontSize: 'clamp(13px, 3vw, 15px)', lineHeight: 1.45 }}>
            Classic 13-card Rummy. One button — we coach you turn by turn.
          </p>
        </div>

        {errorMsg && (
          <div
            style={{
              width: '100%',
              background: 'rgba(239,68,68,0.15)',
              border: '1px solid rgba(239,68,68,0.4)',
              borderRadius: 12,
              padding: '12px 16px',
              color: '#fca5a5',
              fontSize: 13,
              fontWeight: 600,
            }}
          >
            {errorMsg}
          </div>
        )}

        <button
          type="button"
          id="btn-play-now"
          onClick={() => {
            soundEngine.unlock();
            soundEngine.play('click');
            void handlePlay();
          }}
          disabled={isMatchmaking}
          className="btn-play-hero"
          style={{
            width: '100%',
            maxWidth: 360,
            padding: '18px 28px',
            borderRadius: 16,
            border: 'none',
            background: 'linear-gradient(135deg, #fbbf24 0%, #d97706 100%)',
            color: '#0f172a',
            fontSize: 20,
            fontWeight: 900,
            cursor: 'pointer',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            gap: 10,
            boxShadow: '0 8px 28px rgba(251, 191, 36, 0.35)',
          }}
        >
          <Play size={24} fill="#0f172a" />
          Play Now
        </button>

        <p style={{ fontSize: 13, color: '#64748b' }}>
          Costs {PRACTICE_STAKE} free tokens · 2 players · Points Rummy
        </p>

        {/* How to win — 3 steps */}
        <div
          style={{
            width: '100%',
            background: 'rgba(15, 23, 42, 0.7)',
            border: '1px solid rgba(255,255,255,0.08)',
            borderRadius: 16,
            padding: '20px 22px',
            display: 'flex',
            flexDirection: 'column',
            gap: 14,
          }}
        >
          <div style={{ fontSize: 13, fontWeight: 800, color: '#fef08a', letterSpacing: '0.4px' }}>
            HOW TO WIN (30 seconds)
          </div>
          {[
            {
              icon: <Layers size={18} color="#38bdf8" />,
              title: '1. Group your cards',
              text: 'Runs of the same suit (A♥ 2♥ 3♥) or same rank (7♠ 7♥ 7♦).',
            },
            {
              icon: <Trophy size={18} color="#34d399" />,
              title: '2. Need one pure run',
              text: 'At least one run with NO jokers. Plus one more run (jokers OK).',
            },
            {
              icon: <Play size={18} color="#fbbf24" />,
              title: '3. Each turn',
              text: 'Draw a card → throw one away. When ready, Declare to win.',
            },
          ].map((step) => (
            <div key={step.title} style={{ display: 'flex', gap: 12, alignItems: 'flex-start' }}>
              <div
                style={{
                  width: 36,
                  height: 36,
                  borderRadius: 10,
                  background: 'rgba(255,255,255,0.05)',
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'center',
                  flexShrink: 0,
                }}
              >
                {step.icon}
              </div>
              <div>
                <div style={{ fontWeight: 700, fontSize: 14, marginBottom: 2 }}>{step.title}</div>
                <div style={{ fontSize: 13, color: '#94a3b8', lineHeight: 1.4 }}>{step.text}</div>
              </div>
            </div>
          ))}
        </div>
      </main>

      {/* Matchmaking overlay */}
      {isMatchmaking && (
        <div
          style={{
            position: 'fixed',
            inset: 0,
            background: 'rgba(6, 9, 14, 0.92)',
            backdropFilter: 'blur(12px)',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            zIndex: 1000,
            padding: 20,
          }}
        >
          <div
            className="glass-panel"
            style={{
              width: '100%',
              maxWidth: 400,
              padding: 32,
              textAlign: 'center',
              display: 'flex',
              flexDirection: 'column',
              alignItems: 'center',
              gap: 18,
              border: '1px solid rgba(212,175,55,0.4)',
            }}
          >
            <Loader2 size={40} className="spinner" color="#d4af37" />
            <h2 style={{ fontSize: 20, fontWeight: 800, margin: 0 }}>Finding a table…</h2>
            <p style={{ fontSize: 14, color: '#94a3b8', margin: 0, lineHeight: 1.5 }}>
              {mmQueueTime < 15
                ? 'Looking for another player. If none join soon, you play vs AI.'
                : 'Starting with AI partner…'}
            </p>
            <div
              style={{
                width: '100%',
                height: 6,
                background: 'rgba(255,255,255,0.1)',
                borderRadius: 4,
                overflow: 'hidden',
              }}
            >
              <div
                style={{
                  height: '100%',
                  width: `${Math.min(100, (mmQueueTime / 15) * 100)}%`,
                  background: 'linear-gradient(90deg, #10b981, #d4af37)',
                  transition: 'width 1s linear',
                }}
              />
            </div>
            <button
              type="button"
              onClick={handleCancel}
              style={{
                marginTop: 4,
                background: 'transparent',
                border: '1px solid rgba(239,68,68,0.4)',
                color: '#f87171',
                borderRadius: 8,
                padding: '10px 20px',
                fontWeight: 700,
                cursor: 'pointer',
                display: 'flex',
                alignItems: 'center',
                gap: 6,
              }}
            >
              <X size={16} /> Cancel
            </button>
          </div>
        </div>
      )}

      <WalletModal
        isOpen={isWalletOpen}
        onClose={() => {
          setIsWalletOpen(false);
          fetchBalance();
        }}
      />
    </div>
  );
};
