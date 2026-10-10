import { Request, Response } from 'express';
import { Game, GameResult, GameEvent } from '../models/GameModels';

export async function getGameReplay(req: Request, res: Response): Promise<void> {
  try {
    const { gameId } = req.params;
    if (!gameId) {
      res.status(400).json({ success: false, message: 'gameId parameter is required' });
      return;
    }

    const [game, results, events] = await Promise.all([
      Game.findOne({ gameId }).lean(),
      GameResult.find({ gameId }).lean(),
      GameEvent.find({ gameId }).sort({ sequence: 1 }).lean(),
    ]);

    if (!game && events.length === 0) {
      res.status(404).json({ success: false, message: `Game record ${gameId} not found` });
      return;
    }

    res.json({
      success: true,
      game,
      results,
      eventsCount: events.length,
      events,
    });
  } catch (err: any) {
    res.status(500).json({ success: false, message: err.message });
  }
}
