import { Router } from 'express';
import { login, getProfile } from '../controllers/authController';
import { getOverviewMetrics, toggleNodeDrain } from '../controllers/overviewController';
import { getVariantMetrics } from '../controllers/variantController';
import { getLiveTables, terminateTable } from '../controllers/tableController';
import { getGameReplay } from '../controllers/replayController';
import { getGameDetails } from '../controllers/gameDetailController';
import { getRulesetConfigs, updateRulesetConfig } from '../controllers/configController';
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

export default router;
