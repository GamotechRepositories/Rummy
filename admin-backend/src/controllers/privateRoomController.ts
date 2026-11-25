import { Request, Response } from 'express';
import { PrivateRoom } from '../models/PrivateRoomModel';
import { Game } from '../models/GameModels';
import { AuditLog } from '../models/AuditLog';
import { AdminAuthRequest } from '../middleware/authMiddleware';

/**
 * List all player-created Private Rooms + aggregated Profit & Loss KPIs.
 */
export async function listPrivateRooms(req: Request, res: Response): Promise<void> {
  try {
    const { status, variant, search, from, to } = req.query;

    const dateQuery: Record<string, any> = {};
    if (typeof from === 'string' && from.trim()) {
      const d = new Date(from.trim());
      if (!isNaN(d.getTime())) dateQuery.$gte = d;
    }
    if (typeof to === 'string' && to.trim()) {
      const d = new Date(to.trim());
      if (!isNaN(d.getTime())) {
        d.setHours(23, 59, 59, 999);
        dateQuery.$lte = d;
      }
    }

    const mongoQuery = Object.keys(dateQuery).length > 0 ? { createdAt: dateQuery } : {};
    const allRooms = await PrivateRoom.find(mongoQuery).sort({ createdAt: -1 }).limit(500).lean();

    // Compute overall P&L summary across all rooms
    let waitingRooms = 0;
    let runningRooms = 0;
    let completedRooms = 0;
    let cancelledOrExpiredRooms = 0;
    let totalGrossCollectionRs = 0;
    let totalPlatformRakeRs = 0;
    let totalWinnerPayoutRs = 0;
    let totalRefundedRs = 0;

    const byVariant: Record<string, { rooms: number; collectionRs: number; rakeProfitRs: number; payoutRs: number }> = {};

    for (const r of allRooms) {
      if (r.status === 'WAITING') waitingRooms++;
      else if (r.status === 'RUNNING') runningRooms++;
      else if (r.status === 'COMPLETED') completedRooms++;
      else cancelledOrExpiredRooms++;

      totalGrossCollectionRs += r.grossCollectionRs || 0;
      totalPlatformRakeRs += r.platformRakeRs || 0;
      totalWinnerPayoutRs += r.winnerPayoutRs || 0;
      totalRefundedRs += r.refundedTotalRs || 0;

      const vKey = r.rulesetId || 'UNKNOWN';
      if (!byVariant[vKey]) {
        byVariant[vKey] = { rooms: 0, collectionRs: 0, rakeProfitRs: 0, payoutRs: 0 };
      }
      byVariant[vKey].rooms++;
      byVariant[vKey].collectionRs += r.grossCollectionRs || 0;
      byVariant[vKey].rakeProfitRs += r.platformRakeRs || 0;
      byVariant[vKey].payoutRs += r.winnerPayoutRs || 0;
    }

    // Apply filters for the table rows
    const qStatus = typeof status === 'string' && status !== 'ALL' ? status.toUpperCase() : null;
    const qVariant = typeof variant === 'string' && variant !== 'ALL' ? variant.toUpperCase() : null;
    const qSearch = typeof search === 'string' && search.trim() ? search.trim().toLowerCase() : null;

    const filtered = allRooms.filter((r) => {
      if (qStatus && r.status !== qStatus) return false;
      if (qVariant && r.rulesetId !== qVariant) return false;
      if (qSearch) {
        const hit =
          (r.roomCode || '').toLowerCase().includes(qSearch) ||
          (r.hostDisplayName || '').toLowerCase().includes(qSearch) ||
          (r.hostPlayerId || '').toLowerCase().includes(qSearch) ||
          (r.tableId || '').toLowerCase().includes(qSearch);
        if (!hit) return false;
      }
      return true;
    });

    res.json({
      success: true,
      summary: {
        totalRooms: allRooms.length,
        waitingRooms,
        runningRooms,
        completedRooms,
        cancelledOrExpiredRooms,
        totalGrossCollectionRs: Math.round(totalGrossCollectionRs * 100) / 100,
        totalPlatformRakeRs: Math.round(totalPlatformRakeRs * 100) / 100,
        totalWinnerPayoutRs: Math.round(totalWinnerPayoutRs * 100) / 100,
        totalRefundedRs: Math.round(totalRefundedRs * 100) / 100,
        byVariant,
      },
      rooms: filtered,
    });
  } catch (err: any) {
    res.status(500).json({ success: false, message: err.message });
  }
}

export async function getPrivateRoom(req: Request, res: Response): Promise<void> {
  try {
    const roomCode = String(req.params.roomCode || '').trim().toUpperCase();
    const room = await PrivateRoom.findOne({ roomCode }).lean();
    if (!room) {
      res.status(404).json({ success: false, message: `Private room ${roomCode} not found.` });
      return;
    }
    res.json({ success: true, room });
  } catch (err: any) {
    res.status(500).json({ success: false, message: err.message });
  }
}

export async function cancelPrivateRoom(req: AdminAuthRequest, res: Response): Promise<void> {
  try {
    const roomCode = String(req.params.roomCode || '').trim().toUpperCase();
    const { reason } = req.body || {};

    const room = await PrivateRoom.findOne({ roomCode });
    if (!room) {
      res.status(404).json({ success: false, message: `Private room ${roomCode} not found.` });
      return;
    }
    if (room.status !== 'WAITING' && room.status !== 'RUNNING') {
      res.status(400).json({
        success: false,
        message: `Only WAITING or RUNNING rooms can be cancelled (current status: ${room.status}).`,
      });
      return;
    }

    const wasRunning = room.status === 'RUNNING';
    room.status = 'CANCELLED';
    room.cancelledReason = reason || (wasRunning ? 'Force aborted by administrator' : 'Cancelled by administrator');
    room.finishedAt = new Date();
    await room.save();

    if (wasRunning && room.tableId) {
      await Game.updateMany(
        { tableId: room.tableId, status: { $in: ['IN_PROGRESS', 'SHOWDOWN', 'WAITING_FOR_PLAYERS'] } },
        { $set: { status: 'ABORTED', finishedAt: new Date() } }
      );
    }

    if (req.adminUser) {
      await AuditLog.create({
        adminId: req.adminUser.id,
        adminUsername: req.adminUser.username,
        action: 'CANCEL_PRIVATE_ROOM',
        resource: `PRIVATE_ROOM:${roomCode}`,
        details: { reason: room.cancelledReason, playerCount: room.players.length },
      });
    }

    res.json({
      success: true,
      message: `Room ${roomCode} marked CANCELLED. game-service will refund all players.`,
      room,
    });
  } catch (err: any) {
    res.status(500).json({ success: false, message: err.message });
  }
}
