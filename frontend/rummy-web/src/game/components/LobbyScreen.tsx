import React, { useState, useEffect, useRef, useCallback } from 'react';
import { useGameStore } from '../store/useGameStore';
import { socketClient } from '../websocket/GameSocketClient';
import { WalletModal } from './WalletModal';
import {
  Coins,
  Play,
  X,
  Minus,
  Plus,
  ArrowLeft,
  Pencil,
  Users,
  UsersRound,
  Target,
} from 'lucide-react';
import { SoundToggle } from './SoundToggle';
import { LandscapeGate } from './LandscapeGate';
import { soundEngine } from '../audio/soundEngine';
import { getApiBaseUrl } from '../utils/apiConfig';
import { tryLockLandscape } from '../hooks/useRequiresLandscape';
import { preloadTableShell } from '../utils/preloadTableShell';
import { PLAYER_CHARACTERS, photoForCharacter } from '../utils/avatarUtils';

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
  { id: 'POINTS', cardId: 'card-select-points', label: 'Point Rummy', src: '/3cc9cf25-042c-4870-9b09-f7f07c2ddc39.jpg' },
  { id: 'POOL', cardId: 'card-select-pool', label: 'Pool Rummy', src: '/7b9b7c96-a115-450d-ae27-568b5b8fbc89.jpg' },
  { id: 'DEALS', cardId: 'card-select-deals', label: 'Deal Rummy', src: '/8a9db3f4-972d-4293-b2df-9c50601211ea.jpg' },
  { id: 'RUMMY_21', cardId: 'card-select-21card', label: '21-Card Rummy', src: '/7ac1f003-0294-4d18-ae39-93b53258c895.jpg' },
];

function StakeStepper({
  value,
  minusId,
  plusId,
  onMinus,
  onPlus,
  minusDisabled,
  plusDisabled,
}: {
  value: React.ReactNode;
  minusId: string;
  plusId: string;
  onMinus: () => void;
  onPlus: () => void;
  minusDisabled: boolean;
  plusDisabled: boolean;
}) {
  return (
    <div className="stake-stepper">
      <button
        type="button"
        id={minusId}
        className="stake-step-btn"
        disabled={minusDisabled}
        onClick={onMinus}
      >
        <Minus size={16} strokeWidth={3.5} />
      </button>
      <div className="stake-step-value">{value}</div>
      <button
        type="button"
        id={plusId}
        className="stake-step-btn"
        disabled={plusDisabled}
        onClick={onPlus}
      >
        <Plus size={16} strokeWidth={3.5} />
      </button>
    </div>
  );
}

export const LobbyScreen: React.FC = () => {
  const {
    displayName,
    setSession,
    setDisplayName,
    avatarId,
    setAvatarId,
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
  const [characterOpen, setCharacterOpen] = useState(false);
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
      const res = await fetch(`${getApiBaseUrl()}/api/wallet/balance?playerId=${playerId}`);
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
    if (!autoMatchmakePending) return;

    // Always read latest config from store (avoid stale Lobby defaults = 2P)
    const cfg = useGameStore.getState().lastGameConfig;
    const maxPlayers =
      cfg?.maxPlayers && cfg.maxPlayers >= 2 ? cfg.maxPlayers : selectedPlayers;

    setAutoMatchmakePending(false);
    setRematchNotice(true);
    if (cfg?.maxPlayers && cfg.maxPlayers >= 2) {
      setSelectedPlayers(cfg.maxPlayers);
    }

    void handlePlay({
      rulesetId: cfg?.rulesetId ?? activeRulesetId,
      entryFee: cfg?.entryFee ?? activeEntryFee,
      maxPlayers,
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps -- fire once when rematch flag flips
  }, [autoMatchmakePending]);

  useEffect(() => {
    if (!isMatchmaking) setRematchNotice(false);
  }, [isMatchmaking]);

  const handleSelectVariant = (variant: VariantType) => {
    soundEngine.play('click');
    setSelectedVariant(variant);
    setCurrentPage('CONFIGURE_TABLE');
    void tryLockLandscape();
  };

  const handlePlay = async (customConfig?: { rulesetId: string; entryFee: number; maxPlayers: number }) => {
    if (isEnqueuingRef.current || isMatchmaking) return;
    void tryLockLandscape();
    isEnqueuingRef.current = true;

    const name = localName.trim() || 'Player';
    setErrorMsg(null);

    const rId = customConfig?.rulesetId ?? activeRulesetId;
    const eFee = customConfig?.entryFee ?? activeEntryFee;
    // Lobby only offers 2P / 6P tables — preserve 6 on rematch
    const rawPlayers = Number(customConfig?.maxPlayers ?? selectedPlayers) || 2;
    const mPlayers = rawPlayers >= 5 ? 6 : 2;

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

    soundEngine.unlock();
    preloadTableShell();
    soundEngine.play('match');

    const enterMatchedTable = (ticketData: {
      matchedTableId: string;
      matchedServerId?: string;
    }) => {
      setIsMatchmaking(false);
      setMmTicketId(null);
      soundEngine.play('deal');
      setSession(ticketData.matchedTableId, playerId, name);
      setHasJoinedTable(true);
      if (ticketData.matchedServerId) {
        try {
          sessionStorage.setItem('rummy_matched_server', ticketData.matchedServerId);
        } catch {
          // ignore
        }
      }
      if (connectionStatus === 'CONNECTED') {
        socketClient.joinTable(0);
      } else {
        socketClient.connect();
      }
    };

    try {
      const res = await fetch(`${getApiBaseUrl()}/api/matchmaking/join`, {
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
      if (data.status === 'MATCHED' && data.matchedTableId) {
        enterMatchedTable(data);
        return;
      }

      setIsMatchmaking(true);
      setMmQueueTime(0);
      setMmTicketId(data.ticketId);

      pollIntervalRef.current = window.setInterval(async () => {
        try {
          const pollRes = await fetch(`${getApiBaseUrl()}/api/matchmaking/ticket/${data.ticketId}`);
          if (!pollRes.ok) return;
          const ticketData = await pollRes.json();
          if (ticketData.status === 'MATCHED' && ticketData.matchedTableId) {
            if (pollIntervalRef.current) {
              clearInterval(pollIntervalRef.current);
              pollIntervalRef.current = null;
            }
            enterMatchedTable(ticketData);
          } else if (ticketData.status === 'CANCELLED' || ticketData.status === 'EXPIRED') {
            if (pollIntervalRef.current) {
              clearInterval(pollIntervalRef.current);
              pollIntervalRef.current = null;
            }
            setIsMatchmaking(false);
            setMmTicketId(null);
            setErrorMsg(
              ticketData.status === 'EXPIRED'
                ? 'No opponents found. Please try again.'
                : 'Matchmaking was cancelled. Please try again.'
            );
          }
        } catch {
          // retry next poll
        }
      }, 1000);
    } catch (err: unknown) {
      setIsMatchmaking(false);
      setMmTicketId(null);
      const raw = err instanceof Error ? err.message : String(err ?? '');
      const isNetwork =
        err instanceof TypeError ||
        /failed to fetch|networkerror|load failed|err_connection|econnrefused/i.test(raw);
      setErrorMsg(
        isNetwork
          ? 'Network error — game server is offline or unreachable. Start the backend and try again.'
          : raw || 'Could not join table.'
      );
    } finally {
      isEnqueuingRef.current = false;
    }
  };

  const handleCancel = async () => {
    if (pollIntervalRef.current) clearInterval(pollIntervalRef.current);
    if (mmTicketId) {
      try {
        await fetch(`${getApiBaseUrl()}/api/matchmaking/cancel/${mmTicketId}`, { method: 'POST' });
      } catch {
        // ignore
      }
    }
    setIsMatchmaking(false);
    setMmTicketId(null);
  };

  return (
    <LandscapeGate enabled={currentPage !== 'SELECT_VARIANT' || isMatchmaking}>
    <div
      className={`lobby-frame${currentPage === 'CONFIGURE_TABLE' ? ' lobby-frame--stake' : ''}`}
      style={{ color: '#f8fafc' }}
    >
      {/* Top Header Bar */}
      <header className="lobby-topbar" style={{ display: currentPage === 'CONFIGURE_TABLE' ? 'none' : undefined }}>
        <div className="lobby-brand" title="Royal Rummy">
          <img
            src="/image.png"
            alt="Royal Rummy"
            className="lobby-brand-logo"
            draggable={false}
          />
        </div>

        <div className="lobby-top-actions">
          <SoundToggle compact />

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

          <button
            type="button"
            className="lobby-character-btn"
            onClick={() => {
              soundEngine.play('click');
              setCharacterOpen(true);
            }}
          >
            <img src={photoForCharacter(avatarId)} alt="" draggable={false} />
            Choose character
          </button>

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
        className={`lobby-main${currentPage === 'SELECT_VARIANT' ? ' lobby-main--variants' : ''}`}
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
          <div className="lobby-variant-hub">
            <div className="lobby-choose-heading">
              <span className="lobby-choose-eyebrow">Select a game</span>
              <h1 className="lobby-choose-title">
                Choose Your <em>Rummy</em>
              </h1>
              <span className="lobby-choose-ornament" aria-hidden>
                <i />
                <span>♠</span>
                <i />
              </span>
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
          <div className="stake-screen">
            <div className="stake-card">
              <span className="stake-suit stake-suit-tl">♠</span>
              <span className="stake-suit stake-suit-tr">♦</span>
              <span className="stake-suit stake-suit-bl">♠</span>
              <span className="stake-suit stake-suit-br">♥</span>

              <div className="stake-nav">
                <button
                  type="button"
                  id="btn-back-to-variants"
                  className="stake-back"
                  onClick={() => {
                    soundEngine.play('click');
                    setCurrentPage('SELECT_VARIANT');
                  }}
                >
                  <span className="stake-back-icon" aria-hidden>
                    <ArrowLeft size={14} strokeWidth={2.75} />
                  </span>
                  Change Variant
                </button>
                <div className="stake-step">
                  Step 2 of 2: <strong>Configure Stakes</strong>
                </div>
              </div>

              <div className="stake-emblem">
                <div className="stake-ribbon">
                  <h2>{activeVariantTitle.toUpperCase()}</h2>
                  <p>PLAY WITH SKILL</p>
                </div>
              </div>

              <div className="stake-players">
                <div className="stake-section-label">
                  <Users size={18} />
                  Select Players
                </div>
                <div className="stake-player-row">
                  <button
                    type="button"
                    id="btn-select-player-2"
                    className={`stake-player-btn${selectedPlayers === 2 ? ' on' : ''}`}
                    onClick={() => {
                      soundEngine.play('click');
                      setSelectedPlayers(2);
                    }}
                  >
                    <Users size={18} />
                    2 Players
                  </button>
                  <button
                    type="button"
                    id="btn-select-player-6"
                    className={`stake-player-btn${selectedPlayers === 6 ? ' on' : ''}`}
                    onClick={() => {
                      soundEngine.play('click');
                      setSelectedPlayers(6);
                    }}
                  >
                    <UsersRound size={18} />
                    6 Players
                  </button>
                </div>
              </div>

              {selectedVariant === 'POINTS' && (
                <>
                  <div className="stake-values">
                    <div className="stake-metric">
                      <div className="stake-metric-head">
                        <div className="stake-metric-title">
                          <Target size={15} />
                          Point Value
                        </div>
                        <div className="stake-metric-sub">Points per game</div>
                      </div>
                      <StakeStepper
                        value={POINT_VALUE_TIERS[ptIndex].pt}
                        minusId="btn-pt-minus"
                        plusId="btn-pt-plus"
                        minusDisabled={ptIndex <= 0}
                        plusDisabled={ptIndex >= POINT_VALUE_TIERS.length - 1}
                        onMinus={() => {
                          soundEngine.play('click');
                          setPtIndex((i) => Math.max(0, i - 1));
                        }}
                        onPlus={() => {
                          soundEngine.play('click');
                          setPtIndex((i) => Math.min(POINT_VALUE_TIERS.length - 1, i + 1));
                        }}
                      />
                    </div>

                    <div className="stake-metric">
                      <div className="stake-metric-head">
                        <div className="stake-metric-title">
                          <Coins size={15} />
                          Entry Fee
                        </div>
                        <div className="stake-metric-sub">Amount to join</div>
                      </div>
                      <StakeStepper
                        value={`₹ ${POINT_VALUE_TIERS[ptIndex].entry}`}
                        minusId="btn-fee-minus"
                        plusId="btn-fee-plus"
                        minusDisabled={ptIndex <= 0}
                        plusDisabled={ptIndex >= POINT_VALUE_TIERS.length - 1}
                        onMinus={() => {
                          soundEngine.play('click');
                          setPtIndex((i) => Math.max(0, i - 1));
                        }}
                        onPlus={() => {
                          soundEngine.play('click');
                          setPtIndex((i) => Math.min(POINT_VALUE_TIERS.length - 1, i + 1));
                        }}
                      />
                    </div>
                  </div>

                  <div className="stake-slider-wrap">
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
                      style={
                        {
                          '--fill': `${(ptIndex / (POINT_VALUE_TIERS.length - 1)) * 100}%`,
                        } as React.CSSProperties
                      }
                    />
                    <div className="stake-range">
                      <span>₹0.05 (₹4 Fee)</span>
                      <span>₹5.0 (₹400 Fee)</span>
                    </div>
                  </div>
                </>
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

              {errorMsg && <div className="stake-error">{errorMsg}</div>}

              <button
                type="button"
                id="btn-play-now"
                className="stake-play-btn"
                onClick={() => {
                  soundEngine.unlock();
                  soundEngine.play('click');
                  void handlePlay();
                }}
                disabled={isMatchmaking}
              >
                <Play size={18} fill="#2a1a06" />
                PLAY NOW
              </button>

              <div className="stake-summary">
                Table Stake: ₹ {activeEntryFee} · {selectedPlayers} Players Table
                {activePointValue !== null && ` · ₹${activePointValue}/point`}
              </div>
            </div>
          </div>
        )}
      </main>

      {/* Matchmaking Overlay — Step 2 stake-card style */}
      {isMatchmaking && (
        <div className="mm-overlay">
          <div className="stake-card mm-card">
            <span className="stake-suit stake-suit-tl">♠</span>
            <span className="stake-suit stake-suit-tr">♦</span>
            <span className="stake-suit stake-suit-bl">♠</span>
            <span className="stake-suit stake-suit-br">♥</span>

            <div className="stake-ribbon mm-ribbon">
              <h2>FINDING TABLE</h2>
              <p>
                {activeVariantTitle.toUpperCase()} · {selectedPlayers} PLAYERS
              </p>
            </div>

            <div className={`mm-search${mmQueueTime < 15 ? '' : ' mm-search--ready'}`} aria-hidden>
              <div className="mm-orbit">
                <span className="mm-orbit-ring" />
                <span className="mm-orbit-sweep" />
                <span className="mm-orbit-core">
                  <Users size={16} />
                </span>
              </div>
              <div className="mm-seats">
                {Array.from({ length: selectedPlayers }, (_, seat) => (
                  <span
                    key={seat}
                    className={`mm-seat${seat === 0 || mmQueueTime >= 15 ? ' on' : ''}${
                      mmQueueTime < 15 && seat === mmQueueTime % selectedPlayers ? ' pulse' : ''
                    }`}
                  />
                ))}
              </div>
            </div>

            <div className="mm-status">
              {mmQueueTime < 15 ? (
                <>
                  Searching for players
                  <span className="mm-dots">
                    <i />
                    <i />
                    <i />
                  </span>
                </>
              ) : (
                'Table matched. Connecting…'
              )}
            </div>

            <div className="stake-values mm-metrics">
              <div className="stake-metric">
                <div className="stake-metric-head">
                  <div className="stake-metric-title">
                    <Coins size={15} />
                    Stake
                  </div>
                  <div className="stake-metric-sub">Entry fee</div>
                </div>
                <div className="mm-metric-value">₹ {activeEntryFee}</div>
              </div>
              <div className="stake-metric">
                <div className="stake-metric-head">
                  <div className="stake-metric-title">
                    <Users size={15} />
                    Table
                  </div>
                  <div className="stake-metric-sub">Seats</div>
                </div>
                <div className="mm-metric-value">{selectedPlayers}P</div>
              </div>
            </div>

            {activePointValue !== null && (
              <div className="stake-summary">Point value: ₹{activePointValue}/point</div>
            )}

            <button type="button" id="btn-cancel-matchmaking" className="mm-cancel-btn" onClick={handleCancel}>
              <X size={16} strokeWidth={2.5} />
              Cancel Search
            </button>
          </div>
        </div>
      )}

      {characterOpen && (
        <div className="character-picker-backdrop" onClick={() => setCharacterOpen(false)}>
          <div
            className="character-picker"
            role="dialog"
            aria-label="Choose character"
            onClick={(e) => e.stopPropagation()}
          >
            <div className="character-picker-head">
              <h2>Choose character</h2>
              <button type="button" className="character-picker-close" onClick={() => setCharacterOpen(false)} aria-label="Close">
                <X size={16} />
              </button>
            </div>
            {(['boy', 'girl'] as const).map((group) => (
              <section key={group} className="character-picker-group">
                <h3>{group === 'boy' ? 'Boys' : 'Girls'}</h3>
                <div className="character-picker-grid">
                  {PLAYER_CHARACTERS.filter((c) => c.group === group).map((c) => (
                    <button
                      key={c.id}
                      type="button"
                      className={`character-picker-face${c.id === avatarId ? ' on' : ''}`}
                      aria-label={c.id}
                      aria-pressed={c.id === avatarId}
                      onClick={() => {
                        soundEngine.play('select');
                        setAvatarId(c.id);
                        setCharacterOpen(false);
                      }}
                    >
                      <img src={c.photo} alt="" draggable={false} />
                    </button>
                  ))}
                </div>
              </section>
            ))}
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
    </LandscapeGate>
  );
};
