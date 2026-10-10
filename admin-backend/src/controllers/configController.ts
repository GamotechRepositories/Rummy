import { Request, Response } from 'express';
import { AuditLog } from '../models/AuditLog';
import { AdminAuthRequest } from '../middleware/authMiddleware';

// In-memory / MongoDB config store for dynamic game parameters
const DEFAULT_CONFIGS: Record<string, any> = {
  POINTS_13: {
    variantName: 'Points Rummy (13 Cards)',
    cardsPerPlayer: 13,
    turnTimeoutSeconds: 15,
    extraTimeSeconds: 10,
    declareTimeoutSeconds: 30,
    firstDropPenalty: 20,
    middleDropPenalty: 40,
    wrongDeclarePenalty: 80,
    rakePercentage: 10.0,
    enabledStakeTiers: [0.05, 0.1, 0.25, 0.5, 1, 2, 5, 10, 25, 50],
  },
  DEALS_2: {
    variantName: 'Deals Rummy (Best of 2)',
    totalDeals: 2,
    cardsPerPlayer: 13,
    turnTimeoutSeconds: 15,
    declareTimeoutSeconds: 30,
    interDealCountdownSeconds: 5,
    firstDropPenalty: 20,
    middleDropPenalty: 40,
    wrongDeclarePenalty: 80,
    rakePercentage: 10.0,
  },
  DEALS_3: {
    variantName: 'Deals Rummy (Best of 3)',
    totalDeals: 3,
    cardsPerPlayer: 13,
    turnTimeoutSeconds: 15,
    declareTimeoutSeconds: 30,
    interDealCountdownSeconds: 5,
    firstDropPenalty: 20,
    middleDropPenalty: 40,
    wrongDeclarePenalty: 80,
    rakePercentage: 10.0,
  },
  POOL_101: {
    variantName: 'Pool Rummy (101 Pool)',
    eliminationThreshold: 101,
    cardsPerPlayer: 13,
    turnTimeoutSeconds: 15,
    firstDropPenalty: 20,
    middleDropPenalty: 40,
    wrongDeclarePenalty: 80,
    rejoinAllowed: true,
    rejoinMaxScore: 79,
    rakePercentage: 10.0,
  },
  POOL_201: {
    variantName: 'Pool Rummy (201 Pool)',
    eliminationThreshold: 201,
    cardsPerPlayer: 13,
    turnTimeoutSeconds: 15,
    firstDropPenalty: 25,
    middleDropPenalty: 50,
    wrongDeclarePenalty: 80,
    rejoinAllowed: true,
    rejoinMaxScore: 174,
    rakePercentage: 10.0,
  },
  RUMMY_21: {
    variantName: '21-Card Rummy',
    cardsPerPlayer: 21,
    turnTimeoutSeconds: 20,
    declareTimeoutSeconds: 45,
    firstDropPenalty: 30,
    middleDropPenalty: 60,
    wrongDeclarePenalty: 120,
    rakePercentage: 10.0,
  },
};

const liveOverrides: Record<string, any> = {};

export async function getRulesetConfigs(req: Request, res: Response): Promise<void> {
  const merged: Record<string, any> = {};
  for (const [key, val] of Object.entries(DEFAULT_CONFIGS)) {
    merged[key] = { ...val, ...(liveOverrides[key] || {}) };
  }
  res.json({ success: true, configs: merged });
}

export async function updateRulesetConfig(req: AdminAuthRequest, res: Response): Promise<void> {
  try {
    const { variantId } = req.params;
    const vId = String(variantId || '').toUpperCase();
    const updates = req.body;

    if (!DEFAULT_CONFIGS[vId]) {
      res.status(404).json({ success: false, message: `Unknown variant ruleset: ${variantId}` });
      return;
    }

    liveOverrides[vId] = { ...(liveOverrides[vId] || {}), ...updates };

    if (req.adminUser) {
      await AuditLog.create({
        adminId: req.adminUser.id,
        adminUsername: req.adminUser.username,
        action: 'UPDATE_RULESET_CONFIG',
        resource: `RULESET:${vId}`,
        details: updates,
      });
    }

    res.json({
      success: true,
      message: `Config updated for ${vId}`,
      config: { ...DEFAULT_CONFIGS[vId], ...liveOverrides[vId] },
    });
  } catch (err: any) {
    res.status(500).json({ success: false, message: err.message });
  }
}
