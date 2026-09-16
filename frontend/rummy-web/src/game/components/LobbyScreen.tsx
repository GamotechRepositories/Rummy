import React, { useState, useEffect, useRef, useCallback } from 'react';
import { useGameStore } from '../store/useGameStore';
import { socketClient } from '../websocket/GameSocketClient';
import { WalletModal } from './WalletModal';
import {
  Crown,
  Coins,
  Play,
  X,
  ShieldCheck,
  Zap,
  Minus,
  Plus,
  Trophy,
  Layers,
  ArrowLeft,
  Pencil,
  Sparkles,
} from 'lucide-react';
import { SoundToggle } from './SoundToggle';
import { soundEngine } from '../audio/soundEngine';

export type VariantType = 'POINTS' | 'POOL' | 'DEALS' | 'RUMMY_21';

const POINT_VALUE_TIERS = [
  { pt: 0.05, entry: 4 },
  { pt: 0.1, entry: 8 },
  { pt: 0.25, entry: 20 },
  { pt: 0.5, entry: 40 },
  { pt: 1.0, entry: 80 },
  { pt: 2.0, entry: 160 },
  { pt: 5.0, entry: 400 },
];

const POOL_ENTRY_OPTIONS = [10, 25, 50, 100, 250];
const DEALS_ENTRY_OPTIONS = [10, 25, 50, 100];
const RUMMY_21_ENTRY_OPTIONS = [25, 50, 100, 250];

const VARIANT_BANNERS: Array<{
  id: VariantType;
  cardId: string;
  label: string;
  src: string;
}> = [
  { id: 'POINTS', cardId: 'card-select-points', label: 'Point Rummy', src: '/d42908de-8c15-4770-b847-f17f4652ef27.jpg' },
  { id: 'POOL', cardId: 'card-select-pool', label: 'Pool Rummy', src: '/2197f428-15ef-4951-a896-8769b4e87cef.jpg' },
  { id: 'DEALS', cardId: 'card-select-deals', label: 'Deal Rummy', src: '/83ab5ed4-1049-445c-804f-3f7b65c6e986.jpg' },
  { id: 'RUMMY_21', cardId: 'card-select-21card', label: '21-Card Rummy', src: '/f3e7974b-2f26-463a-9eff-6dead79be1da.jpg' },
];

interface LobbyScreenProps {
  onOpenTutorial?: () => void;
}

export const LobbyScreen: React.FC<LobbyScreenProps> = ({ onOpenTutorial }) => {
  const {
    displayName,
    setSession,
    setDisplayName,
    setHasJoinedTable,
    connectionStatus,
    playerId,
    lastGameConfig,
    autoMatchmakePending,
    setAutoMatchmakePending,
    setLastGameConfig,
  } = useGameStore();

  const [localName, setLocalName] = useState(displayName);
  const [isEditingName, setIsEditingName] = useState(false);
  const [walletBalance, setWalletBalance] = useState<number>(1000);
  const [isWalletOpen, setIsWalletOpen] = useState(false);
  const [rematchNotice, setRematchNotice] = useState(false);

  const commitDisplayName = () => {
    const next = localName.trim() || 'Player';
    setLocalName(next);
    setDisplayName(next);
    setIsEditingName(false);
  };

  // 2-PAGE FLOW: Page 1 = 'SELECT_VARIANT', Page 2 = 'CONFIGURE_TABLE'
  const [currentPage, setCurrentPage] = useState<'SELECT_VARIANT' | 'CONFIGURE_TABLE'>('SELECT_VARIANT');

  // Variant & Mode selection
  const [selectedVariant, setSelectedVariant] = useState<VariantType>('POINTS');
  const [selectedPlayers, setSelectedPlayers] = useState<number>(2);

  // Points Rummy Config
  const [ptIndex, setPtIndex] = useState<number>(1); // default 0.1 pt -> ₹8 entry fee

  // Pool Rummy Config
  const [poolSubVariant, setPoolSubVariant] = useState<'POOL_101' | 'POOL_201'>('POOL_101');
  const [poolEntry, setPoolEntry] = useState<number>(25);

  // Deals Rummy Config
  const [dealsEntry, setDealsEntry] = useState<number>(25);

  // 21-Card Rummy Config
  const [rummy21Entry, setRummy21Entry] = useState<number>(50);

  // Matchmaking State
  const [isMatchmaking, setIsMatchmaking] = useState(false);
  const [mmTicketId, setMmTicketId] = useState<string | null>(null);
  const [mmQueueTime, setMmQueueTime] = useState(0);
  const [errorMsg, setErrorMsg] = useState<string | null>(null);
  const pollIntervalRef = useRef<number | null>(null);
  const isEnqueuingRef = useRef(false);

  // Resolve current active stake and rulesetId
  let activeRulesetId = 'POINTS_13';
  let activeEntryFee = 8;
  let activeVariantTitle = 'Point Rummy';
  let activePointValue: number | null = 0.1;

  if (selectedVariant === 'POINTS') {
    activeRulesetId = 'POINTS_13';
    activePointValue = POINT_VALUE_TIERS[ptIndex].pt;
    activeEntryFee = POINT_VALUE_TIERS[ptIndex].entry;
    activeVariantTitle = 'Point Rummy';
  } else if (selectedVariant === 'POOL') {
    activeRulesetId = poolSubVariant;
    activePointValue = null;
    activeEntryFee = poolEntry;
    activeVariantTitle = poolSubVariant === 'POOL_101' ? 'Pool 101' : 'Pool 201';
  } else if (selectedVariant === 'DEALS') {
    activeRulesetId = 'DEALS_RUMMY';
    activePointValue = null;
    activeEntryFee = dealsEntry;
    activeVariantTitle = 'Deal Rummy';
  } else {
    activeRulesetId = 'RUMMY_21';
    activePointValue = null;
    activeEntryFee = rummy21Entry;
    activeVariantTitle = '21-Card Marriage Rummy';
  }

  const fetchBalance = useCallback(async () => {
    try {
      const res = await fetch(`http://localhost:8081/api/wallet/balance?playerId=${playerId}`);
      if (res.ok) {
        const data = await res.json();
        setWalletBalance(data.freePlayBalance ?? 1000);
      }
    } catch {
      // Backend offline fallback
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

  useEffect(() => {
    if (autoMatchmakePending) {
      setAutoMatchmakePending(false);
      setRematchNotice(true);
      const targetConfig = lastGameConfig ?? {
        rulesetId: activeRulesetId,
        entryFee: activeEntryFee,
        maxPlayers: selectedPlayers,
      };
      handlePlay(targetConfig);
    }
  }, [autoMatchmakePending]);

  useEffect(() => {
    if (!isMatchmaking) setRematchNotice(false);
  }, [isMatchmaking]);

  const handleSelectVariant = (variant: VariantType) => {
    soundEngine.play('click');
    setSelectedVariant(variant);
    setCurrentPage('CONFIGURE_TABLE');
  };

  const handlePlay = async (customConfig?: { rulesetId: string; entryFee: number; maxPlayers: number }) => {
    if (isEnqueuingRef.current || isMatchmaking) return;
    isEnqueuingRef.current = true;

    const name = localName.trim() || 'Player';
    setErrorMsg(null);

    const rId = customConfig?.rulesetId ?? activeRulesetId;
    const eFee = customConfig?.entryFee ?? activeEntryFee;
    const mPlayers = customConfig?.maxPlayers ?? selectedPlayers;

    setLastGameConfig({
      rulesetId: rId,
      entryFee: eFee,
      maxPlayers: mPlayers,
    });

    if (walletBalance < eFee) {
      setErrorMsg(
        `Insufficient Balance: You need ₹ ${eFee} to join this table. Click '+ Add Cash' to deposit.`
      );
      setIsWalletOpen(true);
      isEnqueuingRef.current = false;
      return;
    }

    if (pollIntervalRef.current) {
      clearInterval(pollIntervalRef.current);
      pollIntervalRef.current = null;
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
          rulesetId: rId,
          stakeTier: eFee,
          maxPlayers: mPlayers,
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
            if (pollIntervalRef.current) {
              clearInterval(pollIntervalRef.current);
              pollIntervalRef.current = null;
            }
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
      setErrorMsg(err instanceof Error ? err.message : 'Could not join table.');
    } finally {
      isEnqueuingRef.current = false;
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
      {/* Top Header Bar */}
      <header className="lobby-topbar">
        <div className="lobby-brand">
          <div className="lobby-brand-mark">
            <Crown size={18} color="#1a1006" />
          </div>
          <span className="lobby-brand-name">Royal Rummy</span>
        </div>

        <div className="lobby-top-actions">
          <SoundToggle compact />
          {onOpenTutorial && (
            <button
              type="button"
              className="lobby-link-btn"
              onClick={() => {
                soundEngine.play('click');
                onOpenTutorial();
              }}
            >
              How to play
            </button>
          )}

          {isEditingName ? (
            <input
              type="text"
              value={localName}
              onChange={(e) => setLocalName(e.target.value)}
              onBlur={commitDisplayName}
              onKeyDown={(e) => e.key === 'Enter' && commitDisplayName()}
              autoFocus
              maxLength={16}
              aria-label="Your name"
              className="lobby-name-input"
            />
          ) : (
            <button type="button" className="lobby-link-btn" onClick={() => setIsEditingName(true)}>
              {localName || 'Player'}
              <Pencil size={12} strokeWidth={2} />
            </button>
          )}

          <div className="lobby-balance">
            <Coins size={14} />
            <span>₹ {walletBalance.toLocaleString()}</span>
            <button type="button" className="lobby-add-btn" onClick={() => setIsWalletOpen(true)}>
              Add Cash
            </button>
          </div>
        </div>
      </header>

      {/* Main Container */}
      <main
        className="lobby-main"
        style={{
          justifyContent: currentPage === 'SELECT_VARIANT' ? 'center' : 'flex-start',
          paddingBottom: currentPage === 'SELECT_VARIANT' ? '20vh' : 40,
        }}
      >
        {rematchNotice && isMatchmaking && (
          <div
            style={{
              width: '100%',
              maxWidth: 560,
              margin: '0 auto 12px',
              padding: '10px 14px',
              borderRadius: 12,
              background: 'linear-gradient(135deg, rgba(212,175,55,0.22), rgba(15,23,42,0.92))',
              border: '1px solid rgba(212,175,55,0.45)',
              color: '#fef08a',
              fontSize: 13,
              fontWeight: 700,
              textAlign: 'center',
            }}
          >
            Rematch started — same stake &amp; rules. Finding table…
          </div>
        )}
        {/* ========================================================= */}
        {/* PAGE 1: CHOOSE VARIANT HUB                                */}
        {/* ========================================================= */}
        {currentPage === 'SELECT_VARIANT' && (
          <div
            style={{
              width: '100%',
              maxWidth: 980,
              display: 'flex',
              flexDirection: 'column',
              alignItems: 'center',
              gap: 18,
            }}
          >
            {/* Title Section */}
            <div style={{ textAlign: 'center' }}>
              <h1
                style={{
                  fontFamily: 'var(--font-display)',
                  fontSize: 'clamp(24px, 4.5vw, 32px)',
                  fontWeight: 900,
                  lineHeight: 1.2,
                  margin: '0 0 6px 0',
                  color: '#ffffff',
                }}
              >
                Choose Your Rummy Variant
              </h1>
              <p style={{ color: '#cbd5e1', fontSize: 'clamp(13px, 2.5vw, 14px)', margin: 0 }}>
                Pick a format below to select player count (2/6), point value, and entry fee.
              </p>
            </div>

            <div className="variant-cards-row">
              {VARIANT_BANNERS.map((v) => (
                <button
                  key={v.id}
                  id={v.cardId}
                  type="button"
                  className="variant-banner"
                  aria-label={v.label}
                  onClick={() => handleSelectVariant(v.id)}
                >
                  <img src={v.src} alt={v.label} />
                </button>
              ))}
            </div>
          </div>
        )}

        {/* ========================================================= */}
        {/* PAGE 2: TABLE CONFIGURATION & STAKE (MATCHING REAL APP)   */}
        {/* ========================================================= */}
        {currentPage === 'CONFIGURE_TABLE' && (
          <div
            style={{
              width: '100%',
              maxWidth: 720,
              display: 'flex',
              flexDirection: 'column',
              alignItems: 'center',
              gap: 16,
              marginTop: 4,
            }}
          >
            {/* Back to Variants Bar */}
            <div
              style={{
                width: '100%',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'space-between',
              }}
            >
              <button
                type="button"
                id="btn-back-to-variants"
                onClick={() => {
                  soundEngine.play('click');
                  setCurrentPage('SELECT_VARIANT');
                }}
                style={{
                  display: 'inline-flex',
                  alignItems: 'center',
                  gap: 6,
                  padding: '8px 16px',
                  borderRadius: 20,
                  border: '1px solid rgba(212, 175, 55, 0.4)',
                  background: 'rgba(15, 23, 42, 0.7)',
                  color: '#fef08a',
                  fontSize: 13,
                  fontWeight: 800,
                  cursor: 'pointer',
                  transition: 'all 0.2s ease',
                }}
              >
                <ArrowLeft size={16} color="#fbbf24" /> ← Change Variant
              </button>

              <div
                style={{
                  fontSize: 13,
                  fontWeight: 800,
                  color: '#94a3b8',
                  display: 'flex',
                  alignItems: 'center',
                  gap: 6,
                }}
              >
                <span>Step 2 of 2:</span>
                <span style={{ color: '#fff' }}>Configure Stakes</span>
              </div>
            </div>

            {/* Red Casino Felt Box (Tantotant ScreenShot Pramane) */}
            <div
              style={{
                width: '100%',
                background: 'radial-gradient(ellipse at 50% 30%, #7f1d1d 0%, #450a0a 65%, #1c0303 100%)',
                border: '2px solid rgba(212, 175, 55, 0.45)',
                borderRadius: 24,
                padding: '28px 32px',
                display: 'flex',
                flexDirection: 'column',
                alignItems: 'center',
                gap: 22,
                boxShadow: '0 20px 48px rgba(0, 0, 0, 0.65), inset 0 0 50px rgba(0, 0, 0, 0.55)',
                position: 'relative',
              }}
            >
              {/* Variant Emblem Title */}
              <div
                style={{
                  display: 'inline-flex',
                  alignItems: 'center',
                  gap: 10,
                  background: 'rgba(0,0,0,0.55)',
                  border: '1px solid rgba(212,175,55,0.4)',
                  borderRadius: 24,
                  padding: '8px 24px',
                  fontSize: 18,
                  fontWeight: 900,
                  color: '#ffffff',
                  boxShadow: '0 4px 14px rgba(0,0,0,0.4)',
                }}
              >
                <Sparkles size={20} color="#d4af37" />
                <span>{activeVariantTitle}</span>
              </div>

              {/* 1. SELECT PLAYERS: 2 or 6 */}
              <div
                style={{
                  display: 'flex',
                  flexDirection: 'column',
                  alignItems: 'center',
                  gap: 8,
                }}
              >
                <div style={{ fontSize: 13, fontWeight: 700, color: '#fca5a5', letterSpacing: '0.5px' }}>
                  Select Players
                </div>
                <div
                  style={{
                    display: 'inline-flex',
                    background: 'rgba(0, 0, 0, 0.5)',
                    border: '1px solid rgba(255, 255, 255, 0.15)',
                    borderRadius: 12,
                    overflow: 'hidden',
                    boxShadow: '0 4px 12px rgba(0,0,0,0.4)',
                  }}
                >
                  {[2, 6].map((p) => {
                    const isSelected = selectedPlayers === p;
                    return (
                      <button
                        key={p}
                        type="button"
                        id={`btn-select-player-${p}`}
                        onClick={() => {
                          soundEngine.play('click');
                          setSelectedPlayers(p);
                        }}
                        style={{
                          width: 80,
                          padding: '10px 0',
                          border: 'none',
                          background: isSelected
                            ? 'linear-gradient(180deg, #ffffff 0%, #cbd5e1 100%)'
                            : 'transparent',
                          color: isSelected ? '#0f172a' : '#f87171',
                          fontSize: 17,
                          fontWeight: 900,
                          cursor: 'pointer',
                          boxShadow: isSelected
                            ? 'inset 0 1px 0 rgba(255,255,255,0.8), 0 2px 8px rgba(0,0,0,0.4)'
                            : 'none',
                          transition: 'all 0.18s ease',
                        }}
                      >
                        {p}
                      </button>
                    );
                  })}
                </div>
              </div>

              {/* 2. POINT VALUE & ENTRY FEE SLIDER (FOR POINT RUMMY) */}
              {selectedVariant === 'POINTS' && (
                <div
                  style={{
                    width: '100%',
                    display: 'flex',
                    flexDirection: 'column',
                    alignItems: 'center',
                    gap: 18,
                  }}
                >
                  {/* Point Value & Entry Fee Display Boxes */}
                  <div
                    style={{
                      display: 'flex',
                      alignItems: 'center',
                      justifyContent: 'center',
                      gap: 20,
                      width: '100%',
                      flexWrap: 'wrap',
                    }}
                  >
                    {/* Point value Box */}
                    <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                      <span style={{ fontSize: 14, fontWeight: 700, color: '#fecaca' }}>Point value:</span>
                      <div
                        style={{
                          background: 'rgba(0, 0, 0, 0.65)',
                          border: '1px solid rgba(255, 255, 255, 0.2)',
                          borderRadius: 10,
                          padding: '8px 22px',
                          fontSize: 19,
                          fontWeight: 900,
                          color: '#ffffff',
                          minWidth: 70,
                          textAlign: 'center',
                          boxShadow: 'inset 0 2px 6px rgba(0,0,0,0.5)',
                        }}
                      >
                        {POINT_VALUE_TIERS[ptIndex].pt}
                      </div>
                    </div>

                    {/* Entry Fee Box */}
                    <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                      <span style={{ fontSize: 14, fontWeight: 700, color: '#fecaca' }}>Entry Fee:</span>
                      <div
                        style={{
                          background: 'rgba(0, 0, 0, 0.65)',
                          border: '1px solid rgba(212, 175, 55, 0.6)',
                          borderRadius: 10,
                          padding: '8px 22px',
                          fontSize: 19,
                          fontWeight: 900,
                          color: '#fef08a',
                          minWidth: 70,
                          textAlign: 'center',
                          boxShadow: 'inset 0 2px 6px rgba(0,0,0,0.5)',
                        }}
                      >
                        ₹ {POINT_VALUE_TIERS[ptIndex].entry}
                      </div>
                    </div>
                  </div>

                  {/* Stepper with Minus & Plus buttons and Range Slider */}
                  <div
                    style={{
                      width: '100%',
                      maxWidth: 480,
                      display: 'flex',
                      alignItems: 'center',
                      gap: 14,
                    }}
                  >
                    {/* Minus Button */}
                    <button
                      type="button"
                      id="btn-pt-minus"
                      disabled={ptIndex <= 0}
                      onClick={() => {
                        soundEngine.play('click');
                        setPtIndex((i) => Math.max(0, i - 1));
                      }}
                      style={{
                        width: 40,
                        height: 40,
                        borderRadius: '50%',
                        border: 'none',
                        background:
                          ptIndex <= 0
                            ? 'rgba(255,255,255,0.1)'
                            : 'linear-gradient(135deg, #10b981, #059669)',
                        color: '#fff',
                        display: 'flex',
                        alignItems: 'center',
                        justifyContent: 'center',
                        cursor: ptIndex <= 0 ? 'not-allowed' : 'pointer',
                        boxShadow: '0 4px 10px rgba(0,0,0,0.3)',
                        flexShrink: 0,
                      }}
                    >
                      <Minus size={20} strokeWidth={3} />
                    </button>

                    {/* HTML Range Slider */}
                    <div style={{ flex: 1, position: 'relative', display: 'flex', flexDirection: 'column' }}>
                      <input
                        type="range"
                        min={0}
                        max={POINT_VALUE_TIERS.length - 1}
                        step={1}
                        value={ptIndex}
                        onChange={(e) => {
                          soundEngine.play('click');
                          setPtIndex(parseInt(e.target.value, 10));
                        }}
                        className="rummy-slider"
                      />
                      {/* Min / Max Labels directly under slider */}
                      <div
                        style={{
                          display: 'flex',
                          justifyContent: 'space-between',
                          fontSize: 11,
                          fontWeight: 700,
                          color: '#cbd5e1',
                          marginTop: 6,
                        }}
                      >
                        <span>₹0.05 (₹4 Fee)</span>
                        <span>₹5.0 (₹400 Fee)</span>
                      </div>
                    </div>

                    {/* Plus Button */}
                    <button
                      type="button"
                      id="btn-pt-plus"
                      disabled={ptIndex >= POINT_VALUE_TIERS.length - 1}
                      onClick={() => {
                        soundEngine.play('click');
                        setPtIndex((i) => Math.min(POINT_VALUE_TIERS.length - 1, i + 1));
                      }}
                      style={{
                        width: 40,
                        height: 40,
                        borderRadius: '50%',
                        border: 'none',
                        background:
                          ptIndex >= POINT_VALUE_TIERS.length - 1
                            ? 'rgba(255,255,255,0.1)'
                            : 'linear-gradient(135deg, #10b981, #059669)',
                        color: '#fff',
                        display: 'flex',
                        alignItems: 'center',
                        justifyContent: 'center',
                        cursor: ptIndex >= POINT_VALUE_TIERS.length - 1 ? 'not-allowed' : 'pointer',
                        boxShadow: '0 4px 10px rgba(0,0,0,0.3)',
                        flexShrink: 0,
                      }}
                    >
                      <Plus size={20} strokeWidth={3} />
                    </button>
                  </div>
                </div>
              )}

              {/* POOL RUMMY CONFIG */}
              {selectedVariant === 'POOL' && (
                <div
                  style={{
                    width: '100%',
                    display: 'flex',
                    flexDirection: 'column',
                    alignItems: 'center',
                    gap: 14,
                  }}
                >
                  <div
                    style={{
                      display: 'flex',
                      background: 'rgba(0,0,0,0.5)',
                      border: '1px solid rgba(255,255,255,0.15)',
                      borderRadius: 12,
                      overflow: 'hidden',
                    }}
                  >
                    {[
                      { id: 'POOL_101' as const, label: 'Pool 101 (Fast)' },
                      { id: 'POOL_201' as const, label: 'Pool 201 (Marathon)' },
                    ].map((poolOpt) => {
                      const active = poolSubVariant === poolOpt.id;
                      return (
                        <button
                          key={poolOpt.id}
                          type="button"
                          onClick={() => {
                            soundEngine.play('click');
                            setPoolSubVariant(poolOpt.id);
                          }}
                          style={{
                            padding: '8px 20px',
                            border: 'none',
                            background: active
                              ? 'linear-gradient(180deg, #ffffff 0%, #cbd5e1 100%)'
                              : 'transparent',
                            color: active ? '#0f172a' : '#f87171',
                            fontSize: 14,
                            fontWeight: 800,
                            cursor: 'pointer',
                            transition: 'all 0.18s ease',
                          }}
                        >
                          {poolOpt.label}
                        </button>
                      );
                    })}
                  </div>

                  <div style={{ fontSize: 13, fontWeight: 700, color: '#fecaca' }}>Select Entry Fee:</div>
                  <div style={{ display: 'flex', gap: 10, flexWrap: 'wrap', justifyContent: 'center' }}>
                    {POOL_ENTRY_OPTIONS.map((fee) => {
                      const isSelected = poolEntry === fee;
                      return (
                        <button
                          key={fee}
                          type="button"
                          onClick={() => {
                            soundEngine.play('click');
                            setPoolEntry(fee);
                          }}
                          style={{
                            padding: '9px 18px',
                            borderRadius: 10,
                            border: isSelected ? '2px solid #d4af37' : '1px solid rgba(255,255,255,0.15)',
                            background: isSelected
                              ? 'linear-gradient(135deg, #fbbf24 0%, #b45309 100%)'
                              : 'rgba(0, 0, 0, 0.6)',
                            color: isSelected ? '#0f172a' : '#f8fafc',
                            fontSize: 16,
                            fontWeight: 900,
                            cursor: 'pointer',
                            boxShadow: isSelected ? '0 4px 14px rgba(251, 191, 36, 0.4)' : 'none',
                            transition: 'all 0.15s ease',
                          }}
                        >
                          ₹ {fee}
                        </button>
                      );
                    })}
                  </div>

                  <div style={{ fontSize: 13, color: '#fef08a', fontWeight: 700 }}>
                    🏆 Estimated Winner Pool: ₹ {Math.round(poolEntry * selectedPlayers * 0.9)}
                  </div>
                </div>
              )}

              {/* DEALS RUMMY CONFIG */}
              {selectedVariant === 'DEALS' && (
                <div
                  style={{
                    width: '100%',
                    display: 'flex',
                    flexDirection: 'column',
                    alignItems: 'center',
                    gap: 14,
                  }}
                >
                  <div style={{ fontSize: 13, fontWeight: 700, color: '#fecaca' }}>
                    Fixed 2 Deals · Select Entry Fee:
                  </div>
                  <div style={{ display: 'flex', gap: 10, flexWrap: 'wrap', justifyContent: 'center' }}>
                    {DEALS_ENTRY_OPTIONS.map((fee) => {
                      const isSelected = dealsEntry === fee;
                      return (
                        <button
                          key={fee}
                          type="button"
                          onClick={() => {
                            soundEngine.play('click');
                            setDealsEntry(fee);
                          }}
                          style={{
                            padding: '9px 18px',
                            borderRadius: 10,
                            border: isSelected ? '2px solid #d4af37' : '1px solid rgba(255,255,255,0.15)',
                            background: isSelected
                              ? 'linear-gradient(135deg, #10b981 0%, #047857 100%)'
                              : 'rgba(0, 0, 0, 0.6)',
                            color: isSelected ? '#ffffff' : '#f8fafc',
                            fontSize: 16,
                            fontWeight: 900,
                            cursor: 'pointer',
                            boxShadow: isSelected ? '0 4px 14px rgba(16, 185, 129, 0.4)' : 'none',
                            transition: 'all 0.15s ease',
                          }}
                        >
                          ₹ {fee}
                        </button>
                      );
                    })}
                  </div>
                  <div style={{ fontSize: 13, color: '#fef08a', fontWeight: 700 }}>
                    🏆 Winner Takes All: ₹ {Math.round(dealsEntry * selectedPlayers * 0.9)}
                  </div>
                </div>
              )}

              {/* 21-CARD RUMMY CONFIG */}
              {selectedVariant === 'RUMMY_21' && (
                <div
                  style={{
                    width: '100%',
                    display: 'flex',
                    flexDirection: 'column',
                    alignItems: 'center',
                    gap: 14,
                  }}
                >
                  <div style={{ fontSize: 13, fontWeight: 700, color: '#fecaca' }}>
                    3 Decks · 21 Cards · Select Entry Fee:
                  </div>
                  <div style={{ display: 'flex', gap: 10, flexWrap: 'wrap', justifyContent: 'center' }}>
                    {RUMMY_21_ENTRY_OPTIONS.map((fee) => {
                      const isSelected = rummy21Entry === fee;
                      return (
                        <button
                          key={fee}
                          type="button"
                          onClick={() => {
                            soundEngine.play('click');
                            setRummy21Entry(fee);
                          }}
                          style={{
                            padding: '9px 18px',
                            borderRadius: 10,
                            border: isSelected ? '2px solid #d4af37' : '1px solid rgba(255,255,255,0.15)',
                            background: isSelected
                              ? 'linear-gradient(135deg, #ec4899 0%, #be185d 100%)'
                              : 'rgba(0, 0, 0, 0.6)',
                            color: '#ffffff',
                            fontSize: 16,
                            fontWeight: 900,
                            cursor: 'pointer',
                            boxShadow: isSelected ? '0 4px 14px rgba(236, 72, 153, 0.4)' : 'none',
                            transition: 'all 0.15s ease',
                          }}
                        >
                          ₹ {fee}
                        </button>
                      );
                    })}
                  </div>
                </div>
              )}

              {/* Error Message if any */}
              {errorMsg && (
                <div
                  style={{
                    width: '100%',
                    background: 'rgba(0, 0, 0, 0.6)',
                    border: '1px solid rgba(239, 68, 68, 0.6)',
                    borderRadius: 12,
                    padding: '10px 16px',
                    color: '#fca5a5',
                    fontSize: 13,
                    fontWeight: 600,
                    textAlign: 'center',
                  }}
                >
                  {errorMsg}
                </div>
              )}

              {/* Big Golden PLAY NOW button */}
              <button
                type="button"
                id="btn-play-now"
                onClick={() => {
                  soundEngine.unlock();
                  soundEngine.play('click');
                  void handlePlay();
                }}
                disabled={isMatchmaking}
                style={{
                  width: '100%',
                  maxWidth: 360,
                  padding: '16px 28px',
                  borderRadius: 30,
                  border: 'none',
                  background: 'linear-gradient(180deg, #fef08a 0%, #f59e0b 50%, #b45309 100%)',
                  color: '#0f172a',
                  fontSize: 22,
                  fontWeight: 900,
                  letterSpacing: '0.8px',
                  cursor: 'pointer',
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'center',
                  gap: 10,
                  boxShadow:
                    '0 8px 30px rgba(245, 158, 11, 0.4), inset 0 2px 0 rgba(255,255,255,0.8)',
                  transition: 'transform 0.15s ease, box-shadow 0.15s ease',
                }}
              >
                <Play size={24} fill="#0f172a" />
                PLAY NOW
              </button>

              <div style={{ fontSize: 13, color: '#fecaca', textAlign: 'center' }}>
                Table Stake: <strong>₹ {activeEntryFee}</strong> · {selectedPlayers} Players Table
                {activePointValue !== null && ` · ₹${activePointValue}/point`}
              </div>
            </div>

            {/* Quick Rules Footer */}
            <div
              style={{
                width: '100%',
                background: 'rgba(15, 23, 42, 0.75)',
                border: '1px solid rgba(212,175,55,0.25)',
                borderRadius: 16,
                padding: '14px 20px',
                display: 'flex',
                flexDirection: 'column',
                gap: 10,
              }}
            >
              <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 13, fontWeight: 800, color: '#fef08a' }}>
                  <ShieldCheck size={16} color="#10b981" />
                  <span>Official Rules: {activeVariantTitle}</span>
                </div>
                <span style={{ fontSize: 11, color: '#10b981', fontWeight: 700 }}>Server Authoritative</span>
              </div>

              <div
                style={{
                  display: 'grid',
                  gridTemplateColumns: 'repeat(auto-fit, minmax(180px, 1fr))',
                  gap: 10,
                  fontSize: 12,
                  color: '#cbd5e1',
                }}
              >
                <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
                  <Layers size={15} color="#38bdf8" />
                  <span>{selectedVariant === 'RUMMY_21' ? '21 Cards (3 Pure Runs)' : '13 Cards (Min 1 Pure Run)'}</span>
                </div>
                <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
                  <Zap size={15} color="#f59e0b" />
                  <span>
                    {selectedVariant === 'POOL' && poolSubVariant === 'POOL_201'
                      ? 'Drop: 25 1st / 50 Mid'
                      : selectedVariant === 'RUMMY_21'
                      ? 'Drop: 30 1st / 60 Mid'
                      : 'Drop: 20 1st / 40 Mid'}
                  </span>
                </div>
                <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
                  <Trophy size={15} color="#34d399" />
                  <span>
                    {selectedVariant === 'POINTS'
                      ? '1 Deal Fast Settlement'
                      : selectedVariant === 'POOL'
                      ? `Knockout at ${poolSubVariant === 'POOL_101' ? '101' : '201'} pts`
                      : selectedVariant === 'DEALS'
                      ? 'Highest Chips in 2 Deals'
                      : '3 Pure Sequences to Declare'}
                  </span>
                </div>
              </div>
            </div>
          </div>
        )}
      </main>

      {/* Matchmaking Radar Overlay */}
      {isMatchmaking && (
        <div
          style={{
            position: 'fixed',
            inset: 0,
            background: 'radial-gradient(circle at 50% 45%, rgba(120, 53, 15, 0.22) 0%, rgba(10, 15, 26, 0.92) 55%, rgba(3, 7, 18, 0.98) 100%)',
            backdropFilter: 'blur(18px)',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            zIndex: 1000,
            padding: 20,
          }}
        >
          <div
            className="radar-card"
            style={{
              width: '100%',
              maxWidth: 390,
              padding: '36px 30px 30px',
              textAlign: 'center',
              display: 'flex',
              flexDirection: 'column',
              alignItems: 'center',
              gap: 20,
              background: 'linear-gradient(180deg, rgba(26, 34, 52, 0.96) 0%, rgba(13, 18, 30, 0.98) 100%)',
              border: '1.5px solid rgba(212, 175, 55, 0.45)',
              borderRadius: 28,
              boxShadow: '0 25px 60px -10px rgba(0, 0, 0, 0.8), 0 0 50px rgba(212, 175, 55, 0.15)',
            }}
          >
            {/* Animated Radar Visual */}
            <div
              style={{
                width: 130,
                height: 130,
                position: 'relative',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
              }}
            >
              <div
                className="radar-wave-1"
                style={{
                  position: 'absolute',
                  inset: 0,
                  borderRadius: '50%',
                  border: '1.5px solid rgba(212, 175, 55, 0.6)',
                  pointerEvents: 'none',
                }}
              />
              <div
                className="radar-wave-2"
                style={{
                  position: 'absolute',
                  inset: 0,
                  borderRadius: '50%',
                  border: '1.5px solid rgba(212, 175, 55, 0.35)',
                  pointerEvents: 'none',
                }}
              />
              <div
                className="radar-wave-3"
                style={{
                  position: 'absolute',
                  inset: 0,
                  borderRadius: '50%',
                  border: '1.5px solid rgba(16, 185, 129, 0.25)',
                  pointerEvents: 'none',
                }}
              />
              <div
                className="radar-core"
                style={{
                  width: 72,
                  height: 72,
                  borderRadius: '50%',
                  background: 'linear-gradient(135deg, #fbbf24 0%, #d97706 60%, #78350f 100%)',
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'center',
                  boxShadow: '0 0 30px rgba(245, 158, 11, 0.6)',
                  zIndex: 2,
                }}
              >
                <Crown size={32} color="#ffffff" />
              </div>
            </div>

            {/* Info Section */}
            <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 6, width: '100%' }}>
              <div
                style={{
                  fontSize: 11,
                  fontWeight: 800,
                  color: '#fef08a',
                  textTransform: 'uppercase',
                  letterSpacing: '1px',
                  background: 'rgba(212, 175, 55, 0.12)',
                  border: '1px solid rgba(212, 175, 55, 0.3)',
                  borderRadius: 20,
                  padding: '4px 14px',
                }}
              >
                {activeVariantTitle} · {selectedPlayers} Players
              </div>

              <h2
                style={{
                  fontFamily: 'var(--font-display)',
                  fontSize: 24,
                  fontWeight: 900,
                  margin: '4px 0 2px 0',
                  color: '#ffffff',
                  letterSpacing: '0.3px',
                }}
              >
                Finding Table...
              </h2>

              <div
                style={{
                  fontSize: 13,
                  color: '#94a3b8',
                  background: 'rgba(0,0,0,0.35)',
                  border: '1px solid rgba(255,255,255,0.06)',
                  borderRadius: 12,
                  padding: '4px 14px',
                  display: 'inline-flex',
                  alignItems: 'center',
                  gap: 6,
                }}
              >
                <span>Stake:</span>
                <strong style={{ color: '#fef08a' }}>₹ {activeEntryFee}</strong>
                {activePointValue !== null && (
                  <span style={{ color: '#cbd5e1' }}>(₹{activePointValue}/pt)</span>
                )}
              </div>

              <p style={{ fontSize: 12, color: '#94a3b8', margin: '4px 0 0 0', lineHeight: 1.4 }}>
                {mmQueueTime < 15
                  ? 'Searching live opponents in queue...'
                  : 'Starting table with Royal AI partner...'}
              </p>
            </div>

            {/* 15s Timer bar */}
            <div style={{ width: '100%', display: 'flex', flexDirection: 'column', gap: 6 }}>
              <div
                style={{
                  display: 'flex',
                  justifyContent: 'space-between',
                  alignItems: 'center',
                  fontSize: 12,
                  color: '#94a3b8',
                }}
              >
                <span>AI Fallback Guarantee</span>
                <span
                  style={{
                    fontWeight: 800,
                    color: '#fef08a',
                    background: 'rgba(0, 0, 0, 0.4)',
                    border: '1px solid rgba(255, 255, 255, 0.1)',
                    borderRadius: 6,
                    padding: '2px 8px',
                    fontSize: 12,
                  }}
                >
                  {Math.max(0, 15 - mmQueueTime)}s
                </span>
              </div>
              <div
                style={{
                  width: '100%',
                  height: 6,
                  background: 'rgba(255, 255, 255, 0.08)',
                  borderRadius: 6,
                  overflow: 'hidden',
                }}
              >
                <div
                  style={{
                    height: '100%',
                    width: `${Math.min(100, (mmQueueTime / 15) * 100)}%`,
                    background: 'linear-gradient(90deg, #10b981 0%, #d4af37 100%)',
                    boxShadow: '0 0 10px rgba(16, 185, 129, 0.5)',
                    transition: 'width 1s linear',
                  }}
                />
              </div>
            </div>

            {/* Cancel Button */}
            <button
              type="button"
              id="btn-cancel-matchmaking"
              onClick={handleCancel}
              style={{
                background: 'rgba(239, 68, 68, 0.08)',
                border: '1px solid rgba(239, 68, 68, 0.35)',
                color: '#fca5a5',
                borderRadius: 14,
                padding: '10px 22px',
                fontWeight: 700,
                cursor: 'pointer',
                display: 'flex',
                alignItems: 'center',
                gap: 6,
                fontSize: 13,
                transition: 'all 0.2s ease',
              }}
              onMouseEnter={(e) => {
                e.currentTarget.style.background = 'rgba(239, 68, 68, 0.18)';
              }}
              onMouseLeave={(e) => {
                e.currentTarget.style.background = 'rgba(239, 68, 68, 0.08)';
              }}
            >
              <X size={15} /> Cancel Search
            </button>
          </div>
        </div>
      )}

      {/* Wallet Modal */}
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
