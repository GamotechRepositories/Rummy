import { Request, Response } from 'express';
import { randomBytes } from 'crypto';
import { Tournament, ITournamentPrize } from '../models/TournamentModel';
import { Game, GameResult } from '../models/GameModels';
import { AdminAuthRequest } from '../middleware/authMiddleware';

// ─────────────────────────────────────────────────────────────────────────────
// Default prize distribution: top 10% of maxPlayers get paid
// ─────────────────────────────────────────────────────────────────────────────
const DEFAULT_PRIZES: ITournamentPrize[] = [
  { rank: 1, percentage: 100 },
];

// ─────────────────────────────────────────────────────────────────────────────
// GET /api/admin/tournaments  — list all (admin view)
// ─────────────────────────────────────────────────────────────────────────────
export async function listTournaments(req: Request, res: Response): Promise<void> {
  try {
    const { status, limit = 50 } = req.query;
    const filter: any = {};
    if (status) filter.status = status;

    const tournaments = await Tournament.find(filter)
      .sort({ startsAt: -1 })
      .limit(Number(limit))
      .lean();

    res.json({ success: true, count: tournaments.length, tournaments });
  } catch (err: any) {
    res.status(500).json({ success: false, message: err.message });
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// GET /api/admin/tournaments/:id  — single tournament full detail (rounds, tables, player journey)
// ─────────────────────────────────────────────────────────────────────────────
export async function getTournament(req: Request, res: Response): Promise<void> {
  try {
    const t: any = await Tournament.findOne({ tournamentId: req.params.id }).lean();
    if (!t) { res.status(404).json({ success: false, message: 'Tournament not found' }); return; }

    // Collect all tableIds across all rounds to fetch persisted Game & GameResult records
    const allTableIds: string[] = [];
    for (const r of (t.rounds || [])) {
      for (const tid of (r.tableIds || [])) {
        allTableIds.push(tid);
      }
    }

    const [games, results] = allTableIds.length > 0
      ? await Promise.all([
          Game.find({ tableId: { $in: allTableIds } }).lean(),
          GameResult.find({ tableId: { $in: allTableIds } }).lean(),
        ])
      : [[], []];

    const gamesByTable = new Map<string, any>();
    for (const g of games as any[]) {
      // keep latest or finished game per tableId
      const existing = gamesByTable.get(g.tableId);
      if (!existing || g.status === 'COMPLETED') {
        gamesByTable.set(g.tableId, g);
      }
    }

    const resultsByTable = new Map<string, any[]>();
    for (const gr of results as any[]) {
      if (!resultsByTable.has(gr.tableId)) resultsByTable.set(gr.tableId, []);
      resultsByTable.get(gr.tableId)!.push(gr);
    }

    const displayNameMap = new Map<string, string>();
    for (const reg of (t.registrations || [])) {
      displayNameMap.set(reg.playerId, reg.displayName || reg.playerId);
    }

    // Build per-round table breakdowns
    const roundDetails = (t.rounds || []).map((r: any) => {
      const qualifiedSet = new Set<string>(r.qualifiedPlayerIds || []);
      const tables = (r.tableIds || []).map((tableId: string) => {
        const g = gamesByTable.get(tableId);
        const tableResults = resultsByTable.get(tableId) || [];
        const players = tableResults.length > 0
          ? tableResults.map((pr: any) => ({
              playerId: pr.playerId,
              displayName: pr.displayName || displayNameMap.get(pr.playerId) || pr.playerId,
              finalScore: pr.finalScore ?? 0,
              won: Boolean(pr.won) || g?.winnerPlayerId === pr.playerId,
              qualified: qualifiedSet.has(pr.playerId),
              status: pr.status || 'FINISHED',
            }))
          : (g?.players || []).map((gp: any) => ({
              playerId: gp.playerId,
              displayName: gp.displayName || displayNameMap.get(gp.playerId) || gp.playerId,
              finalScore: gp.cumulativeScore ?? gp.score ?? 0,
              won: g?.winnerPlayerId === gp.playerId,
              qualified: qualifiedSet.has(gp.playerId),
              status: gp.status || 'PLAYED',
            }));

        const winnerId = g?.winnerPlayerId || players.find((p: any) => p.won)?.playerId || null;
        return {
          tableId,
          gameId: g?.gameId || null,
          status: g?.status || r.status,
          winnerPlayerId: winnerId,
          winnerName: winnerId ? (displayNameMap.get(winnerId) || winnerId) : null,
          players,
        };
      });

      return {
        roundNumber: r.roundNumber,
        status: r.status,
        startedAt: r.startedAt,
        finishedAt: r.finishedAt,
        tableCount: tables.length,
        qualifiedPlayerIds: r.qualifiedPlayerIds || [],
        tables,
      };
    });

    // Build per-player profile & round-by-round journey
    const checkedInSet = new Set<string>(t.checkedInPlayerIds || []);
    const rankingMap = new Map<string, any>();
    for (const fr of (t.finalRankings || [])) {
      rankingMap.set(fr.playerId, fr);
    }

    const playerProfiles = (t.registrations || []).map((reg: any) => {
      const roundHistory: any[] = [];
      for (const rd of roundDetails) {
        for (const tbl of rd.tables) {
          const found = tbl.players.find((p: any) => p.playerId === reg.playerId);
          if (found) {
            roundHistory.push({
              roundNumber: rd.roundNumber,
              tableId: tbl.tableId,
              gameId: tbl.gameId,
              finalScore: found.finalScore,
              won: found.won,
              qualified: found.qualified,
              status: found.status,
            });
          }
        }
      }
      const rankEntry = rankingMap.get(reg.playerId);
      return {
        playerId: reg.playerId,
        displayName: reg.displayName || reg.playerId,
        operatorId: reg.operatorId || 'DEFAULT',
        registeredAt: reg.registeredAt,
        checkedIn: checkedInSet.has(reg.playerId),
        refunded: Boolean(reg.refunded),
        finalRank: rankEntry?.rank ?? null,
        prizeRs: rankEntry?.prizeRs ?? 0,
        roundHistory,
      };
    });

    res.json({
      success: true,
      tournament: t,
      roundDetails,
      playerProfiles,
    });
  } catch (err: any) {
    res.status(500).json({ success: false, message: err.message });
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// POST /api/admin/tournaments  — create tournament (admin only)
// ─────────────────────────────────────────────────────────────────────────────
export async function createTournament(req: AdminAuthRequest, res: Response): Promise<void> {
  try {
    const {
      title,
      minPlayers,
      maxPlayers,
      entryFeeRs,
      fixedPrizeRs,
      checkInWindowMinutes,
      prizes,
      registrationStartsAt,
      registrationEndsAt,
      startsAt,
      operatorScope,
    } = req.body;

    // Validate min/max are multiples of 6
    if (minPlayers % 6 !== 0 || maxPlayers % 6 !== 0) {
      res.status(400).json({ success: false, message: 'minPlayers and maxPlayers must be multiples of 6.' });
      return;
    }
    if (minPlayers < 6 || maxPlayers < minPlayers) {
      res.status(400).json({ success: false, message: 'minPlayers must be ≥ 6 and maxPlayers must be ≥ minPlayers.' });
      return;
    }

    const tournament = await Tournament.create({
      tournamentId: `TRN-${randomBytes(4).toString('hex').toUpperCase()}`,
      title,
      minPlayers: Number(minPlayers),
      maxPlayers: Number(maxPlayers),
      entryFeeRs: Number(entryFeeRs ?? 0),
      fixedPrizeRs: Number(fixedPrizeRs ?? 0),
      checkInWindowMinutes: Number(checkInWindowMinutes ?? 5),
      prizes: prizes && prizes.length > 0 ? prizes : DEFAULT_PRIZES,
      registrationStartsAt: new Date(registrationStartsAt),
      registrationEndsAt: new Date(registrationEndsAt),
      startsAt: new Date(startsAt),
      operatorScope: operatorScope ?? 'ALL',
      status: 'DRAFT',
      createdByAdminId: req.adminUser?.id ?? 'unknown',
    });

    res.status(201).json({ success: true, tournament });
  } catch (err: any) {
    res.status(500).json({ success: false, message: err.message });
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// PATCH /api/admin/tournaments/:id  — update (title, times, fees, minMax — only before RUNNING)
// ─────────────────────────────────────────────────────────────────────────────
export async function updateTournament(req: AdminAuthRequest, res: Response): Promise<void> {
  try {
    const t = await Tournament.findOne({ tournamentId: req.params.id });
    if (!t) { res.status(404).json({ success: false, message: 'Tournament not found' }); return; }
    if (['RUNNING', 'COMPLETED'].includes(t.status)) {
      res.status(400).json({ success: false, message: 'Cannot edit a tournament that is already RUNNING or COMPLETED.' });
      return;
    }

    const allowed = ['title', 'minPlayers', 'maxPlayers', 'entryFeeRs', 'fixedPrizeRs',
                     'checkInWindowMinutes', 'prizes', 'registrationStartsAt', 'registrationEndsAt',
                     'startsAt', 'operatorScope'];
    for (const key of allowed) {
      if (req.body[key] !== undefined) {
        (t as any)[key] = req.body[key];
      }
    }
    if (req.body.checkInWindowMinutes !== undefined) {
      t.checkInWindowMinutes = Math.max(1, Number(req.body.checkInWindowMinutes));
      if (t.status === 'STARTING' && t.startsAt) {
        t.checkInEndsAt = new Date(new Date(t.startsAt).getTime() + t.checkInWindowMinutes * 60_000);
      }
    }
    if (req.body.minPlayers !== undefined && req.body.minPlayers % 6 !== 0) {
      res.status(400).json({ success: false, message: 'minPlayers must be a multiple of 6.' });
      return;
    }
    if (req.body.maxPlayers !== undefined && req.body.maxPlayers % 6 !== 0) {
      res.status(400).json({ success: false, message: 'maxPlayers must be a multiple of 6.' });
      return;
    }

    await t.save();
    res.json({ success: true, tournament: t });
  } catch (err: any) {
    res.status(500).json({ success: false, message: err.message });
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// POST /api/admin/tournaments/:id/publish  — set status REGISTERING
// ─────────────────────────────────────────────────────────────────────────────
export async function publishTournament(req: AdminAuthRequest, res: Response): Promise<void> {
  try {
    const t = await Tournament.findOne({ tournamentId: req.params.id });
    if (!t) { res.status(404).json({ success: false, message: 'Tournament not found' }); return; }
    if (t.status !== 'DRAFT') {
      res.status(400).json({ success: false, message: `Tournament is already ${t.status}` });
      return;
    }
    t.status = 'REGISTERING';
    await t.save();
    res.json({ success: true, message: 'Tournament published and open for registration.', tournament: t });
  } catch (err: any) {
    res.status(500).json({ success: false, message: err.message });
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// POST /api/admin/tournaments/:id/cancel  — admin force-cancel with full refund
// ─────────────────────────────────────────────────────────────────────────────
export async function cancelTournament(req: AdminAuthRequest, res: Response): Promise<void> {
  try {
    const t = await Tournament.findOne({ tournamentId: req.params.id });
    if (!t) { res.status(404).json({ success: false, message: 'Tournament not found' }); return; }
    if (t.status === 'COMPLETED' || t.status === 'CANCELLED') {
      res.status(400).json({ success: false, message: `Tournament is already ${t.status}` });
      return;
    }

    t.status = 'CANCELLED';
    t.cancelledReason = req.body.reason || 'Cancelled by administrator';

    // Mark all non-refunded registrations for refund
    // (Actual wallet credit is handled by game-service when it polls status)
    for (const reg of t.registrations) {
      reg.refunded = false; // game-service will process and flip to true
    }
    await t.save();

    res.json({
      success: true,
      message: `Tournament cancelled. ${t.registrations.length} players will receive full refund.`,
      refundCount: t.registrations.length,
    });
  } catch (err: any) {
    res.status(500).json({ success: false, message: err.message });
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// GET /api/admin/tournaments/:id/registrations  — list all registered players
// ─────────────────────────────────────────────────────────────────────────────
export async function getTournamentRegistrations(req: Request, res: Response): Promise<void> {
  try {
    const t = await Tournament.findOne({ tournamentId: req.params.id }, { registrations: 1, tournamentId: 1, status: 1, minPlayers: 1, maxPlayers: 1 }).lean();
    if (!t) { res.status(404).json({ success: false, message: 'Tournament not found' }); return; }
    res.json({
      success: true,
      tournamentId: t.tournamentId,
      status: t.status,
      registered: t.registrations?.length ?? 0,
      minPlayers: t.minPlayers,
      maxPlayers: t.maxPlayers,
      registrations: t.registrations,
    });
  } catch (err: any) {
    res.status(500).json({ success: false, message: err.message });
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// POST /api/tournaments/:id/register  — PUBLIC: player registers (via game-service call)
// Entry fee debit is handled by game-service WalletService, not here.
// This endpoint only records the registration (admin-backend stores tournament metadata).
// ─────────────────────────────────────────────────────────────────────────────
export async function registerPlayer(req: Request, res: Response): Promise<void> {
  try {
    const { playerId, displayName, operatorId, entryTxnId } = req.body;
    if (!playerId || !entryTxnId) {
      res.status(400).json({ success: false, message: 'playerId and entryTxnId are required.' });
      return;
    }

    const t = await Tournament.findOne({ tournamentId: req.params.id });
    if (!t) { res.status(404).json({ success: false, message: 'Tournament not found' }); return; }
    if (t.status !== 'REGISTERING') {
      res.status(400).json({ success: false, message: `Registration is not open. Status: ${t.status}` });
      return;
    }
    if (t.registrations.length >= t.maxPlayers) {
      res.status(400).json({ success: false, message: 'Tournament is full.' });
      return;
    }
    const alreadyRegistered = t.registrations.some((r) => r.playerId === playerId);
    if (alreadyRegistered) {
      res.status(409).json({ success: false, message: 'Player already registered.' });
      return;
    }

    t.registrations.push({ playerId, displayName: displayName || playerId, operatorId: operatorId || 'DEFAULT', registeredAt: new Date(), entryTxnId, refunded: false });
    t.grossPoolRs = t.registrations.length * t.entryFeeRs;
    t.platformProfitRs = t.grossPoolRs - t.fixedPrizeRs;

    await t.save();
    res.json({
      success: true,
      message: 'Registration successful.',
      registered: t.registrations.length,
      maxPlayers: t.maxPlayers,
    });
  } catch (err: any) {
    res.status(500).json({ success: false, message: err.message });
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// GET /api/tournaments  — PUBLIC: list REGISTERING/STARTING tournaments for player lobby
// ─────────────────────────────────────────────────────────────────────────────
export async function listPublicTournaments(req: Request, res: Response): Promise<void> {
  try {
    const tournaments = await Tournament.find(
      { status: { $in: ['REGISTERING', 'STARTING', 'RUNNING'] } },
      {
        tournamentId: 1, title: 1, variant: 1, entryFeeRs: 1, fixedPrizeRs: 1,
        minPlayers: 1, maxPlayers: 1, registrationStartsAt: 1, registrationEndsAt: 1, startsAt: 1, status: 1,
        'registrations.playerId': 1,
      }
    ).sort({ startsAt: 1 }).limit(20).lean();

    const result = tournaments.map((t) => ({
      tournamentId: t.tournamentId,
      title: t.title,
      variant: t.variant,
      entryFeeRs: t.entryFeeRs,
      prizePoolRs: t.fixedPrizeRs,
      isGuaranteed: true,
      minPlayers: t.minPlayers,
      maxPlayers: t.maxPlayers,
      registered: (t.registrations ?? []).length,
      registrationStartsAt: t.registrationStartsAt,
      registrationEndsAt: t.registrationEndsAt,
      startsAt: t.startsAt,
      status: t.status,
    }));

    res.json({ success: true, tournaments: result });
  } catch (err: any) {
    res.status(500).json({ success: false, message: err.message });
  }
}
