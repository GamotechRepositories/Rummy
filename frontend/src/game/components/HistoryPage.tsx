import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { ArrowLeft, ChevronDown, RefreshCw } from 'lucide-react';
import { useGameStore } from '../store/useGameStore';
import type { GameResultItem } from '../types/game';
import { getApiBaseUrl } from '../utils/apiConfig';
import { authFetch } from '../utils/authClient';
import { soundEngine } from '../audio/soundEngine';

export interface HistoryVariantFilter {
  id: string;
  label: string;
  rulesets: string[];
}

type HistorySection = 'matches' | 'tournaments' | 'private' | 'wallet';

interface TournamentHistoryItem {
  tournamentId: string;
  title?: string;
  variant?: string;
  status: string;
  entryFeeRs: number;
  prizePoolRs: number;
  startsAt?: string;
  registered?: number;
  maxPlayers?: number;
  myRank?: number;
  myPrizeRs?: number;
  winnerDisplayName?: string;
  cancelledReason?: string;
}

interface PrivateRoomHistoryItem {
  roomCode: string;
  rulesetId?: string;
  stakeTier: number;
  maxPlayers: number;
  status: string;
  isHost?: boolean;
  hostDisplayName?: string;
  winnerPlayerId?: string;
  winnerDisplayName?: string;
  winnerPayoutRs?: number;
  players?: Array<{ playerId: string; displayName: string }>;
  createdAt?: string;
}

interface WalletTransactionItem {
  id: string;
  transactionType: string;
  amount: number;
  balanceBefore: number;
  balanceAfter: number;
  description?: string;
  createdAt: string;
}

interface HistoryPageProps {
  onBack: () => void;
  allowedRulesets: string[];
  variantFilters?: HistoryVariantFilter[];
  showTournaments?: boolean;
}

const RULESET_LABELS: Record<string, string> = {
  POINTS_13: 'Points Rummy',
  POOL_101: '101 Pool',
  POOL_201: '201 Pool',
  DEALS_2: '2 Deals',
  DEALS_3: '3 Deals',
  DEALS_RUMMY: 'Deals Rummy',
  RUMMY_21: '21-Card',
};

const TX_LABELS: Record<string, string> = {
  GAME_ENTRY_STAKE: 'Table Entry',
  REJOIN_FEE: 'Rejoin Fee',
  GAME_WIN: 'Winnings',
  GAME_REFUND: 'Stake Returned',
  GAME_ENTRY_REFUND: 'Entry Refund',
  GAME_ABORT_REFUND: 'Match Cancelled Refund',
  GAME_CRASH_REFUND: 'Match Cancelled Refund',
  REJOIN_REFUND: 'Rejoin Fee Refund',
  TOURNAMENT_ENTRY: 'Tournament Entry',
  TOURNAMENT_REFUND: 'Tournament Refund',
  TOURNAMENT_PRIZE: 'Tournament Prize',
  PRIVATE_ROOM_ENTRY: 'Private Room Entry',
  PRIVATE_ROOM_REFUND: 'Private Room Refund',
  PRIVATE_ROOM_WIN: 'Private Room Win',
};

function isCreditTx(type: string, description?: string): boolean {
  const upper = type.toUpperCase();
  if (upper.includes('WIN') || upper.includes('REFUND') || upper.includes('PRIZE') || upper.includes('CREDIT')) {
    return true;
  }
  if (description && /win|prize|refund/i.test(description)) {
    return true;
  }
  return false;
}

function formatRulesetLabel(rulesetId?: string): string {
  if (!rulesetId) return 'Rummy Match';
  const key = rulesetId.trim().toUpperCase();
  return RULESET_LABELS[key] ?? rulesetId.replace(/_/g, ' ');
}

function formatMatchSource(tableId?: string): string | null {
  if (!tableId) return null;
  if (tableId.startsWith('TBL_TRN_')) return 'Tournament';
  if (tableId.startsWith('TBL_PRV_')) return 'Private Room';
  return null;
}

function formatResultLabel(status?: string, won?: boolean): string {
  if (won) return 'Won';
  const upper = (status ?? '').toUpperCase();
  if (upper.includes('DROP')) return 'Dropped';
  if (upper === 'ELIMINATED') return 'Lost';
  return 'Lost';
}

function formatTournamentDisplay(item: TournamentHistoryItem): {
  amountText: string;
  amountTone: 'credit' | 'debit' | 'neutral';
  badgeLabel: string;
  badgeTone: 'won' | 'lost' | 'neutral';
} {
  const won = item.myRank === 1 || (typeof item.myPrizeRs === 'number' && item.myPrizeRs > 0);
  if (won) {
    const winAmount = typeof item.myPrizeRs === 'number' ? item.myPrizeRs : 0;
    return {
      amountText: winAmount > 0 ? `+₹ ${formatInr(winAmount)}` : 'Free',
      amountTone: 'credit',
      badgeLabel: 'Won',
      badgeTone: 'won',
    };
  }
  const status = (item.status || '').toUpperCase();
  const entry = item.entryFeeRs > 0 ? item.entryFeeRs : 0;
  if (status === 'COMPLETED') {
    return {
      amountText: entry > 0 ? `-₹ ${formatInr(entry)}` : 'Free',
      amountTone: entry > 0 ? 'debit' : 'neutral',
      badgeLabel: 'Lost',
      badgeTone: 'lost',
    };
  }
  if (status === 'CANCELLED') {
    return {
      amountText: entry > 0 ? `₹ ${formatInr(entry)}` : 'Free',
      amountTone: 'neutral',
      badgeLabel: 'Cancelled',
      badgeTone: 'lost',
    };
  }
  if (status === 'RUNNING') {
    return {
      amountText: entry > 0 ? `-₹ ${formatInr(entry)}` : 'Free',
      amountTone: entry > 0 ? 'debit' : 'neutral',
      badgeLabel: 'Live',
      badgeTone: 'won',
    };
  }
  if (status === 'STARTING') {
    return {
      amountText: entry > 0 ? `-₹ ${formatInr(entry)}` : 'Free',
      amountTone: entry > 0 ? 'debit' : 'neutral',
      badgeLabel: 'Check-In',
      badgeTone: 'neutral',
    };
  }
  if (status === 'REGISTERING') {
    return {
      amountText: entry > 0 ? `-₹ ${formatInr(entry)}` : 'Free',
      amountTone: entry > 0 ? 'debit' : 'neutral',
      badgeLabel: 'Registered',
      badgeTone: 'neutral',
    };
  }
  return {
    amountText: entry > 0 ? `-₹ ${formatInr(entry)}` : 'Free',
    amountTone: entry > 0 ? 'debit' : 'neutral',
    badgeLabel: item.status || 'Unknown',
    badgeTone: 'neutral',
  };
}

function formatPrivateRoomStatus(room: PrivateRoomHistoryItem, playerId: string): { label: string; tone: 'won' | 'lost' | 'neutral' } {
  const status = (room.status || '').toUpperCase();
  if (status === 'COMPLETED') {
    if (room.winnerPlayerId && room.winnerPlayerId === playerId) {
      return { label: 'Won', tone: 'won' };
    }
    return { label: 'Completed', tone: 'neutral' };
  }
  if (status === 'CANCELLED') return { label: 'Cancelled', tone: 'lost' };
  if (status === 'EXPIRED') return { label: 'Expired', tone: 'lost' };
  if (status === 'RUNNING') return { label: 'Live', tone: 'won' };
  if (status === 'WAITING') return { label: 'Waiting', tone: 'neutral' };
  return { label: status || 'Closed', tone: 'neutral' };
}

function formatDateTime(iso?: string): string {
  if (!iso) return '—';
  try {
    const dt = new Date(iso);
    if (Number.isNaN(dt.getTime())) return iso;
    return new Intl.DateTimeFormat('en-IN', {
      day: '2-digit',
      month: 'short',
      hour: '2-digit',
      minute: '2-digit',
    }).format(dt);
  } catch {
    return iso;
  }
}

function shortGameRef(gameId: string): string {
  const clean = gameId.replace(/[^a-zA-Z0-9]/g, '');
  return clean.length > 8 ? clean.slice(-8).toUpperCase() : clean.toUpperCase();
}

function formatInr(value: number): string {
  return value.toLocaleString('en-IN', {
    minimumFractionDigits: Number.isInteger(value) ? 0 : 2,
    maximumFractionDigits: 2,
  });
}

export const HistoryPage: React.FC<HistoryPageProps> = ({
  onBack,
  allowedRulesets,
  variantFilters,
  showTournaments = true,
}) => {
  const { playerId, walletBalance, setWalletBalance } = useGameStore();
  const [section, setSection] = useState<HistorySection>('matches');
  const [dropdownOpen, setDropdownOpen] = useState(false);
  const [selectedFilterId, setSelectedFilterId] = useState<string>('ALL');
  const dropdownRef = useRef<HTMLDivElement | null>(null);

  const [matches, setMatches] = useState<GameResultItem[]>([]);
  const [loadingMatches, setLoadingMatches] = useState(true);
  const [matchError, setMatchError] = useState<string | null>(null);

  const [tournaments, setTournaments] = useState<TournamentHistoryItem[]>([]);
  const [loadingTournaments, setLoadingTournaments] = useState(showTournaments);
  const [tournamentError, setTournamentError] = useState<string | null>(null);

  const [privateRooms, setPrivateRooms] = useState<PrivateRoomHistoryItem[]>([]);
  const [loadingPrivate, setLoadingPrivate] = useState(true);
  const [privateError, setPrivateError] = useState<string | null>(null);

  const [transactions, setTransactions] = useState<WalletTransactionItem[]>([]);
  const [loadingTx, setLoadingTx] = useState(true);
  const [txError, setTxError] = useState<string | null>(null);

  const [isRefreshing, setIsRefreshing] = useState(false);

  const allowedRulesetsParam = allowedRulesets.join(',');

  const fetchMatches = useCallback(async () => {
    try {
      const params = new URLSearchParams();
      if (allowedRulesetsParam) {
        params.set('rulesets', allowedRulesetsParam);
      }
      params.set('excludeTournaments', 'true');
      const res = await authFetch(
        `${getApiBaseUrl()}/api/history/player/${encodeURIComponent(playerId)}?${params.toString()}`
      );
      if (!res.ok) {
        throw new Error(`HTTP ${res.status}`);
      }
      const data = (await res.json()) as { results?: GameResultItem[] };
      setMatchError(null);
      setMatches(Array.isArray(data.results) ? data.results : []);
    } catch {
      setMatchError('Unable to load match history right now.');
    } finally {
      setLoadingMatches(false);
    }
  }, [playerId, allowedRulesetsParam]);

  const fetchTournaments = useCallback(async () => {
    if (!showTournaments) {
      setLoadingTournaments(false);
      return;
    }
    try {
      const variantsQuery = allowedRulesetsParam
        ? `&variants=${encodeURIComponent(allowedRulesetsParam)}`
        : '';
      const res = await authFetch(
        `${getApiBaseUrl()}/api/tournaments/my?playerId=${encodeURIComponent(playerId)}${variantsQuery}`
      );
      if (!res.ok) {
        throw new Error(`HTTP ${res.status}`);
      }
      const data = (await res.json()) as { tournaments?: TournamentHistoryItem[] };
      setTournamentError(null);
      setTournaments(Array.isArray(data.tournaments) ? data.tournaments : []);
    } catch {
      setTournamentError('Unable to load tournament history right now.');
    } finally {
      setLoadingTournaments(false);
    }
  }, [playerId, allowedRulesetsParam, showTournaments]);

  const fetchPrivateRooms = useCallback(async () => {
    try {
      const rulesetsQuery = allowedRulesetsParam
        ? `&rulesets=${encodeURIComponent(allowedRulesetsParam)}`
        : '';
      const res = await authFetch(
        `${getApiBaseUrl()}/api/rooms/my?playerId=${encodeURIComponent(playerId)}${rulesetsQuery}`
      );
      if (!res.ok) {
        throw new Error(`HTTP ${res.status}`);
      }
      const data = (await res.json()) as { rooms?: PrivateRoomHistoryItem[] };
      setPrivateError(null);
      setPrivateRooms(Array.isArray(data.rooms) ? data.rooms : []);
    } catch {
      setPrivateError('Unable to load private room history right now.');
    } finally {
      setLoadingPrivate(false);
    }
  }, [playerId, allowedRulesetsParam]);

  const fetchTransactions = useCallback(async () => {
    try {
      const encodedId = encodeURIComponent(playerId);
      const [txRes, balRes] = await Promise.all([
        authFetch(`${getApiBaseUrl()}/api/wallet/transactions?playerId=${encodedId}`),
        authFetch(`${getApiBaseUrl()}/api/wallet/balance?playerId=${encodedId}`),
      ]);
      if (balRes.ok) {
        const balData = (await balRes.json()) as { balance?: number };
        if (typeof balData.balance === 'number') {
          setWalletBalance(balData.balance);
        }
      }
      if (!txRes.ok) {
        throw new Error(`HTTP ${txRes.status}`);
      }
      const rows = (await txRes.json()) as WalletTransactionItem[];
      setTxError(null);
      setTransactions(Array.isArray(rows) ? rows : []);
    } catch {
      setTxError('Unable to load wallet transactions right now.');
    } finally {
      setLoadingTx(false);
    }
  }, [playerId, setWalletBalance]);

  useEffect(() => {
    void fetchMatches();
    void fetchTournaments();
    void fetchPrivateRooms();
    void fetchTransactions();
  }, [fetchMatches, fetchTournaments, fetchPrivateRooms, fetchTransactions]);

  useEffect(() => {
    if (!dropdownOpen) return;
    const onMouseDown = (e: MouseEvent) => {
      if (dropdownRef.current && !dropdownRef.current.contains(e.target as Node)) {
        setDropdownOpen(false);
      }
    };
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        setDropdownOpen(false);
      }
    };
    window.addEventListener('mousedown', onMouseDown);
    window.addEventListener('keydown', onKeyDown);
    return () => {
      window.removeEventListener('mousedown', onMouseDown);
      window.removeEventListener('keydown', onKeyDown);
    };
  }, [dropdownOpen]);

  const handleRefresh = async () => {
    if (isRefreshing) return;
    soundEngine.play('click');
    setIsRefreshing(true);
    try {
      await Promise.all([
        fetchMatches(),
        fetchTournaments(),
        fetchPrivateRooms(),
        fetchTransactions(),
      ]);
    } finally {
      setIsRefreshing(false);
    }
  };

  const filteredMatches = useMemo(() => {
    const nonTournament = matches.filter(
      (m) => !(m.tableId && m.tableId.startsWith('TBL_TRN_'))
    );
    if (!variantFilters || variantFilters.length === 0 || selectedFilterId === 'ALL') {
      return nonTournament;
    }
    const target = variantFilters.find((f) => f.id === selectedFilterId);
    if (!target || target.rulesets.length === 0) {
      return nonTournament;
    }
    const allowedSet = new Set(target.rulesets.map((r) => r.toUpperCase()));
    return nonTournament.filter((m) => m.rulesetId && allowedSet.has(m.rulesetId.trim().toUpperCase()));
  }, [matches, variantFilters, selectedFilterId]);

  const filteredPrivateRooms = useMemo(() => {
    if (!variantFilters || variantFilters.length === 0 || selectedFilterId === 'ALL') {
      return privateRooms;
    }
    const target = variantFilters.find((f) => f.id === selectedFilterId);
    if (!target || target.rulesets.length === 0) {
      return privateRooms;
    }
    const allowedSet = new Set(target.rulesets.map((r) => r.toUpperCase()));
    return privateRooms.filter((r) => r.rulesetId && allowedSet.has(r.rulesetId.trim().toUpperCase()));
  }, [privateRooms, variantFilters, selectedFilterId]);

  const matchStats = useMemo(() => {
    const total = filteredMatches.length;
    const wins = filteredMatches.filter((m) => m.won).length;
    const winRate = total > 0 ? Math.round((wins / total) * 1000) / 10 : 0;
    const bestScore = total > 0 ? Math.min(...filteredMatches.map((m) => m.finalScore)) : null;
    const settled = filteredMatches.filter((m) => typeof m.netAmountRs === 'number');
    const netTotal = settled.reduce((acc, m) => acc + (m.netAmountRs ?? 0), 0);
    return { total, wins, winRate, bestScore, hasNet: settled.length > 0, netTotal };
  }, [filteredMatches]);

  const tournamentStats = useMemo(() => {
    const total = tournaments.length;
    const won = tournaments.filter(
      (t) => t.myRank === 1 || (typeof t.myPrizeRs === 'number' && t.myPrizeRs > 0)
    ).length;
    const winRate = total > 0 ? Math.round((won / total) * 1000) / 10 : 0;
    const totalPrize = tournaments.reduce((acc, t) => acc + Number(t.myPrizeRs ?? 0), 0);
    return { total, won, winRate, totalPrize };
  }, [tournaments]);

  const privateStats = useMemo(() => {
    const total = filteredPrivateRooms.length;
    const hosted = filteredPrivateRooms.filter((r) => r.isHost).length;
    const won = filteredPrivateRooms.filter((r) => r.winnerPlayerId === playerId).length;
    const completed = filteredPrivateRooms.filter((r) => (r.status || '').toUpperCase() === 'COMPLETED').length;
    return { total, hosted, won, completed };
  }, [filteredPrivateRooms, playerId]);

  const categoryLabel =
    section === 'tournaments'
      ? 'Tournaments'
      : section === 'private'
        ? 'Private Rooms'
        : 'Matches';

  return (
    <div className="stake-panel his-panel">
      <div className="his-topbar-row">
        <button
          type="button"
          className="stake-back"
          onClick={() => {
            soundEngine.play('click');
            onBack();
          }}
        >
          <span className="stake-back-icon" aria-hidden="true">
            <ArrowLeft size={14} strokeWidth={2.75} />
          </span>
          Back to Lobby
        </button>

        <button
          type="button"
          className="his-refresh-btn"
          onClick={() => {
            void handleRefresh();
          }}
          disabled={isRefreshing}
          aria-label="Refresh history"
          title="Refresh"
        >
          <RefreshCw size={14} className={isRefreshing ? 'his-spin' : undefined} />
          <span>Refresh</span>
        </button>
      </div>

      <div className="stake-card his-card">
        <div className="stake-ribbon">MATCH HISTORY</div>

        <div className="his-tabs" role="tablist" aria-label="History sections">
          <div className="his-tab-dropdown-wrap" ref={dropdownRef}>
            <button
              type="button"
              id="btn-history-matches-menu"
              role="tab"
              aria-selected={section !== 'wallet'}
              aria-haspopup="menu"
              aria-expanded={dropdownOpen}
              className={`his-tab-btn his-tab-btn--dropdown${section !== 'wallet' ? ' active' : ''}`}
              onClick={() => {
                soundEngine.play('click');
                setDropdownOpen((prev) => !prev);
              }}
            >
              <span>{categoryLabel}</span>
              <ChevronDown
                size={15}
                className={`his-tab-chevron${dropdownOpen ? ' open' : ''}`}
                aria-hidden="true"
              />
            </button>

            {dropdownOpen && (
              <div className="his-dropdown-menu" role="menu" aria-label="Match category">
                <button
                  type="button"
                  role="menuitem"
                  className={`his-dropdown-item${section === 'matches' ? ' active' : ''}`}
                  onClick={() => {
                    soundEngine.play('click');
                    setSection('matches');
                    setDropdownOpen(false);
                  }}
                >
                  <span>Matches</span>
                </button>
                {showTournaments && (
                  <button
                    type="button"
                    role="menuitem"
                    className={`his-dropdown-item${section === 'tournaments' ? ' active' : ''}`}
                    onClick={() => {
                      soundEngine.play('click');
                      setSection('tournaments');
                      setDropdownOpen(false);
                    }}
                  >
                    <span>Tournaments</span>
                  </button>
                )}
                <button
                  type="button"
                  role="menuitem"
                  className={`his-dropdown-item${section === 'private' ? ' active' : ''}`}
                  onClick={() => {
                    soundEngine.play('click');
                    setSection('private');
                    setDropdownOpen(false);
                  }}
                >
                  <span>Private Rooms</span>
                </button>
              </div>
            )}
          </div>

          <button
            type="button"
            role="tab"
            aria-selected={section === 'wallet'}
            className={`his-tab-btn${section === 'wallet' ? ' active' : ''}`}
            onClick={() => {
              soundEngine.play('click');
              setDropdownOpen(false);
              setSection('wallet');
            }}
          >
            Transactions
          </button>
        </div>

        {section === 'matches' && (
          <>
            <div className="his-summary-strip">
              <div className="his-stat">
                <span className="his-stat-label">Matches</span>
                <strong className="his-stat-value">{matchStats.total}</strong>
              </div>
              <div className="his-stat">
                <span className="his-stat-label">Won</span>
                <strong className="his-stat-value his-stat-value--gold">{matchStats.wins}</strong>
              </div>
              <div className="his-stat">
                <span className="his-stat-label">Win Rate</span>
                <strong className="his-stat-value">{matchStats.winRate}%</strong>
              </div>
              <div className="his-stat">
                {matchStats.hasNet ? (
                  <>
                    <span className="his-stat-label">Net P&amp;L</span>
                    <strong
                      className={`his-stat-value ${
                        matchStats.netTotal > 0
                          ? 'his-tx-amount--credit'
                          : matchStats.netTotal < 0
                            ? 'his-tx-amount--debit'
                            : ''
                      }`}
                    >
                      {matchStats.netTotal > 0
                        ? `+₹ ${formatInr(matchStats.netTotal)}`
                        : matchStats.netTotal < 0
                          ? `-₹ ${formatInr(Math.abs(matchStats.netTotal))}`
                          : '₹ 0'}
                    </strong>
                  </>
                ) : (
                  <>
                    <span className="his-stat-label">Best Score</span>
                    <strong className="his-stat-value">
                      {matchStats.bestScore !== null ? `${matchStats.bestScore} pts` : '—'}
                    </strong>
                  </>
                )}
              </div>
            </div>

            {variantFilters && variantFilters.length > 1 && (
              <div className="his-subfilters" role="group" aria-label="Filter by variant">
                {variantFilters.map((filter) => (
                  <button
                    key={filter.id}
                    type="button"
                    className={`his-chip${selectedFilterId === filter.id ? ' active' : ''}`}
                    onClick={() => {
                      soundEngine.play('click');
                      setSelectedFilterId(filter.id);
                    }}
                  >
                    {filter.label}
                  </button>
                ))}
              </div>
            )}

            <div className="his-scroll">
              {loadingMatches ? (
                <div className="his-empty">Loading match history...</div>
              ) : matchError ? (
                <div className="his-empty his-empty--error">{matchError}</div>
              ) : filteredMatches.length === 0 ? (
                <div className="his-empty">No completed matches found yet.</div>
              ) : (
                <div className="his-list">
                  {filteredMatches.map((item) => {
                    const sourceTag = formatMatchSource(item.tableId);
                    const hasNetRs = typeof item.netAmountRs === 'number';
                    const netAmount = hasNetRs ? (item.netAmountRs ?? 0) : null;
                    const amountText =
                      netAmount === null
                        ? `${item.finalScore} pts`
                        : netAmount > 0
                          ? `+₹ ${formatInr(netAmount)}`
                          : netAmount < 0
                            ? `-₹ ${formatInr(Math.abs(netAmount))}`
                            : '₹ 0';
                    const amountClass =
                      netAmount === null || netAmount === 0
                        ? 'his-row-score'
                        : netAmount > 0
                          ? 'his-tx-amount his-tx-amount--credit'
                          : 'his-tx-amount his-tx-amount--debit';
                    return (
                      <div key={item.id || `${item.gameId}-${item.createdAt}`} className="his-row">
                        <div className="his-row-main">
                          <div className="his-row-title-line">
                            <span className="his-row-variant">{formatRulesetLabel(item.rulesetId)}</span>
                            {sourceTag && <span className="his-row-tag">{sourceTag}</span>}
                            <span className="his-row-ref">#{shortGameRef(item.gameId)}</span>
                          </div>
                          <div className="his-row-date">{formatDateTime(item.createdAt)}</div>
                        </div>

                        <div className="his-row-meta">
                          <span className={amountClass}>{amountText}</span>
                          <span className={`his-badge ${item.won ? 'his-badge--won' : 'his-badge--lost'}`}>
                            {formatResultLabel(item.status, item.won)}
                          </span>
                        </div>
                      </div>
                    );
                  })}
                </div>
              )}
            </div>
          </>
        )}

        {section === 'tournaments' && (
          <>
            <div className="his-summary-strip">
              <div className="his-stat">
                <span className="his-stat-label">Played</span>
                <strong className="his-stat-value">{tournamentStats.total}</strong>
              </div>
              <div className="his-stat">
                <span className="his-stat-label">Won</span>
                <strong className="his-stat-value his-stat-value--gold">{tournamentStats.won}</strong>
              </div>
              <div className="his-stat">
                <span className="his-stat-label">Win Rate</span>
                <strong className="his-stat-value">{tournamentStats.winRate}%</strong>
              </div>
              <div className="his-stat">
                <span className="his-stat-label">Prize Won</span>
                <strong className="his-stat-value his-stat-value--gold">
                  ₹ {formatInr(tournamentStats.totalPrize)}
                </strong>
              </div>
            </div>

            <div className="his-scroll">
              {loadingTournaments ? (
                <div className="his-empty">Loading tournament history...</div>
              ) : tournamentError ? (
                <div className="his-empty his-empty--error">{tournamentError}</div>
              ) : tournaments.length === 0 ? (
                <div className="his-empty">No tournament history found yet.</div>
              ) : (
                <div className="his-list">
                  {tournaments.map((t) => {
                    const display = formatTournamentDisplay(t);
                    const amountClass =
                      display.amountTone === 'credit'
                        ? 'his-tx-amount his-tx-amount--credit'
                        : display.amountTone === 'debit'
                          ? 'his-tx-amount his-tx-amount--debit'
                          : 'his-row-score';
                    return (
                      <div key={t.tournamentId} className="his-row">
                        <div className="his-row-main">
                          <div className="his-row-title-line">
                            <span className="his-row-variant">{t.title || 'Tournament'}</span>
                            <span className="his-row-tag">{formatRulesetLabel(t.variant)}</span>
                          </div>
                          <div className="his-row-date">{formatDateTime(t.startsAt)}</div>
                        </div>

                        <div className="his-row-meta">
                          <span className={amountClass}>{display.amountText}</span>
                          <span className={`his-badge his-badge--${display.badgeTone}`}>
                            {display.badgeLabel}
                          </span>
                        </div>
                      </div>
                    );
                  })}
                </div>
              )}
            </div>
          </>
        )}

        {section === 'private' && (
          <>
            <div className="his-summary-strip">
              <div className="his-stat">
                <span className="his-stat-label">Rooms</span>
                <strong className="his-stat-value">{privateStats.total}</strong>
              </div>
              <div className="his-stat">
                <span className="his-stat-label">Created</span>
                <strong className="his-stat-value">{privateStats.hosted}</strong>
              </div>
              <div className="his-stat">
                <span className="his-stat-label">Won</span>
                <strong className="his-stat-value his-stat-value--gold">{privateStats.won}</strong>
              </div>
              <div className="his-stat">
                <span className="his-stat-label">Completed</span>
                <strong className="his-stat-value">{privateStats.completed}</strong>
              </div>
            </div>

            {variantFilters && variantFilters.length > 1 && (
              <div className="his-subfilters" role="group" aria-label="Filter by variant">
                {variantFilters.map((filter) => (
                  <button
                    key={filter.id}
                    type="button"
                    className={`his-chip${selectedFilterId === filter.id ? ' active' : ''}`}
                    onClick={() => {
                      soundEngine.play('click');
                      setSelectedFilterId(filter.id);
                    }}
                  >
                    {filter.label}
                  </button>
                ))}
              </div>
            )}

            <div className="his-scroll">
              {loadingPrivate ? (
                <div className="his-empty">Loading private rooms...</div>
              ) : privateError ? (
                <div className="his-empty his-empty--error">{privateError}</div>
              ) : filteredPrivateRooms.length === 0 ? (
                <div className="his-empty">No private room history found yet.</div>
              ) : (
                <div className="his-list">
                  {filteredPrivateRooms.map((room) => {
                    const statusInfo = formatPrivateRoomStatus(room, playerId);
                    const wonPayout =
                      room.winnerPlayerId === playerId && typeof room.winnerPayoutRs === 'number' && room.winnerPayoutRs > 0;
                    return (
                      <div key={room.roomCode} className="his-row">
                        <div className="his-row-main">
                          <div className="his-row-title-line">
                            <span className="his-row-variant">{formatRulesetLabel(room.rulesetId)}</span>
                            <span className="his-row-tag">{room.isHost ? 'Created' : 'Joined'}</span>
                            <span className="his-row-ref">#{room.roomCode}</span>
                          </div>
                          <div className="his-row-date">
                            {formatDateTime(room.createdAt)} · {room.stakeTier > 0 ? `₹ ${formatInr(room.stakeTier)}` : 'Free'} · {room.players?.length ?? 0}/{room.maxPlayers}P
                            {room.winnerDisplayName && statusInfo.tone !== 'won' ? ` · Winner: ${room.winnerDisplayName}` : ''}
                          </div>
                        </div>

                        <div className="his-row-meta">
                          <span className={wonPayout ? 'his-tx-amount his-tx-amount--credit' : 'his-row-score'}>
                            {wonPayout
                              ? `+₹ ${formatInr(room.winnerPayoutRs ?? 0)}`
                              : room.stakeTier > 0
                                ? `Stake ₹ ${formatInr(room.stakeTier)}`
                                : 'Free'}
                          </span>
                          <span className={`his-badge his-badge--${statusInfo.tone}`}>
                            {statusInfo.label}
                          </span>
                        </div>
                      </div>
                    );
                  })}
                </div>
              )}
            </div>
          </>
        )}

        {section === 'wallet' && (
          <>
            <div className="his-summary-strip his-summary-strip--wallet">
              <div className="his-stat">
                <span className="his-stat-label">Balance</span>
                <strong className="his-stat-value his-stat-value--gold">
                  ₹ {formatInr(walletBalance ?? 0)}
                </strong>
              </div>
              <div className="his-stat">
                <span className="his-stat-label">Transactions</span>
                <strong className="his-stat-value">{transactions.length}</strong>
              </div>
            </div>

            <div className="his-scroll">
              {loadingTx ? (
                <div className="his-empty">Loading transactions...</div>
              ) : txError ? (
                <div className="his-empty his-empty--error">{txError}</div>
              ) : transactions.length === 0 ? (
                <div className="his-empty">No wallet transactions recorded yet.</div>
              ) : (
                <div className="his-list">
                  {transactions.map((tx) => {
                    const credit = isCreditTx(tx.transactionType, tx.description);
                    const label =
                      TX_LABELS[tx.transactionType] ??
                      tx.description ??
                      tx.transactionType.replace(/_/g, ' ');
                    return (
                      <div key={tx.id} className="his-row">
                        <div className="his-row-main">
                          <div className="his-row-title-line">
                            <span className="his-row-variant">{label}</span>
                          </div>
                          <div className="his-row-date">{formatDateTime(tx.createdAt)}</div>
                        </div>

                        <div className="his-row-meta">
                          <span className={`his-tx-amount ${credit ? 'his-tx-amount--credit' : 'his-tx-amount--debit'}`}>
                            {credit ? '+' : '-'}₹ {formatInr(Math.abs(tx.amount))}
                          </span>
                          <span className="his-row-date">Bal ₹ {formatInr(tx.balanceAfter)}</span>
                        </div>
                      </div>
                    );
                  })}
                </div>
              )}
            </div>
          </>
        )}
      </div>
    </div>
  );
};
