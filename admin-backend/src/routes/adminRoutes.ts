import { Router } from 'express';
import { login, getProfile } from '../controllers/authController';
import { getOverviewMetrics, toggleNodeDrain } from '../controllers/overviewController';
import { getVariantMetrics } from '../controllers/variantController';
import { getLiveTables, terminateTable } from '../controllers/tableController';
import { getGameReplay } from '../controllers/replayController';
import { getGameDetails } from '../controllers/gameDetailController';
import { getRulesetConfigs, updateRulesetConfig } from '../controllers/configController';
import {
  listTournaments,
  getTournament,
  createTournament,
  updateTournament,
  publishTournament,
  cancelTournament,
  getTournamentRegistrations,
  registerPlayer,
  listPublicTournaments,
} from '../controllers/tournamentController';
import {
  listPrivateRooms,
  getPrivateRoom,
  cancelPrivateRoom,
} from '../controllers/privateRoomController';
import { authMiddleware } from '../middleware/authMiddleware';
import { requireRole } from '../middleware/rbacMiddleware';

const router = Router();

// Public auth routes
router.post('/auth/login', login);

// Protected routes (require JWT)
router.get('/auth/me', authMiddleware, getProfile);

// Overview / Main Dashboard
router.get('/overview/metrics', authMiddleware, getOverviewMetrics);
router.post('/overview/drain', authMiddleware, requireRole('SUPER_ADMIN'), toggleNodeDrain);

// Variant-specific dashboards
router.get('/variants/:variantId/metrics', authMiddleware, getVariantMetrics);

// Live Tables Spectator
router.get('/tables/live', authMiddleware, getLiveTables);
router.post('/tables/:tableId/terminate', authMiddleware, requireRole('SUPER_ADMIN', 'GAME_OPERATOR'), terminateTable);

// Game Details & Dispute Replay
router.get('/games/:gameId', authMiddleware, getGameDetails);
router.get('/replay/:gameId', authMiddleware, getGameReplay);

// Rules & Rake configuration
router.get('/config/rulesets', authMiddleware, getRulesetConfigs);
router.put('/config/rulesets/:variantId', authMiddleware, requireRole('SUPER_ADMIN'), updateRulesetConfig);

// ── Tournament Management ────────────────────────────────────────────────────
// Admin-only management routes
router.get('/tournaments', authMiddleware, listTournaments);
router.get('/tournaments/:id', authMiddleware, getTournament);
router.post('/tournaments', authMiddleware, requireRole('SUPER_ADMIN', 'GAME_OPERATOR'), createTournament);
router.patch('/tournaments/:id', authMiddleware, requireRole('SUPER_ADMIN', 'GAME_OPERATOR'), updateTournament);
router.post('/tournaments/:id/publish', authMiddleware, requireRole('SUPER_ADMIN', 'GAME_OPERATOR'), publishTournament);
router.post('/tournaments/:id/cancel', authMiddleware, requireRole('SUPER_ADMIN', 'GAME_OPERATOR'), cancelTournament);
router.get('/tournaments/:id/registrations', authMiddleware, getTournamentRegistrations);

// Public player-facing routes (called by game-service / frontend)
router.get('/public/tournaments', listPublicTournaments);
router.post('/public/tournaments/:id/register', registerPlayer);

// ── Private Rooms (Create Room Management & P&L) ─────────────────────────────
router.get('/private-rooms', authMiddleware, listPrivateRooms);
router.get('/private-rooms/:roomCode', authMiddleware, getPrivateRoom);
router.post('/private-rooms/:roomCode/cancel', authMiddleware, requireRole('SUPER_ADMIN', 'GAME_OPERATOR'), cancelPrivateRoom);

export default router;
