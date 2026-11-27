import React, { useState, useEffect, useRef, useCallback } from 'react';
import { useGameStore } from '../store/useGameStore';
import { socketClient } from '../websocket/GameSocketClient';
import { InsufficientBalanceModal, type InsufficientBalanceInfo } from './InsufficientBalanceModal';
import { PrivateRoomLobby } from './PrivateRoomLobby';
import { HistoryPage, type HistoryVariantFilter } from './HistoryPage';
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
  Sparkles,
  RotateCw,
  Menu,
  History,
  Volume2,
  VolumeX,
  Maximize2,
  Minimize2,
  Home,
  LogOut,
} from 'lucide-react';
import { SoundToggle } from './SoundToggle';
import { FullscreenToggle, toggleFullscreen } from './FullscreenToggle';
import { LandscapeGate, useLandscapeGate } from './LandscapeGate';
import { soundEngine } from '../audio/soundEngine';
import { getApiBaseUrl } from '../utils/apiConfig';
import { authFetch, exitToOperatorLobby, formatCurrencySymbol } from '../utils/authClient';
import { tryLockLandscape } from '../hooks/useRequiresLandscape';
import { preloadTableShell } from '../utils/preloadTableShell';
import { LOBBY_VARIANT_ART } from '../utils/preloadLobbyArt';
import { PLAYER_CHARACTERS, photoForCharacter } from '../utils/avatarUtils';

export type VariantType = 'POINTS' | 'POOL' | 'DEALS' | 'RUMMY_21';

/** Matches the server's StakeTiers: 2 Deals tables seat 2 players only. */
const isTwoPlayerOnly = (rulesetId: string) => rulesetId === 'DEALS_2' || rulesetId === 'DEALS_RUMMY';

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
  { id: 'POOL', cardId: 'card-select-pool', label: 'Pool Rummy', src: LOBBY_VARIANT_ART.POOL },
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

const StakeNavRight: React.FC = () => {
  const { simulatedLandscape, toggleFlip } = useLandscapeGate();
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: '6px' }}>
      <div className="stake-step">
        Step 2 of 2: <strong>Configure Stakes</strong>
      </div>
      {simulatedLandscape && (
        <button
          type="button"
          onClick={() => {
            soundEngine.play('click');
            toggleFlip();
          }}
          className="board-hud-flip-btn"
          style={{ width: '28px', height: '28px', borderRadius: '6px', flexShrink: 0 }}
          title="Flip orientation 180°"
          aria-label="Flip screen 180 degrees"
        >
          <RotateCw size={13} />
        </button>
      )}
      <SoundToggle compact />
      <FullscreenToggle compact />
    </div>
  );
};

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
    autoMatchmakePending,
    setAutoMatchmakePending,
    setLastGameConfig,
    walletBalance,
    setWalletBalance,
    currency,
    closeUrl,
    setLaunchMeta,
    setSessionInvalidInfo,
  } = useGameStore();

  const [localName, setLocalName] = useState(displayName);
  const [isEditingName, setIsEditingName] = useState(false);
  const [characterOpen, setCharacterOpen] = useState(false);
  const [insufficientBalance, setInsufficientBalance] = useState<InsufficientBalanceInfo | null>(null);
  const [rematchNotice, setRematchNotice] = useState(false);

  const commitDisplayName = () => {
    const next = localName.trim() || 'Player';
    setLocalName(next);
    setDisplayName(next);
    setIsEditingName(false);
  };

  // 3-PAGE FLOW: Page 1 = 'SELECT_VARIANT', Page 2 = 'CONFIGURE_TABLE', Page 3 = 'HISTORY'
  const [currentPage, setCurrentPage] = useState<'SELECT_VARIANT' | 'CONFIGURE_TABLE' | 'HISTORY'>('SELECT_VARIANT');
  const [menuOpen, setMenuOpen] = useState(false);
  const [soundMuted, setSoundMuted] = useState(() => soundEngine.isMuted());
  const [isFullscreen, setIsFullscreen] = useState(
    () => typeof document !== 'undefined' && Boolean(document.fullscreenElement)
  );

  useEffect(() => {
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        setMenuOpen(false);
      }
    };
    const onFsChange = () => {
      setIsFullscreen(Boolean(document.fullscreenElement));
    };
    window.addEventListener('keydown', onKeyDown);
    document.addEventListener('fullscreenchange', onFsChange);
    return () => {
      window.removeEventListener('keydown', onKeyDown);
      document.removeEventListener('fullscreenchange', onFsChange);
    };
  }, []);

  // ── LOBBY TABS: Cash Games vs Private Room ───────────────────
  const [lobbyTab, setLobbyTab] = useState<'CASH' | 'PRIVATE'>(() => {
    try {
      if (sessionStorage.getItem('rummy_open_private_tab') === '1') {
        sessionStorage.removeItem('rummy_open_private_tab');
        return 'PRIVATE';
      }
    } catch { /* ignore */ }
    return 'CASH';
  });
  const [activePrivateRoomCode, setActivePrivateRoomCode] = useState<string | null>(null);
  const inWaitingLobby =
    lobbyTab === 'PRIVATE' && activePrivateRoomCode !== null;

  // Variant & Mode selection
  const [selectedVariant, setSelectedVariant] = useState<VariantType>('POOL');
  const [selectedPlayers, setSelectedPlayers] = useState<number>(2);

  // Points Rummy Config
  const [ptIndex, setPtIndex] = useState<number>(1); // default 0.1 pt -> ₹8 entry fee

  // Pool Rummy Config
  const [poolSubVariant, setPoolSubVariant] = useState<'POOL_101' | 'POOL_201'>('POOL_101');
  const [poolEntry, setPoolEntry] = useState<number>(25);

  // Deals Rummy Config
  const [dealsSubVariant, setDealsSubVariant] = useState<'DEALS_2' | 'DEALS_3'>('DEALS_2');
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
    activeRulesetId = dealsSubVariant;
    activePointValue = null;
    activeEntryFee = dealsEntry;
    activeVariantTitle = dealsSubVariant === 'DEALS_2' ? '2 Deals Rummy' : '3 Deals Rummy';
  } else {
    activeRulesetId = 'RUMMY_21';
    activePointValue = null;
    activeEntryFee = rummy21Entry;
    activeVariantTitle = '21-Card Marriage Rummy';
  }

  // Best of 2 deals is heads-up only; 6-seat deals tables play 3 deals.
  const twoPlayerOnly = isTwoPlayerOnly(activeRulesetId);
  const tablePlayers = twoPlayerOnly ? 2 : selectedPlayers;

  const fetchBalance = useCallback(async () => {
    try {
      const res = await authFetch(`${getApiBaseUrl()}/api/wallet/balance?playerId=${playerId}`);
      if (res.ok) {
        const data = await res.json();
        setWalletBalance(Number(data.balance ?? 0));
        setLaunchMeta({
          currency: data.currency,
          lang: data.lang,
          closeUrl: data.closeUrl,
        });
        if (data.sessionInvalid) {
          setSessionInvalidInfo({
            message: 'Your game session has expired. Please return to the lobby and launch again.',
            errorCode: data.sessionErrorCode,
            closeUrl: data.closeUrl,
          });
        }
      }
    } catch {
      // Backend offline fallback
    }
  }, [playerId, setLaunchMeta, setSessionInvalidInfo, setWalletBalance]);

  useEffect(() => {
    fetchBalance();
    const t1 = window.setTimeout(fetchBalance, 1500);
    const t2 = window.setTimeout(fetchBalance, 4000);
    const interval = setInterval(fetchBalance, 10000);
    const onFocusOrVisible = () => {
      if (document.visibilityState === 'visible') {
        void fetchBalance();
      }
    };
    window.addEventListener('focus', onFocusOrVisible);
    document.addEventListener('visibilitychange', onFocusOrVisible);
    return () => {
      window.clearTimeout(t1);
      window.clearTimeout(t2);
      clearInterval(interval);
      window.removeEventListener('focus', onFocusOrVisible);
      document.removeEventListener('visibilitychange', onFocusOrVisible);
    };
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
    const rawPlayers = Number(customConfig?.maxPlayers ?? tablePlayers) || 2;
    const mPlayers = rawPlayers >= 5 && !isTwoPlayerOnly(rId) ? 6 : 2;

    setLastGameConfig({
      rulesetId: rId,
      entryFee: eFee,
      maxPlayers: mPlayers,
    });

    // Ensure fresh live balance before checking
    let currentBal = useGameStore.getState().walletBalance;
    try {
      const res = await authFetch(`${getApiBaseUrl()}/api/wallet/balance?playerId=${playerId}`);
      if (res.ok) {
        const data = await res.json();
        const fresh = Number(data.balance ?? 0);
        setWalletBalance(fresh);
        currentBal = fresh;
      }
    } catch {
      // Backend offline or network error fallback to currentBal
    }

    if (currentBal !== null && currentBal < eFee) {
      setInsufficientBalance({
        requiredAmount: eFee,
        currentBalance: currentBal,
        contextLabel: 'join this table',
      });
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
      const joinBody = JSON.stringify({
        playerId,
        playerName: name,
        avatarId,
        rulesetId: rId,
        stakeTier: eFee,
        maxPlayers: mPlayers,
        allowAiFallback: true,
      });
      // 503 = the node that answered is restarting; the load balancer routes the retry to a healthy one.
      let res: Response | null = null;
      for (let attempt = 0; attempt < 3; attempt++) {
        res = await authFetch(`${getApiBaseUrl()}/api/matchmaking/join`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: joinBody,
        });
        if (res.status !== 503) break;
        await new Promise((resolve) => setTimeout(resolve, 1500 * (attempt + 1)));
      }
      if (!res) throw new Error('Could not start matchmaking');

      if (!res.ok) {
        const errData = await res.json().catch(() => ({}));
        if (errData.error === 'INSUFFICIENT_BALANCE') {
          setInsufficientBalance({
            requiredAmount: typeof errData.requiredStake === 'number' ? errData.requiredStake : eFee,
            currentBalance: typeof errData.currentBalance === 'number' ? errData.currentBalance : currentBal,
            contextLabel: 'join this table',
          });
          return;
        }
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
          const pollRes = await authFetch(`${getApiBaseUrl()}/api/matchmaking/ticket/${data.ticketId}`);
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
        await authFetch(`${getApiBaseUrl()}/api/matchmaking/cancel/${mmTicketId}`, { method: 'POST' });
      } catch {
        // ignore
      }
    }
    setIsMatchmaking(false);
    setMmTicketId(null);
  };

  return (
    <LandscapeGate
      enabled={currentPage === 'CONFIGURE_TABLE' || isMatchmaking || inWaitingLobby}
      hideFloatingBadge={currentPage === 'CONFIGURE_TABLE' || inWaitingLobby}
    >
    <div
      className={`lobby-frame${currentPage === 'CONFIGURE_TABLE' ? ' lobby-frame--stake' : inWaitingLobby || currentPage === 'HISTORY' ? ' lobby-frame--waiting' : ''}`}
      style={{ color: '#f8fafc' }}
    >
      {/* Top Header Bar */}
      <header className="lobby-topbar" style={{ display: currentPage === 'CONFIGURE_TABLE' || inWaitingLobby ? 'none' : undefined }}>
        <div
          className="lobby-brand"
          title="Royal Rummy"
          style={{ cursor: currentPage === 'HISTORY' ? 'pointer' : undefined }}
          onClick={() => {
            if (currentPage === 'HISTORY') {
              soundEngine.play('click');
              setCurrentPage('SELECT_VARIANT');
            }
          }}
        >
          <img
            src="/image.webp"
            alt="Royal Rummy"
            className="lobby-brand-logo"
            draggable={false}
          />
        </div>

        <div className="lobby-top-actions">
          <div className="lobby-balance" title="Available Balance">
            <Coins size={14} />
            <span>{formatCurrencySymbol(currency)} {(walletBalance ?? 0).toLocaleString()}</span>
          </div>

          {closeUrl && (
            <button
              type="button"
              id="btn-lobby-exit"
              className="lobby-icon-btn"
              title="Exit to Lobby"
              aria-label="Exit to Lobby"
              onClick={() => {
                soundEngine.play('click');
                exitToOperatorLobby(closeUrl);
              }}
            >
              <LogOut size={18} strokeWidth={2.2} />
            </button>
          )}

          <FullscreenToggle
            id="btn-lobby-fullscreen"
            className="lobby-icon-btn"
            strokeWidth={2.2}
          />

          <button
            type="button"
            id="btn-lobby-menu"
            className={`lobby-hamburger-btn${menuOpen ? ' open' : ''}`}
            aria-label="Open menu"
            aria-expanded={menuOpen}
            onClick={() => {
              soundEngine.play('click');
              setSoundMuted(soundEngine.isMuted());
              setMenuOpen((prev) => !prev);
            }}
          >
            <Menu size={19} strokeWidth={2.2} />
          </button>
        </div>
      </header>

      {/* Main Container */}
      <main
        className={`lobby-main${currentPage === 'SELECT_VARIANT' && !inWaitingLobby ? ' lobby-main--variants' : currentPage === 'HISTORY' ? ' lobby-main--history' : ''}`}
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
            {/* ── Back button when inside Private Room ── */}
            {lobbyTab !== 'CASH' && !inWaitingLobby && (
              <div className="lobby-subview-nav">
                <button
                  type="button"
                  className="stake-back"
                  onClick={() => {
                    soundEngine.play('click');
                    setLobbyTab('CASH');
                  }}
                >
                  <span className="stake-back-icon" aria-hidden>
                    <ArrowLeft size={14} strokeWidth={2.75} />
                  </span>
                  Back to Lobby
                </button>
              </div>
            )}

            {/* ── Main Hub: Cash Game + Private Room cards ── */}
            {lobbyTab === 'CASH' && (
              <>
                <div className="lobby-choose-heading">
                  <span className="lobby-choose-eyebrow">Select a mode</span>
                  <h1 className="lobby-choose-title">
                    Play <em>Pool Rummy</em>
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
                      <img src={v.src} alt={v.label} decoding="async" />
                    </button>
                  ))}

                  <button
                    type="button"
                    id="card-select-private-room"
                    className="variant-banner"
                    aria-label="Private Room"
                    onClick={() => {
                      soundEngine.play('click');
                      setLobbyTab('PRIVATE');
                    }}
                  >
                    <img src="/mode-private-room.webp" alt="Private Room" decoding="async" />
                  </button>
                </div>
              </>
            )}

            {/* ── Private Room tab ── */}
            {lobbyTab === 'PRIVATE' && (
              <PrivateRoomLobby
                playerId={playerId ?? undefined}
                activeRoomCode={activePrivateRoomCode}
                onSelectRoomCode={setActivePrivateRoomCode}
              />
            )}
          </div>
        )}

        {/* ========================================================= */}
        {/* PAGE 2: TABLE CONFIGURATION & STAKE (MATCHING REAL APP)   */}
        {/* ========================================================= */}
        {currentPage === 'CONFIGURE_TABLE' && (
          <div className="stake-screen">
            {/* Desktop Navigation: Positioned outside the popup card */}
            <div className="stake-nav stake-nav--desktop">
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
                Back to Lobby
              </button>
              <StakeNavRight />
            </div>

            <div className="stake-card">
              <span className="stake-suit stake-suit-tl">♠</span>
              <span className="stake-suit stake-suit-tr">♦</span>
              <span className="stake-suit stake-suit-bl">♠</span>
              <span className="stake-suit stake-suit-br">♥</span>

              {/* Mobile Navigation: Remains inside the full-screen card on mobile */}
              <div className="stake-nav stake-nav--mobile">
                <button
                  type="button"
                  id="btn-back-to-variants-mobile"
                  className="stake-back"
                  onClick={() => {
                    soundEngine.play('click');
                    setCurrentPage('SELECT_VARIANT');
                  }}
                >
                  <span className="stake-back-icon" aria-hidden>
                    <ArrowLeft size={14} strokeWidth={2.75} />
                  </span>
                  Back to Lobby
                </button>
                <StakeNavRight />
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
                    className={`stake-player-btn${tablePlayers === 2 ? ' on' : ''}`}
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
                    className={`stake-player-btn${tablePlayers === 6 ? ' on' : ''}`}
                    disabled={twoPlayerOnly}
                    title={twoPlayerOnly ? '2 Deals is played by 2 players. Choose 3 Deals for a 6-player table.' : undefined}
                    style={twoPlayerOnly ? { opacity: 0.4, cursor: 'not-allowed' } : undefined}
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
                      <span>₹0.10 (₹8 Fee)</span>
                      <span>₹5.0 (₹400 Fee)</span>
                    </div>
                  </div>
                  <div className="stake-winnings-hint" style={{ fontSize: 13, color: '#fef08a', fontWeight: 700, marginTop: 10, textAlign: 'center' }}>
                    🏆 Max Winnings: up to ₹ {Math.round((tablePlayers - 1) * POINT_VALUE_TIERS[ptIndex].entry * 0.85)} (80 pts cap)
                  </div>
                </>
              )}

              {/* POOL RUMMY CONFIG */}
              {selectedVariant === 'POOL' && (
                <div
                  className="stake-variant-config"
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
                            background: active ? '#f1f5f9' : 'transparent',
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
                            border: isSelected ? '2px solid #f5d061' : '1px solid rgba(255,255,255,0.15)',
                            background: isSelected ? '#f0be2e' : 'rgba(0, 0, 0, 0.6)',
                            color: isSelected ? '#0f172a' : '#f8fafc',
                            fontSize: 16,
                            fontWeight: 900,
                            cursor: 'pointer',
                            transition: 'all 0.15s ease',
                          }}
                        >
                          ₹ {fee}
                        </button>
                      );
                    })}
                  </div>

                  <div className="stake-winnings-hint" style={{ fontSize: 13, color: '#fef08a', fontWeight: 700 }}>
                    🏆 Estimated Winner Pool: ₹ {Math.round(poolEntry * tablePlayers * 0.85)}
                  </div>
                </div>
              )}

              {/* DEALS RUMMY CONFIG */}
              {selectedVariant === 'DEALS' && (
                <div
                  className="stake-variant-config"
                  style={{
                    width: '100%',
                    display: 'flex',
                    flexDirection: 'column',
                    alignItems: 'center',
                    gap: 14,
                  }}
                >
                  {/* Deals Count Switcher (2 Deals vs 3 Deals) */}
                  <div
                    style={{
                      display: 'flex',
                      background: 'rgba(0, 0, 0, 0.45)',
                      padding: 4,
                      borderRadius: 12,
                      border: '1px solid rgba(255, 255, 255, 0.1)',
                      gap: 4,
                    }}
                  >
                    {[
                      { id: 'DEALS_2' as const, label: '2 Deals (160 Chips)' },
                      { id: 'DEALS_3' as const, label: '3 Deals (240 Chips)' },
                    ].map((opt) => {
                      const active = dealsSubVariant === opt.id;
                      return (
                        <button
                          key={opt.id}
                          type="button"
                          onClick={() => {
                            soundEngine.play('click');
                            setDealsSubVariant(opt.id);
                          }}
                          style={{
                            padding: '8px 20px',
                            border: 'none',
                            background: active ? '#f1f5f9' : 'transparent',
                            color: active ? '#0f172a' : '#f87171',
                            fontSize: 14,
                            fontWeight: 800,
                            cursor: 'pointer',
                            transition: 'all 0.18s ease',
                            borderRadius: 8,
                          }}
                        >
                          {opt.label}
                        </button>
                      );
                    })}
                  </div>

                  <div style={{ fontSize: 13, fontWeight: 700, color: '#fecaca' }}>
                    Select Entry Fee:
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
                            background: isSelected ? '#059669' : 'rgba(0, 0, 0, 0.6)',
                            color: isSelected ? '#ffffff' : '#f8fafc',
                            fontSize: 16,
                            fontWeight: 900,
                            cursor: 'pointer',
                            transition: 'all 0.15s ease',
                          }}
                        >
                          ₹ {fee}
                        </button>
                      );
                    })}
                  </div>
                  <div className="stake-winnings-hint" style={{ fontSize: 13, color: '#fef08a', fontWeight: 700 }}>
                    🏆 Winner Takes All: ₹ {Math.round(dealsEntry * tablePlayers * 0.85)}
                  </div>
                </div>
              )}

              {/* 21-CARD RUMMY CONFIG */}
              {selectedVariant === 'RUMMY_21' && (
                <div
                  className="stake-variant-config"
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
                            background: isSelected ? '#be185d' : 'rgba(0, 0, 0, 0.6)',
                            color: '#ffffff',
                            fontSize: 16,
                            fontWeight: 900,
                            cursor: 'pointer',
                            transition: 'all 0.15s ease',
                          }}
                        >
                          ₹ {fee}
                        </button>
                      );
                    })}
                  </div>
                  <span className="stake-winnings-hint" style={{ fontSize: 11, color: '#86efac', fontWeight: 700 }}>
                    Max Win: ~₹{(rummy21Entry * 0.85).toFixed(2)} (after 15% platform fee) • 120 Pts Cap
                  </span>
                </div>
              )}

              {errorMsg && <div className="stake-error">{errorMsg}</div>}

              <button
                type="button"
                id="btn-play-now"
                className="stake-play-btn"
                onClick={() => {
                  soundEngine.unlock();
                  soundEngine.playOldClick();
                  void handlePlay();
                }}
                disabled={isMatchmaking}
              >
                <Play size={18} fill="#2a1a06" />
                PLAY NOW
              </button>

              <div className="stake-summary">
                Table Stake: ₹ {activeEntryFee} · {tablePlayers} Players Table
                {activePointValue !== null && ` · ₹${activePointValue}/point`}
              </div>
            </div>
          </div>
        )}

        {currentPage === 'HISTORY' && (
          <HistoryPage
            onBack={() => setCurrentPage('SELECT_VARIANT')}
            allowedRulesets={['POOL_101', 'POOL_201']}
            showTournaments={false}
            variantFilters={([
              { id: 'ALL', label: 'All', rulesets: [] },
              { id: 'POOL_101', label: '101 Pool', rulesets: ['POOL_101'] },
              { id: 'POOL_201', label: '201 Pool', rulesets: ['POOL_201'] },
            ] satisfies HistoryVariantFilter[])}
          />
        )}
      </main>

      {/* Matchmaking Overlay — Step 2 stake-card style */}
      {isMatchmaking && (
        <div className="mm-overlay">
          <div
            style={{
              position: 'absolute',
              top: '16px',
              right: '20px',
              display: 'flex',
              alignItems: 'center',
              gap: '8px',
              zIndex: 30,
            }}
          >
            <SoundToggle compact />
            <FullscreenToggle compact />
          </div>
          <div className="stake-card mm-card">
            <span className="stake-suit stake-suit-tl">♠</span>
            <span className="stake-suit stake-suit-tr">♦</span>
            <span className="stake-suit stake-suit-bl">♠</span>
            <span className="stake-suit stake-suit-br">♥</span>

            <div className="stake-ribbon mm-ribbon">
              <h2>FINDING TABLE</h2>
              <p>
                {activeVariantTitle.toUpperCase()} · {tablePlayers} PLAYERS
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
                {Array.from({ length: tablePlayers }, (_, seat) => (
                  <span
                    key={seat}
                    className={`mm-seat${seat === 0 || mmQueueTime >= 15 ? ' on' : ''}${
                      mmQueueTime < 15 && seat === mmQueueTime % tablePlayers ? ' pulse' : ''
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
                <div className="mm-metric-value">{tablePlayers}P</div>
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

      {/* Floating Character Selection Button (Chatbot Widget Style) */}
      {currentPage === 'SELECT_VARIANT' && !isMatchmaking && !inWaitingLobby && (
        <button
          type="button"
          id="btn-floating-character"
          className="lobby-character-fab"
          onClick={() => {
            soundEngine.play('click');
            setCharacterOpen(true);
          }}
          title="Choose character"
          aria-label="Choose character"
        >
          <div className="character-fab-avatar-wrap">
            <img
              src={photoForCharacter(avatarId)}
              alt="Character"
              className="character-fab-avatar"
              draggable={false}
            />
            <div className="character-fab-badge" aria-hidden="true">
              <Sparkles size={10} strokeWidth={2.5} />
            </div>
          </div>
          <span className="character-fab-label">Choose character</span>
        </button>
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

      {/* Insufficient Balance Modal */}
      <InsufficientBalanceModal
        info={insufficientBalance}
        onClose={() => setInsufficientBalance(null)}
      />

      {/* Slide-out Header Hamburger Menu */}
      {menuOpen && (
        <div
          className="lobby-drawer-backdrop"
          onClick={() => setMenuOpen(false)}
        >
          <aside
            className="lobby-drawer"
            role="dialog"
            aria-modal="true"
            aria-label="Menu"
            onClick={(e) => e.stopPropagation()}
          >
            <div className="lobby-drawer-head">
              <div className="lobby-drawer-profile">
                <img
                  src={photoForCharacter(avatarId)}
                  alt=""
                  className="lobby-drawer-avatar"
                  draggable={false}
                />
                <div className="lobby-drawer-name-wrap">
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
                    <button
                      type="button"
                      className="lobby-drawer-name-btn"
                      onClick={() => {
                        soundEngine.play('click');
                        setIsEditingName(true);
                      }}
                    >
                      <span>{localName || 'Player'}</span>
                      <Pencil size={13} strokeWidth={2} />
                    </button>
                  )}
                </div>
              </div>
              <button
                type="button"
                className="lobby-drawer-close"
                onClick={() => {
                  soundEngine.play('click');
                  setMenuOpen(false);
                }}
                aria-label="Close menu"
              >
                <X size={18} />
              </button>
            </div>

            <nav className="lobby-drawer-nav" aria-label="Primary navigation">
              <button
                type="button"
                id="btn-menu-lobby"
                className={`lobby-drawer-item${currentPage === 'SELECT_VARIANT' ? ' active' : ''}`}
                onClick={() => {
                  soundEngine.play('click');
                  setCurrentPage('SELECT_VARIANT');
                  setLobbyTab('CASH');
                  setMenuOpen(false);
                }}
              >
                <Home size={16} />
                <span>Lobby</span>
              </button>

              <button
                type="button"
                id="btn-menu-history"
                className={`lobby-drawer-item${currentPage === 'HISTORY' ? ' active' : ''}`}
                onClick={() => {
                  soundEngine.play('click');
                  setCurrentPage('HISTORY');
                  setMenuOpen(false);
                }}
              >
                <History size={16} />
                <span>History</span>
              </button>

              <button
                type="button"
                id="btn-menu-character"
                className="lobby-drawer-item"
                onClick={() => {
                  soundEngine.play('click');
                  setMenuOpen(false);
                  setCharacterOpen(true);
                }}
              >
                <UsersRound size={16} />
                <span>Choose Character</span>
              </button>

            </nav>

            <div className="lobby-drawer-controls">
              <button
                type="button"
                id="btn-menu-sound"
                className="lobby-drawer-item"
                onClick={() => {
                  soundEngine.unlock();
                  setSoundMuted(soundEngine.toggleMute());
                }}
              >
                {soundMuted ? <VolumeX size={16} /> : <Volume2 size={16} />}
                <span>Sound</span>
                <span className="lobby-drawer-pill">{soundMuted ? 'Muted' : 'On'}</span>
              </button>

              <button
                type="button"
                id="btn-menu-fullscreen"
                className="lobby-drawer-item"
                onClick={() => {
                  soundEngine.play('click');
                  void toggleFullscreen().then(setIsFullscreen);
                }}
              >
                {isFullscreen ? <Minimize2 size={16} /> : <Maximize2 size={16} />}
                <span>Fullscreen</span>
                <span className="lobby-drawer-pill">{isFullscreen ? 'On' : 'Off'}</span>
              </button>

              {closeUrl && (
                <button
                  type="button"
                  id="btn-menu-exit"
                  className="lobby-drawer-item"
                  onClick={() => {
                    soundEngine.play('click');
                    exitToOperatorLobby(closeUrl);
                  }}
                >
                  <LogOut size={16} />
                  <span>Exit to Lobby</span>
                </button>
              )}
            </div>
          </aside>
        </div>
      )}
    </div>
    </LandscapeGate>
  );
};
