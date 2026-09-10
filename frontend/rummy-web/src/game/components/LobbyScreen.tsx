import React, { useState, useEffect, useRef, useCallback } from 'react';
import { useGameStore } from '../store/useGameStore';
import { socketClient } from '../websocket/GameSocketClient';
import { HistoryModal } from './HistoryModal';
import { WalletModal } from './WalletModal';
import { AdminDashboardModal } from './AdminDashboardModal';
import { ResponsibleGamblingModal } from './ResponsibleGamblingModal';
import {
  Crown,
  Users,
  Coins,
  ShieldCheck,
  Activity,
  Trophy,
  Zap,
  Loader2,
  X,
  Play,
  Flame,
  UserCheck,
  PlusCircle,
  AlertTriangle,
  Radio,
  Layers,
} from 'lucide-react';

export interface StakeTier {
  id: string;
  name: string;
  tag?: string;
  pointValue: string;
  minEntry: number;
  stake: number;
  prizePool?: string;
  activePlayers: number;
  featured?: boolean;
}

export interface GameVariantConfig {
  id: string;
  rulesetId: string;
  label: string;
  badge: string;
  desc: string;
  cardsDealt: number;
  decks: string;
  maxPlayersAllowed?: number; // e.g. Gin Rummy is 2 players only
}

export const ALL_VARIANTS: GameVariantConfig[] = [
  {
    id: 'INDIAN_POINTS',
    rulesetId: 'POINTS_13',
    label: 'Points Rummy',
    badge: '🔥 HOT',
    desc: '13 Cards • 1 Deal Fast Action • Standard Points System',
    cardsDealt: 13,
    decks: '2 Decks',
  },
  {
    id: 'POOL_101',
    rulesetId: 'POOL_101',
    label: 'Pool 101',
    badge: 'POPULAR',
    desc: '13 Cards • 101 Pts Elimination • Last Standing Wins',
    cardsDealt: 13,
    decks: '2 Decks',
  },
  {
    id: 'POOL_201',
    rulesetId: 'POOL_201',
    label: 'Pool 201',
    badge: 'MARATHON',
    desc: '13 Cards • 201 Pts Elimination • Tournament Endurance',
    cardsDealt: 13,
    decks: '2 Decks',
  },
  {
    id: 'DEALS_2',
    rulesetId: 'DEALS_RUMMY',
    label: 'Deals Rummy',
    badge: '2 DEALS',
    desc: '13 Cards • Fixed 2 Deals • Highest Cumulative Chips Wins',
    cardsDealt: 13,
    decks: '2 Decks',
  },
  {
    id: 'RUMMY_21',
    rulesetId: 'RUMMY_21',
    label: '21-Card Rummy',
    badge: '🃏 21 CARDS',
    desc: '21 Cards • 3 Decks • Dublees, Marriage & 3 Pure Sequences',
    cardsDealt: 21,
    decks: '3 Decks',
  },
  {
    id: 'GIN_RUMMY',
    rulesetId: 'GIN_RUMMY',
    label: 'Gin Rummy',
    badge: '🍸 2 PLAYERS',
    desc: '10 Cards • Western 2-Player Classic • Knocking & Big Gin',
    cardsDealt: 10,
    decks: '1 Deck',
    maxPlayersAllowed: 2,
  },
  {
    id: 'RUMMY_500',
    rulesetId: 'RUMMY_500',
    label: 'Rummy 500',
    badge: '⚡ 500 PTS',
    desc: 'Dynamic Melds • Race to 500 Cumulative Points',
    cardsDealt: 13,
    decks: '1 Deck',
  },
  {
    id: 'KALOOKI',
    rulesetId: 'KALOOKI',
    label: 'Kalooki',
    badge: '🇬🇧 CONTRACT',
    desc: '13 Cards • 2 Decks • Wild Jokers & Laying Off Runs',
    cardsDealt: 13,
    decks: '2 Decks',
  },
  {
    id: 'CANASTA',
    rulesetId: 'CANASTA',
    label: 'Canasta',
    badge: '💎 7 MELDS',
    desc: '11 Cards • 7-Card Red/Black Canastas • 5,000 Target',
    cardsDealt: 11,
    decks: '2 Decks',
  },
];

const POINTS_TIERS: StakeTier[] = [
  { id: 'points_practice', name: 'Practice Club', tag: 'Beginner', pointValue: '0.5 Token/Pt', minEntry: 50, stake: 50, activePlayers: 1420 },
  { id: 'points_bronze', name: 'Bronze Arena', tag: 'Popular 🔥', pointValue: '1.0 Token/Pt', minEntry: 100, stake: 100, activePlayers: 2840, featured: true },
  { id: 'points_silver', name: 'Silver League', tag: 'Fast Match', pointValue: '5.0 Tokens/Pt', minEntry: 500, stake: 500, activePlayers: 1650 },
  { id: 'points_gold', name: 'Gold Elite', tag: 'High Reward', pointValue: '10.0 Tokens/Pt', minEntry: 1000, stake: 1000, activePlayers: 920 },
  { id: 'points_vip', name: 'Royal High Roller', tag: 'VIP Championship', pointValue: '50.0 Tokens/Pt', minEntry: 5000, stake: 5000, activePlayers: 340 },
];

const POOL_TIERS: StakeTier[] = [
  { id: 'pool_bronze', name: 'Classic Pool', tag: 'Beginner', pointValue: 'Entry: 100', minEntry: 100, stake: 100, prizePool: '180 Tokens', activePlayers: 1980, featured: true },
  { id: 'pool_silver', name: 'Masters Pool', tag: 'Hot 🔥', pointValue: 'Entry: 500', minEntry: 500, stake: 500, prizePool: '900 Tokens', activePlayers: 1240 },
  { id: 'pool_gold', name: 'Champions Pool', tag: 'High Stakes', pointValue: 'Entry: 1,000', minEntry: 1000, stake: 1000, prizePool: '1,800 Tokens', activePlayers: 670 },
  { id: 'pool_diamond', name: 'Grand Slam Pool', tag: 'VIP Elite', pointValue: 'Entry: 5,000', minEntry: 5000, stake: 5000, prizePool: '9,000 Tokens', activePlayers: 210 },
];

const DEALS_TIERS: StakeTier[] = [
  { id: 'deals_bronze', name: 'Quick 2-Deals', tag: 'Fast Pace', pointValue: 'Entry: 100', minEntry: 100, stake: 100, prizePool: '180 Tokens', activePlayers: 1530, featured: true },
  { id: 'deals_silver', name: 'High Stakes 2-Deals', tag: 'Strategic', pointValue: 'Entry: 500', minEntry: 500, stake: 500, prizePool: '900 Tokens', activePlayers: 890 },
  { id: 'deals_gold', name: 'Royal Crown Deals', tag: 'VIP', pointValue: 'Entry: 1,000', minEntry: 1000, stake: 1000, prizePool: '1,800 Tokens', activePlayers: 420 },
];

const TWENTY_ONE_TIERS: StakeTier[] = [
  { id: 'r21_practice', name: 'Marriage Practice', tag: '21 Cards', pointValue: '1.0 Token/Pt', minEntry: 100, stake: 100, activePlayers: 1120 },
  { id: 'r21_bronze', name: 'Dublee Masters', tag: 'Popular 🔥', pointValue: '5.0 Tokens/Pt', minEntry: 500, stake: 500, activePlayers: 1890, featured: true },
  { id: 'r21_gold', name: 'Crown 21 Suite', tag: 'VIP High Stakes', pointValue: '10.0 Tokens/Pt', minEntry: 1000, stake: 1000, activePlayers: 840 },
  { id: 'r21_emperor', name: 'Emperor 21 Jackpot', tag: 'Championship', pointValue: '50.0 Tokens/Pt', minEntry: 5000, stake: 5000, activePlayers: 290 },
];

const GIN_TIERS: StakeTier[] = [
  { id: 'gin_casual', name: 'Casual Knock', tag: '2P Classic', pointValue: '0.5 Token/Box', minEntry: 50, stake: 50, activePlayers: 890 },
  { id: 'gin_bronze', name: 'Classic Gin', tag: 'Popular 🔥', pointValue: '1.0 Token/Box', minEntry: 100, stake: 100, activePlayers: 1650, featured: true },
  { id: 'gin_pro', name: 'Pro Gin Arena', tag: 'High Stakes', pointValue: '5.0 Tokens/Box', minEntry: 500, stake: 500, activePlayers: 780 },
  { id: 'gin_grand', name: 'Grand Gin Suite', tag: 'VIP Master', pointValue: '10.0 Tokens/Box', minEntry: 1000, stake: 1000, activePlayers: 340 },
];

const RUMMY_500_TIERS: StakeTier[] = [
  { id: 'r500_sprint', name: '500 Sprint', tag: 'Target 500', pointValue: '1.0 Token/Pt', minEntry: 100, stake: 100, activePlayers: 920, featured: true },
  { id: 'r500_league', name: '500 Pro League', tag: 'Championship', pointValue: '5.0 Tokens/Pt', minEntry: 500, stake: 500, activePlayers: 640 },
  { id: 'r500_grand', name: '500 High Roller', tag: 'VIP', pointValue: '10.0 Tokens/Pt', minEntry: 1000, stake: 1000, activePlayers: 280 },
];

const KALOOKI_TIERS: StakeTier[] = [
  { id: 'kal_british', name: 'British Kalooki', tag: 'Contract', pointValue: 'Entry: 100', minEntry: 100, stake: 100, prizePool: '180 Tokens', activePlayers: 780, featured: true },
  { id: 'kal_club', name: 'Club Kalooki', tag: 'Hot 🔥', pointValue: 'Entry: 500', minEntry: 500, stake: 500, prizePool: '900 Tokens', activePlayers: 490 },
  { id: 'kal_royal', name: 'Royal Kalooki', tag: 'VIP', pointValue: 'Entry: 1,000', minEntry: 1000, stake: 1000, prizePool: '1,800 Tokens', activePlayers: 210 },
];

const CANASTA_TIERS: StakeTier[] = [
  { id: 'can_silver', name: 'Silver Canasta', tag: '7-Card Melds', pointValue: 'Entry: 100', minEntry: 100, stake: 100, prizePool: '180 Tokens', activePlayers: 620, featured: true },
  { id: 'can_gold', name: 'Gold Canasta', tag: 'Championship', pointValue: 'Entry: 500', minEntry: 500, stake: 500, prizePool: '900 Tokens', activePlayers: 380 },
  { id: 'can_royal', name: 'Royal Canasta', tag: 'VIP Target 5K', pointValue: 'Entry: 1,000', minEntry: 1000, stake: 1000, prizePool: '1,800 Tokens', activePlayers: 180 },
];

export const LobbyScreen: React.FC = () => {
  const { displayName, setSession, setHasJoinedTable, connectionStatus, playerId } = useGameStore();

  // Player state
  const [localName, setLocalName] = useState(displayName);
  const [isEditingName, setIsEditingName] = useState(false);
  const [walletBalance, setWalletBalance] = useState<number>(1000);

  // Modals
  const [isWalletOpen, setIsWalletOpen] = useState(false);
  const [isHistoryOpen, setIsHistoryOpen] = useState(false);
  const [isAdminOpen, setIsAdminOpen] = useState(false);
  const [isSafePlayOpen, setIsSafePlayOpen] = useState(false);

  // Matchmaking filter state: default Points Rummy
  const [selectedVariant, setSelectedVariant] = useState<string>('INDIAN_POINTS');
  const [selectedPlayers, setSelectedPlayers] = useState<2 | 6>(2);

  // Matchmaking execution state
  const [isMatchmaking, setIsMatchmaking] = useState(false);
  const [activeTier, setActiveTier] = useState<StakeTier | null>(null);
  const [mmTicketId, setMmTicketId] = useState<string | null>(null);
  const [mmQueueTime, setMmQueueTime] = useState(0);
  const [balanceAlert, setBalanceAlert] = useState<string | null>(null);
  const pollIntervalRef = useRef<number | null>(null);

  // Current active variant config
  const activeVariantConfig = ALL_VARIANTS.find((v) => v.id === selectedVariant) || ALL_VARIANTS[0];

  // Fetch real-time wallet balance
  const fetchBalance = useCallback(async () => {
    try {
      const res = await fetch(`http://localhost:8081/api/wallet/balance?playerId=${playerId}`);
      if (res.ok) {
        const data = await res.json();
        setWalletBalance(data.freePlayBalance ?? 1000);
      }
    } catch {
      // Backend offline or fallback
    }
  }, [playerId]);

  useEffect(() => {
    fetchBalance();
    const interval = setInterval(fetchBalance, 10000);
    return () => clearInterval(interval);
  }, [fetchBalance]);

  // Adjust player count if variant only supports 2 players (like Gin Rummy)
  useEffect(() => {
    if (activeVariantConfig.maxPlayersAllowed && activeVariantConfig.maxPlayersAllowed < selectedPlayers) {
      setSelectedPlayers(2);
    }
  }, [activeVariantConfig, selectedPlayers]);

  // Matchmaking timer (15s countdown)
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

  // Start Matchmaking for a specific table tier
  const handlePlayNow = async (tier: StakeTier) => {
    if (!localName.trim()) return;
    setBalanceAlert(null);
    setActiveTier(tier);

    // Balance check
    if (walletBalance < tier.stake) {
      setBalanceAlert(
        `Insufficient Tokens! You need ${tier.stake} Free Tokens to enter this table, but currently have ${walletBalance} Tokens.`
      );
      return;
    }

    setIsMatchmaking(true);
    setMmQueueTime(0);

    const actualMaxPlayers = activeVariantConfig.maxPlayersAllowed ? activeVariantConfig.maxPlayersAllowed : selectedPlayers;

    try {
      const res = await fetch('http://localhost:8081/api/matchmaking/join', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          playerId,
          playerName: localName.trim(),
          rulesetId: activeVariantConfig.rulesetId,
          stakeTier: tier.stake,
          maxPlayers: actualMaxPlayers,
          allowAiFallback: true,
        }),
      });

      if (!res.ok) {
        const errData = await res.json().catch(() => ({}));
        throw new Error(errData.message || 'Failed to join matchmaking queue');
      }

      const data = await res.json();
      setMmTicketId(data.ticketId);

      // Start polling ticket every second
      pollIntervalRef.current = window.setInterval(async () => {
        try {
          const pollRes = await fetch(`http://localhost:8081/api/matchmaking/ticket/${data.ticketId}`);
          if (pollRes.ok) {
            const ticketData = await pollRes.json();
            if (ticketData.status === 'MATCHED' && ticketData.matchedTableId) {
              if (pollIntervalRef.current) clearInterval(pollIntervalRef.current);
              setIsMatchmaking(false);
              setSession(ticketData.matchedTableId, playerId, localName.trim());
              setHasJoinedTable(true);

              if (connectionStatus === 'CONNECTED') {
                socketClient.joinTable(0);
              } else {
                socketClient.connect();
              }
            }
          }
        } catch {
          // Network retry
        }
      }, 1000);
    } catch (err: any) {
      setIsMatchmaking(false);
      setBalanceAlert(err.message || 'Error joining matchmaking queue.');
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
    setActiveTier(null);
  };

  // Get current active tiers based on selected variant
  const getTiers = (): StakeTier[] => {
    switch (selectedVariant) {
      case 'INDIAN_POINTS':
        return POINTS_TIERS;
      case 'POOL_101':
      case 'POOL_201':
        return POOL_TIERS;
      case 'DEALS_2':
        return DEALS_TIERS;
      case 'RUMMY_21':
        return TWENTY_ONE_TIERS;
      case 'GIN_RUMMY':
        return GIN_TIERS;
      case 'RUMMY_500':
        return RUMMY_500_TIERS;
      case 'KALOOKI':
        return KALOOKI_TIERS;
      case 'CANASTA':
        return CANASTA_TIERS;
      default:
        return POINTS_TIERS;
    }
  };

  return (
    <div
      style={{
        minHeight: '100vh',
        background: 'radial-gradient(ellipse at 50% 10%, #172554 0%, #06090e 65%)',
        color: '#f8fafc',
        display: 'flex',
        flexDirection: 'column',
      }}
    >
      {/* ================= TOP APP BAR ================= */}
      <header
        style={{
          height: '70px',
          background: 'rgba(6, 9, 14, 0.85)',
          backdropFilter: 'blur(16px)',
          borderBottom: '1px solid rgba(212, 175, 55, 0.25)',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between',
          padding: '0 24px',
          position: 'sticky',
          top: 0,
          zIndex: 100,
        }}
      >
        {/* Brand & Logo */}
        <div style={{ display: 'flex', alignItems: 'center', gap: '12px' }}>
          <div
            style={{
              width: '42px',
              height: '42px',
              borderRadius: '10px',
              background: 'linear-gradient(135deg, #d4af37 0%, #78350f 100%)',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              boxShadow: '0 0 15px rgba(212, 175, 55, 0.4)',
            }}
          >
            <Crown size={24} color="#ffffff" />
          </div>
          <div>
            <div
              style={{
                fontFamily: 'var(--font-display)',
                fontSize: '20px',
                fontWeight: 900,
                letterSpacing: '1px',
                background: 'linear-gradient(135deg, #ffffff 0%, #fef08a 60%, #d4af37 100%)',
                WebkitBackgroundClip: 'text',
                WebkitTextFillColor: 'transparent',
                display: 'flex',
                alignItems: 'center',
                gap: '6px',
              }}
            >
              ROYAL RUMMY
              <span
                style={{
                  fontSize: '10px',
                  fontWeight: 700,
                  padding: '2px 6px',
                  background: 'rgba(212, 175, 55, 0.2)',
                  border: '1px solid rgba(212, 175, 55, 0.5)',
                  borderRadius: '4px',
                  color: '#fef08a',
                  letterSpacing: '0.5px',
                  WebkitTextFillColor: '#fef08a',
                }}
              >
                PRO
              </span>
            </div>
            <div style={{ fontSize: '11px', color: '#94a3b8', display: 'flex', alignItems: 'center', gap: '6px' }}>
              <span style={{ display: 'inline-block', width: '6px', height: '6px', borderRadius: '50%', background: '#10b981' }} />
              <span>5,410 Players Online across 9 Variants</span>
            </div>
          </div>
        </div>

        {/* Right Section: Player Capsule, Live Wallet, Actions */}
        <div style={{ display: 'flex', alignItems: 'center', gap: '14px' }}>
          {/* Player Profile Capsule */}
          <div
            style={{
              display: 'flex',
              alignItems: 'center',
              gap: '8px',
              padding: '6px 12px',
              background: 'rgba(30, 41, 59, 0.6)',
              border: '1px solid rgba(255, 255, 255, 0.1)',
              borderRadius: '24px',
            }}
          >
            <div
              style={{
                width: '28px',
                height: '28px',
                borderRadius: '50%',
                background: 'linear-gradient(135deg, #3b82f6, #1e40af)',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                fontWeight: 700,
                fontSize: '12px',
              }}
            >
              {localName ? localName.charAt(0).toUpperCase() : 'R'}
            </div>
            {isEditingName ? (
              <input
                type="text"
                value={localName}
                onChange={(e) => setLocalName(e.target.value)}
                onBlur={() => setIsEditingName(false)}
                onKeyDown={(e) => e.key === 'Enter' && setIsEditingName(false)}
                autoFocus
                style={{
                  background: '#0f172a',
                  border: '1px solid var(--border-gold)',
                  borderRadius: '4px',
                  color: '#ffffff',
                  fontSize: '12px',
                  padding: '2px 6px',
                  width: '90px',
                  outline: 'none',
                }}
              />
            ) : (
              <span
                onClick={() => setIsEditingName(true)}
                title="Click to edit name"
                style={{ fontSize: '13px', fontWeight: 600, color: '#e2e8f0', cursor: 'pointer' }}
              >
                {localName || 'RoyalAce'} ✎
              </span>
            )}
          </div>

          {/* Live Wallet Pill */}
          <div
            id="wallet-balance-pill"
            style={{
              display: 'flex',
              alignItems: 'center',
              background: 'linear-gradient(135deg, rgba(212, 175, 55, 0.15) 0%, rgba(15, 23, 42, 0.8) 100%)',
              border: '1px solid rgba(212, 175, 55, 0.5)',
              borderRadius: '24px',
              padding: '4px 6px 4px 12px',
              gap: '8px',
              boxShadow: '0 0 12px rgba(212, 175, 55, 0.2)',
            }}
          >
            <div style={{ display: 'flex', alignItems: 'center', gap: '6px' }}>
              <Coins size={16} color="#d4af37" />
              <div>
                <div style={{ fontSize: '10px', color: '#94a3b8', lineHeight: 1 }}>BALANCE</div>
                <div style={{ fontSize: '14px', fontWeight: 800, color: '#fef08a', lineHeight: 1.2 }}>
                  {walletBalance.toLocaleString()} <span style={{ fontSize: '10px', fontWeight: 600 }}>TOKENS</span>
                </div>
              </div>
            </div>
            <button
              type="button"
              id="btn-top-add-tokens"
              onClick={() => setIsWalletOpen(true)}
              title="Add Free Tokens"
              style={{
                background: 'linear-gradient(135deg, #10b981 0%, #059669 100%)',
                border: 'none',
                borderRadius: '16px',
                color: '#ffffff',
                padding: '4px 10px',
                fontSize: '11px',
                fontWeight: 700,
                cursor: 'pointer',
                display: 'flex',
                alignItems: 'center',
                gap: '4px',
                boxShadow: '0 2px 6px rgba(16, 185, 129, 0.4)',
              }}
            >
              <PlusCircle size={13} />
              Vault
            </button>
          </div>

          {/* Quick Action Navigation Icons */}
          <div style={{ display: 'flex', alignItems: 'center', gap: '6px' }}>
            <button
              type="button"
              id="btn-nav-history"
              onClick={() => setIsHistoryOpen(true)}
              title="Match History"
              style={{
                background: 'rgba(30, 41, 59, 0.7)',
                border: '1px solid rgba(255, 255, 255, 0.1)',
                borderRadius: '8px',
                color: '#cbd5e1',
                padding: '8px 12px',
                fontSize: '12px',
                fontWeight: 600,
                cursor: 'pointer',
                display: 'flex',
                alignItems: 'center',
                gap: '6px',
              }}
            >
              📜 History
            </button>
            <button
              type="button"
              id="btn-nav-safe"
              onClick={() => setIsSafePlayOpen(true)}
              title="Responsible Gaming & Fair Play"
              style={{
                background: 'rgba(16, 185, 129, 0.1)',
                border: '1px solid rgba(16, 185, 129, 0.3)',
                borderRadius: '8px',
                color: '#34d399',
                padding: '8px 12px',
                fontSize: '12px',
                fontWeight: 600,
                cursor: 'pointer',
                display: 'flex',
                alignItems: 'center',
                gap: '6px',
              }}
            >
              <ShieldCheck size={14} />
              Safe Play
            </button>
            <button
              type="button"
              id="btn-nav-admin"
              onClick={() => setIsAdminOpen(true)}
              title="Admin Dashboard"
              style={{
                background: 'rgba(59, 130, 246, 0.1)',
                border: '1px solid rgba(59, 130, 246, 0.25)',
                borderRadius: '8px',
                color: '#93c5fd',
                padding: '8px 12px',
                fontSize: '12px',
                fontWeight: 600,
                cursor: 'pointer',
                display: 'flex',
                alignItems: 'center',
                gap: '6px',
              }}
            >
              <Activity size={14} />
              Admin
            </button>
          </div>
        </div>
      </header>

      {/* ================= PROMO & FAIR PLAY BANNER ================= */}
      <div
        style={{
          background: 'linear-gradient(90deg, #1e1b4b 0%, #1e293b 50%, #0f172a 100%)',
          borderBottom: '1px solid rgba(255, 255, 255, 0.08)',
          padding: '10px 24px',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between',
          fontSize: '12px',
          color: '#cbd5e1',
        }}
      >
        <div style={{ display: 'flex', alignItems: 'center', gap: '12px' }}>
          <span
            style={{
              padding: '2px 8px',
              background: 'rgba(239, 68, 68, 0.2)',
              border: '1px solid rgba(239, 68, 68, 0.4)',
              borderRadius: '4px',
              color: '#f87171',
              fontWeight: 700,
              fontSize: '10px',
              textTransform: 'uppercase',
            }}
          >
            ALL 9 VARIANTS LIVE
          </span>
          <span style={{ fontWeight: 600, color: '#fef08a' }}>
            🏆 Royal Rummy Suite: Indian 13-Card, 21-Card Marriage, Gin Rummy, Rummy 500, Kalooki & Canasta
          </span>
          <span style={{ color: 'var(--text-dim)' }}>|</span>
          <span>⚡ 15-Second Matchmaking Radar Active with AI Fallback Guarantee</span>
        </div>
        <div style={{ display: 'flex', alignItems: 'center', gap: '8px', color: '#10b981', fontWeight: 600 }}>
          <UserCheck size={14} />
          <span>RNG Certified & Server-Authoritative Engine</span>
        </div>
      </div>

      {/* ================= INSUFFICIENT BALANCE ALERT MODAL ================= */}
      {balanceAlert && (
        <div
          style={{
            margin: '16px auto 0 auto',
            maxWidth: '1200px',
            width: 'calc(100% - 48px)',
            background: 'rgba(239, 68, 68, 0.15)',
            border: '1px solid rgba(239, 68, 68, 0.4)',
            borderRadius: '10px',
            padding: '12px 18px',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
            gap: '12px',
          }}
        >
          <div style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
            <AlertTriangle size={20} color="#f87171" />
            <span style={{ color: '#fca5a5', fontSize: '13px', fontWeight: 600 }}>{balanceAlert}</span>
          </div>
          <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
            <button
              type="button"
              onClick={() => {
                setBalanceAlert(null);
                setIsWalletOpen(true);
              }}
              style={{
                background: 'linear-gradient(135deg, #d4af37, #b45309)',
                border: 'none',
                borderRadius: '6px',
                padding: '6px 14px',
                color: '#ffffff',
                fontWeight: 700,
                fontSize: '12px',
                cursor: 'pointer',
              }}
            >
              Open Token Vault
            </button>
            <button
              type="button"
              onClick={() => setBalanceAlert(null)}
              style={{
                background: 'transparent',
                border: 'none',
                color: '#94a3b8',
                cursor: 'pointer',
                padding: '4px',
              }}
            >
              <X size={16} />
            </button>
          </div>
        </div>
      )}

      {/* ================= MAIN LOBBY CONTAINER ================= */}
      <main
        style={{
          flex: 1,
          maxWidth: '1240px',
          width: '100%',
          margin: '0 auto',
          padding: '24px',
          display: 'flex',
          flexDirection: 'column',
          gap: '20px',
        }}
      >
        {/* ALL 9 GAME VARIANT TABS (Horizontal Scrollable Strip) */}
        <div
          style={{
            display: 'flex',
            flexDirection: 'column',
            gap: '10px',
            borderBottom: '2px solid rgba(255, 255, 255, 0.08)',
            paddingBottom: '12px',
          }}
        >
          <div
            style={{
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'space-between',
              gap: '12px',
            }}
          >
            <div style={{ display: 'flex', alignItems: 'center', gap: '6px', color: '#d4af37', fontSize: '12px', fontWeight: 700 }}>
              <Layers size={15} />
              SELECT GAME VARIANT ({ALL_VARIANTS.length} MODES)
            </div>

            {/* Player Count Filter Pill (2 vs 6 Players) */}
            <div
              style={{
                display: 'flex',
                alignItems: 'center',
                background: 'rgba(15, 23, 42, 0.8)',
                border: '1px solid rgba(255, 255, 255, 0.12)',
                borderRadius: '24px',
                padding: '3px',
                gap: '3px',
              }}
            >
              <button
                type="button"
                id="filter-2-players"
                onClick={() => setSelectedPlayers(2)}
                style={{
                  padding: '5px 12px',
                  borderRadius: '18px',
                  border: 'none',
                  background: selectedPlayers === 2 ? 'linear-gradient(135deg, #d4af37, #b45309)' : 'transparent',
                  color: selectedPlayers === 2 ? '#ffffff' : '#94a3b8',
                  fontSize: '11px',
                  fontWeight: 700,
                  cursor: 'pointer',
                  display: 'flex',
                  alignItems: 'center',
                  gap: '5px',
                  transition: 'all 0.2s ease',
                }}
              >
                <Users size={13} />
                2 Players
              </button>
              <button
                type="button"
                id="filter-6-players"
                disabled={activeVariantConfig.maxPlayersAllowed === 2}
                onClick={() => setSelectedPlayers(6)}
                title={activeVariantConfig.maxPlayersAllowed === 2 ? 'This variant is strictly 2 players only' : '6 Players table'}
                style={{
                  padding: '5px 12px',
                  borderRadius: '18px',
                  border: 'none',
                  background: selectedPlayers === 6 ? 'linear-gradient(135deg, #d4af37, #b45309)' : 'transparent',
                  color: selectedPlayers === 6 ? '#ffffff' : '#94a3b8',
                  fontSize: '11px',
                  fontWeight: 700,
                  cursor: activeVariantConfig.maxPlayersAllowed === 2 ? 'not-allowed' : 'pointer',
                  opacity: activeVariantConfig.maxPlayersAllowed === 2 ? 0.35 : 1,
                  display: 'flex',
                  alignItems: 'center',
                  gap: '5px',
                  transition: 'all 0.2s ease',
                }}
              >
                <Users size={13} />
                6 Players
              </button>
            </div>
          </div>

          {/* Smooth Horizontal Scrollable Tabs */}
          <div
            style={{
              display: 'flex',
              gap: '8px',
              overflowX: 'auto',
              paddingBottom: '6px',
              scrollbarWidth: 'thin',
            }}
          >
            {ALL_VARIANTS.map((tab) => {
              const active = selectedVariant === tab.id;
              return (
                <button
                  key={tab.id}
                  type="button"
                  id={`tab-variant-${tab.id.toLowerCase()}`}
                  onClick={() => setSelectedVariant(tab.id)}
                  style={{
                    padding: '10px 16px',
                    borderRadius: '10px',
                    border: active ? '1px solid rgba(212, 175, 55, 0.8)' : '1px solid rgba(255, 255, 255, 0.08)',
                    background: active
                      ? 'linear-gradient(180deg, rgba(212, 175, 55, 0.25) 0%, rgba(30, 41, 59, 0.7) 100%)'
                      : 'rgba(15, 23, 42, 0.6)',
                    color: active ? '#ffffff' : '#94a3b8',
                    cursor: 'pointer',
                    display: 'flex',
                    flexDirection: 'column',
                    alignItems: 'flex-start',
                    gap: '3px',
                    flexShrink: 0,
                    transition: 'all 0.2s ease',
                  }}
                >
                  <div style={{ display: 'flex', alignItems: 'center', gap: '6px' }}>
                    <span style={{ fontSize: '13px', fontWeight: active ? 800 : 600 }}>{tab.label}</span>
                    <span
                      style={{
                        fontSize: '9px',
                        fontWeight: 700,
                        padding: '1px 5px',
                        borderRadius: '4px',
                        background: active ? '#d4af37' : 'rgba(255,255,255,0.1)',
                        color: active ? '#0f172a' : '#cbd5e1',
                      }}
                    >
                      {tab.badge}
                    </span>
                  </div>
                  <span style={{ fontSize: '10px', color: active ? '#fef08a' : '#64748b' }}>
                    {tab.cardsDealt} Cards • {tab.decks}
                  </span>
                </button>
              );
            })}
          </div>
        </div>

        {/* ACTIVE VARIANT DESCRIPTION BANNER */}
        <div
          style={{
            background: 'linear-gradient(135deg, rgba(30, 41, 59, 0.7) 0%, rgba(15, 23, 42, 0.9) 100%)',
            border: '1px solid rgba(212, 175, 55, 0.3)',
            borderRadius: '12px',
            padding: '14px 20px',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
            gap: '16px',
          }}
        >
          <div>
            <h2 style={{ fontSize: '17px', fontWeight: 800, color: '#f8fafc', display: 'flex', alignItems: 'center', gap: '8px' }}>
              <Flame size={18} color="#f59e0b" />
              {activeVariantConfig.label} ({activeVariantConfig.maxPlayersAllowed || selectedPlayers} Players Table)
            </h2>
            <p style={{ fontSize: '12px', color: '#cbd5e1', marginTop: '3px' }}>
              {activeVariantConfig.desc}
            </p>
          </div>
          <div style={{ display: 'flex', gap: '10px' }}>
            <div style={{ padding: '6px 12px', background: 'rgba(0,0,0,0.3)', borderRadius: '8px', textAlign: 'center' }}>
              <div style={{ fontSize: '10px', color: '#94a3b8' }}>HAND SIZE</div>
              <div style={{ fontSize: '13px', fontWeight: 800, color: '#fde047' }}>{activeVariantConfig.cardsDealt} Cards</div>
            </div>
            <div style={{ padding: '6px 12px', background: 'rgba(0,0,0,0.3)', borderRadius: '8px', textAlign: 'center' }}>
              <div style={{ fontSize: '10px', color: '#94a3b8' }}>DECK CONFIG</div>
              <div style={{ fontSize: '13px', fontWeight: 800, color: '#38bdf8' }}>{activeVariantConfig.decks}</div>
            </div>
          </div>
        </div>

        {/* ================= TABLE TIERS CARDS ================= */}
        <div
          style={{
            display: 'grid',
            gridTemplateColumns: 'repeat(auto-fit, minmax(220px, 1fr))',
            gap: '16px',
          }}
        >
          {getTiers().map((tier) => {
            const isAffordable = walletBalance >= tier.stake;

            return (
              <div
                key={tier.id}
                className="glass-panel"
                style={{
                  padding: '20px',
                  borderRadius: '16px',
                  border: tier.featured
                    ? '2px solid rgba(212, 175, 55, 0.8)'
                    : '1px solid rgba(255, 255, 255, 0.1)',
                  background: tier.featured
                    ? 'linear-gradient(145deg, rgba(30, 41, 59, 0.8) 0%, rgba(212, 175, 55, 0.1) 100%)'
                    : 'rgba(15, 23, 42, 0.65)',
                  position: 'relative',
                  display: 'flex',
                  flexDirection: 'column',
                  justifyContent: 'space-between',
                  boxShadow: tier.featured
                    ? '0 8px 24px rgba(212, 175, 55, 0.2)'
                    : '0 4px 16px rgba(0,0,0,0.4)',
                  transition: 'transform 0.2s ease, border-color 0.2s ease',
                }}
              >
                {/* Top Badge */}
                {tier.tag && (
                  <div
                    style={{
                      position: 'absolute',
                      top: '-10px',
                      right: '16px',
                      background: tier.featured
                        ? 'linear-gradient(135deg, #d4af37, #b45309)'
                        : 'rgba(30, 41, 59, 0.9)',
                      border: '1px solid rgba(212, 175, 55, 0.5)',
                      borderRadius: '12px',
                      padding: '2px 10px',
                      fontSize: '10px',
                      fontWeight: 800,
                      color: '#ffffff',
                      letterSpacing: '0.5px',
                    }}
                  >
                    {tier.tag}
                  </div>
                )}

                <div>
                  <div style={{ fontSize: '15px', fontWeight: 800, color: '#f8fafc', marginBottom: '10px' }}>
                    {tier.name}
                  </div>

                  {/* Point Value / Entry */}
                  <div
                    style={{
                      padding: '10px',
                      background: 'rgba(0, 0, 0, 0.35)',
                      borderRadius: '8px',
                      marginBottom: '14px',
                      border: '1px solid rgba(255, 255, 255, 0.05)',
                    }}
                  >
                    <div style={{ fontSize: '11px', color: '#94a3b8' }}>Point / Format</div>
                    <div style={{ fontSize: '14px', fontWeight: 700, color: '#fde047' }}>
                      {tier.pointValue}
                    </div>
                  </div>

                  {/* Stake & Players Info */}
                  <div style={{ display: 'flex', flexDirection: 'column', gap: '6px', marginBottom: '16px', fontSize: '12px' }}>
                    <div style={{ display: 'flex', justifyContent: 'space-between' }}>
                      <span style={{ color: '#94a3b8' }}>Min Stake:</span>
                      <span style={{ fontWeight: 700, color: '#ffffff' }}>{tier.stake} Tokens</span>
                    </div>
                    {tier.prizePool && (
                      <div style={{ display: 'flex', justifyContent: 'space-between' }}>
                        <span style={{ color: '#94a3b8' }}>Prize Pool:</span>
                        <span style={{ fontWeight: 700, color: '#34d399' }}>{tier.prizePool}</span>
                      </div>
                    )}
                    <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                      <span style={{ color: '#94a3b8' }}>Live Players:</span>
                      <span style={{ color: '#10b981', fontWeight: 700, display: 'flex', alignItems: 'center', gap: '4px' }}>
                        <span style={{ width: '6px', height: '6px', borderRadius: '50%', background: '#10b981' }} />
                        {tier.activePlayers.toLocaleString()}
                      </span>
                    </div>
                  </div>
                </div>

                {/* Single PLAY NOW Action Button */}
                <button
                  type="button"
                  id={`btn-play-tier-${tier.id}`}
                  onClick={() => handlePlayNow(tier)}
                  style={{
                    width: '100%',
                    padding: '12px',
                    borderRadius: '10px',
                    border: 'none',
                    background: isAffordable
                      ? 'linear-gradient(135deg, #d4af37 0%, #b8860b 100%)'
                      : 'rgba(51, 65, 85, 0.5)',
                    color: isAffordable ? '#0f172a' : '#94a3b8',
                    fontWeight: 900,
                    fontSize: '14px',
                    cursor: isAffordable ? 'pointer' : 'pointer',
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'center',
                    gap: '8px',
                    boxShadow: isAffordable ? '0 4px 14px rgba(212, 175, 55, 0.3)' : 'none',
                    transition: 'all 0.2s ease',
                  }}
                >
                  <Play size={16} fill={isAffordable ? '#0f172a' : '#94a3b8'} />
                  PLAY NOW
                </button>
              </div>
            );
          })}
        </div>

        {/* ================= BOTTOM TRUST & FAIR PLAY FOOTER ================= */}
        <div
          style={{
            marginTop: '20px',
            padding: '16px 20px',
            background: 'rgba(15, 23, 42, 0.5)',
            border: '1px solid rgba(255, 255, 255, 0.08)',
            borderRadius: '12px',
            display: 'grid',
            gridTemplateColumns: 'repeat(auto-fit, minmax(200px, 1fr))',
            gap: '16px',
            alignItems: 'center',
          }}
        >
          <div style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
            <Radio size={20} color="#d4af37" />
            <div>
              <div style={{ fontSize: '12px', fontWeight: 700, color: '#ffffff' }}>15-Sec Instant Radar</div>
              <div style={{ fontSize: '11px', color: '#94a3b8' }}>Real player match with auto-AI fallback</div>
            </div>
          </div>
          <div style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
            <ShieldCheck size={20} color="#10b981" />
            <div>
              <div style={{ fontSize: '12px', fontWeight: 700, color: '#ffffff' }}>100% Fair Play Engine</div>
              <div style={{ fontSize: '11px', color: '#94a3b8' }}>RNG certified deck shuffling algorithm</div>
            </div>
          </div>
          <div style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
            <Trophy size={20} color="#f59e0b" />
            <div>
              <div style={{ fontSize: '12px', fontWeight: 700, color: '#ffffff' }}>Pure Skill Gaming</div>
              <div style={{ fontSize: '11px', color: '#94a3b8' }}>No real currency deposits or betting</div>
            </div>
          </div>
          <div style={{ display: 'flex', alignItems: 'center', gap: '10px' }}>
            <Zap size={20} color="#38bdf8" />
            <div>
              <div style={{ fontSize: '12px', fontWeight: 700, color: '#ffffff' }}>Low-Latency WebSockets</div>
              <div style={{ fontSize: '11px', color: '#94a3b8' }}>Sub-30ms table state synchronization</div>
            </div>
          </div>
        </div>
      </main>

      {/* ================= FULLSCREEN MATCHMAKING RADAR MODAL ================= */}
      {isMatchmaking && activeTier && (
        <div
          style={{
            position: 'fixed',
            inset: 0,
            background: 'rgba(6, 9, 14, 0.92)',
            backdropFilter: 'blur(16px)',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            zIndex: 1000,
            padding: '20px',
          }}
        >
          <div
            className="glass-panel"
            style={{
              width: '100%',
              maxWidth: '480px',
              padding: '36px',
              textAlign: 'center',
              border: '2px solid rgba(212, 175, 55, 0.5)',
              boxShadow: '0 0 40px rgba(212, 175, 55, 0.25)',
              display: 'flex',
              flexDirection: 'column',
              alignItems: 'center',
              gap: '20px',
              position: 'relative',
            }}
          >
            {/* Pulsing Radar Visual */}
            <div
              style={{
                width: '140px',
                height: '140px',
                position: 'relative',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                margin: '10px 0',
              }}
            >
              {/* Concentric sonar pulses */}
              <div
                className="radar-wave-1"
                style={{
                  position: 'absolute',
                  inset: 0,
                  borderRadius: '50%',
                  border: '2px solid rgba(212, 175, 55, 0.6)',
                  pointerEvents: 'none',
                }}
              />
              <div
                className="radar-wave-2"
                style={{
                  position: 'absolute',
                  inset: 0,
                  borderRadius: '50%',
                  border: '2px solid rgba(212, 175, 55, 0.4)',
                  pointerEvents: 'none',
                }}
              />
              <div
                className="radar-wave-3"
                style={{
                  position: 'absolute',
                  inset: 0,
                  borderRadius: '50%',
                  border: '2px solid rgba(212, 175, 55, 0.2)',
                  pointerEvents: 'none',
                }}
              />

              {/* Center Crown Avatar */}
              <div
                style={{
                  width: '70px',
                  height: '70px',
                  borderRadius: '50%',
                  background: 'linear-gradient(135deg, #d4af37 0%, #78350f 100%)',
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'center',
                  boxShadow: '0 0 25px rgba(212, 175, 55, 0.7)',
                  zIndex: 2,
                }}
              >
                <Crown size={36} color="#ffffff" />
              </div>
            </div>

            {/* Table Details */}
            <div>
              <div
                style={{
                  fontSize: '12px',
                  fontWeight: 800,
                  color: '#d4af37',
                  textTransform: 'uppercase',
                  letterSpacing: '1px',
                  marginBottom: '4px',
                }}
              >
                {activeVariantConfig.label} • {activeVariantConfig.maxPlayersAllowed || selectedPlayers} Players Table
              </div>
              <h3 style={{ fontSize: '22px', fontWeight: 900, color: '#ffffff', margin: 0 }}>
                {activeTier.name}
              </h3>
              <div style={{ fontSize: '13px', color: '#94a3b8', marginTop: '4px' }}>
                Stake: <strong style={{ color: '#fef08a' }}>{activeTier.stake} Free Tokens</strong> | Format: {activeTier.pointValue}
              </div>
            </div>

            {/* Live 15s Countdown Capsule */}
            <div
              style={{
                width: '100%',
                background: 'rgba(15, 23, 42, 0.8)',
                border: '1px solid rgba(212, 175, 55, 0.3)',
                borderRadius: '12px',
                padding: '14px',
                display: 'flex',
                flexDirection: 'column',
                gap: '10px',
              }}
            >
              <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: '8px', fontSize: '13px', fontWeight: 700, color: '#f8fafc' }}>
                  <Loader2 size={18} className="spinner" color="#d4af37" />
                  <span>Searching for Online Players...</span>
                </div>
                <div
                  style={{
                    fontSize: '14px',
                    fontWeight: 900,
                    color: mmQueueTime >= 13 ? '#ef4444' : '#10b981',
                    background: 'rgba(0,0,0,0.4)',
                    padding: '2px 8px',
                    borderRadius: '6px',
                  }}
                >
                  ⏳ {Math.max(0, 15 - mmQueueTime)}s
                </div>
              </div>

              {/* 15s Progress Bar */}
              <div
                style={{
                  width: '100%',
                  height: '8px',
                  background: 'rgba(255, 255, 255, 0.1)',
                  borderRadius: '4px',
                  overflow: 'hidden',
                }}
              >
                <div
                  style={{
                    height: '100%',
                    width: `${Math.min(100, (mmQueueTime / 15) * 100)}%`,
                    background: 'linear-gradient(90deg, #10b981 0%, #d4af37 70%, #f59e0b 100%)',
                    transition: 'width 1s linear',
                  }}
                />
              </div>

              <div style={{ fontSize: '11px', color: '#94a3b8', lineHeight: 1.4 }}>
                {mmQueueTime < 15
                  ? `Scanning active player queue for ${activeVariantConfig.label}. If another real player does not join within 15 seconds, game starts automatically with our AI Bot.`
                  : 'Starting game with Royal AI Player now...'}
              </div>
            </div>

            {/* Cancel Button */}
            <button
              type="button"
              id="btn-cancel-matchmaking"
              onClick={handleCancelMatchmaking}
              style={{
                padding: '10px 24px',
                borderRadius: '8px',
                background: 'rgba(239, 68, 68, 0.15)',
                border: '1px solid rgba(239, 68, 68, 0.4)',
                color: '#f87171',
                fontSize: '13px',
                fontWeight: 700,
                cursor: 'pointer',
                display: 'flex',
                alignItems: 'center',
                gap: '6px',
                transition: 'all 0.2s ease',
              }}
            >
              <X size={16} /> Cancel Search
            </button>
          </div>
        </div>
      )}

      {/* ================= MODALS ================= */}
      <WalletModal
        isOpen={isWalletOpen}
        onClose={() => {
          setIsWalletOpen(false);
          fetchBalance();
        }}
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
    </div>
  );
};
