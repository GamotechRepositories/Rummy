import { Request, Response } from 'express';
import axios from 'axios';
import { Game } from '../models/GameModels';
import { AuditLog } from '../models/AuditLog';
import { ENV } from '../config/env';
import { AdminAuthRequest } from '../middleware/authMiddleware';

export async function getLiveTables(req: Request, res: Response): Promise<void> {
  try {
    const { variant } = req.query;

    // Fetch active games from MongoDB where status == 'IN_PROGRESS' or 'SHOWDOWN'
    const query: any = {
      status: { $in: ['IN_PROGRESS', 'SHOWDOWN', 'WAITING_FOR_PLAYERS'] },
    };

    if (variant && typeof variant === 'string' && variant !== 'ALL') {
      query.rulesetId = new RegExp(variant, 'i');
    }

    const liveGames = await Game.find(query).sort({ startedAt: -1 }).limit(50).lean();

    res.json({
      success: true,
      count: liveGames.length,
      tables: liveGames,
    });
  } catch (err: any) {
    res.status(500).json({ success: false, message: err.message });
  }
}

export async function terminateTable(req: AdminAuthRequest, res: Response): Promise<void> {
  try {
    const { tableId } = req.params;
    const { reason } = req.body;

    if (!tableId) {
      res.status(400).json({ success: false, message: 'tableId is required' });
      return;
    }

    // Update MongoDB record
    await Game.updateMany(
      { tableId, status: { $in: ['IN_PROGRESS', 'SHOWDOWN', 'WAITING_FOR_PLAYERS'] } },
      { $set: { status: 'ABORTED', finishedAt: new Date() } }
    );

    if (req.adminUser) {
      await AuditLog.create({
        adminId: req.adminUser.id,
        adminUsername: req.adminUser.username,
        action: 'FORCE_TERMINATE_TABLE',
        resource: `TABLE:${tableId}`,
        details: { reason: reason || 'Terminated by administrator' },
      });
    }

    res.json({
      success: true,
      message: `Table ${tableId} has been safely terminated and closed.`,
    });
  } catch (err: any) {
    res.status(500).json({ success: false, message: err.message });
  }
}
