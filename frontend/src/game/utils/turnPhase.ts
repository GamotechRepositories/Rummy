import type { TurnPhase } from '../types/game';

/**
 * Backend sends AWAITING_DRAW / AWAITING_DISCARD.
 * UI historically used DRAW / DISCARD. Normalize both.
 */
export type UiTurnPhase = 'DRAW' | 'DISCARD' | 'FINISH' | null;

export function normalizeTurnPhase(phase: string | TurnPhase | null | undefined): UiTurnPhase {
  if (!phase) return null;
  const p = String(phase).toUpperCase();
  if (p === 'DRAW' || p === 'AWAITING_DRAW') return 'DRAW';
  if (p === 'DISCARD' || p === 'AWAITING_DISCARD') return 'DISCARD';
  if (p === 'FINISH' || p === 'AWAITING_DECLARE_VALIDATION') return 'FINISH';
  return null;
}

export function isDrawPhase(isMyTurn: boolean, phase: string | null | undefined): boolean {
  return isMyTurn && normalizeTurnPhase(phase) === 'DRAW';
}

export function isDiscardPhase(isMyTurn: boolean, phase: string | null | undefined): boolean {
  return isMyTurn && normalizeTurnPhase(phase) === 'DISCARD';
}
