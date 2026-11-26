import { getApiBaseUrl } from './apiConfig';
import { authFetch, getAuthenticatedPlayerId } from './authClient';
import type { VisualCardGroup } from '../types/game';

const KEYS = {
  playerId: 'rummy_player_id',
  activeTable: 'rummy_active_table',
  displayName: 'rummy_display_name',
  lastConfig: 'rummy_last_game_config',
  cardGroups: 'rummy_card_groups',
} as const;

export interface PersistedCardGroupData {
  tableId: string;
  gameId?: string;
  dealNumber?: number;
  groups: VisualCardGroup[];
  isSorted?: boolean;
  savedAt: number;
}

function storageGet(key: string): string | null {
  try {
    return localStorage.getItem(key) ?? sessionStorage.getItem(key);
  } catch {
    return null;
  }
}

function storageSet(key: string, value: string): void {
  try {
    localStorage.setItem(key, value);
    sessionStorage.setItem(key, value);
  } catch {
    // ignore
  }
}

function storageRemove(key: string): void {
  try {
    localStorage.removeItem(key);
    sessionStorage.removeItem(key);
  } catch {
    // ignore
  }
}

export function getOrCreatePlayerId(): string {
  const fromToken = getAuthenticatedPlayerId();
  if (fromToken) return fromToken;
  const existing = storageGet(KEYS.playerId);
  if (existing) return existing;
  const newId = 'PLAYER_' + Math.floor(1000 + Math.random() * 9000);
  storageSet(KEYS.playerId, newId);
  return newId;
}

export function persistActiveSession(opts: {
  tableId: string;
  playerId: string;
  displayName: string;
  lastGameConfig?: { rulesetId: string; entryFee: number; maxPlayers: number } | null;
}): void {
  storageSet(KEYS.playerId, opts.playerId);
  storageSet(KEYS.activeTable, opts.tableId);
  storageSet(KEYS.displayName, opts.displayName);
  if (opts.lastGameConfig) {
    storageSet(KEYS.lastConfig, JSON.stringify(opts.lastGameConfig));
  }
}

export function clearActiveSessionLocal(): void {
  storageRemove(KEYS.activeTable);
  clearPersistedCardGroups();
}

export function persistCardGroups(
  tableId: string,
  gameId: string | undefined,
  groups: VisualCardGroup[],
  isSorted?: boolean,
  dealNumber?: number
): void {
  if (!tableId || !groups || groups.length === 0) return;
  const data: PersistedCardGroupData = {
    tableId,
    gameId,
    dealNumber,
    groups,
    isSorted: isSorted ?? (groups.length > 1),
    savedAt: Date.now(),
  };
  storageSet(KEYS.cardGroups, JSON.stringify(data));
}

export function readPersistedCardGroups(
  expectedTableId?: string,
  expectedGameId?: string,
  expectedDealNumber?: number
): VisualCardGroup[] | null {
  const raw = storageGet(KEYS.cardGroups);
  if (!raw) return null;
  try {
    const data = JSON.parse(raw) as PersistedCardGroupData;
    if (!data || !Array.isArray(data.groups) || data.groups.length === 0) {
      return null;
    }
    if (expectedTableId && data.tableId && data.tableId !== expectedTableId) {
      return null;
    }
    if (expectedGameId && data.gameId && data.gameId !== expectedGameId) {
      return null;
    }
    if (expectedDealNumber != null && data.dealNumber != null && data.dealNumber !== expectedDealNumber) {
      return null;
    }
    // Safeguard: discard data older than 2 hours
    if (data.savedAt && Date.now() - data.savedAt > 2 * 60 * 60 * 1000) {
      storageRemove(KEYS.cardGroups);
      return null;
    }
    return data.groups;
  } catch {
    return null;
  }
}

export function clearPersistedCardGroups(): void {
  storageRemove(KEYS.cardGroups);
}

export function readPersistedDisplayName(): string | null {
  return storageGet(KEYS.displayName);
}

export function readPersistedActiveTable(): string | null {
  return storageGet(KEYS.activeTable);
}

export function readPersistedLastConfig(): {
  rulesetId: string;
  entryFee: number;
  maxPlayers: number;
} | null {
  const raw = storageGet(KEYS.lastConfig);
  if (!raw) return null;
  try {
    return JSON.parse(raw) as { rulesetId: string; entryFee: number; maxPlayers: number };
  } catch {
    return null;
  }
}

export interface ActiveSessionResponse {
  active: boolean;
  tableId?: string;
  serverInstanceId?: string;
  gameStatus?: string;
  playerStatus?: string;
  displayName?: string;
  gameId?: string;
}

/**
 * Ask backend if this player still has a resumable in-progress table.
 * Returns null on network/timeout so the caller can fall back to localStorage.
 */
export async function fetchActiveSession(
  playerId: string
): Promise<ActiveSessionResponse | null> {
  const controller = new AbortController();
  const timer = window.setTimeout(() => controller.abort(), 2500);
  try {
    const res = await authFetch(
      `${getApiBaseUrl()}/api/session/active?playerId=${encodeURIComponent(playerId)}`,
      { signal: controller.signal }
    );
    if (!res.ok) return null;
    return (await res.json()) as ActiveSessionResponse;
  } catch {
    return null;
  } finally {
    window.clearTimeout(timer);
  }
}

export async function clearActiveSessionRemote(playerId: string): Promise<void> {
  try {
    await authFetch(`${getApiBaseUrl()}/api/session/clear`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ playerId }),
    });
  } catch {
    // offline leave — local clear still applies
  }
}
