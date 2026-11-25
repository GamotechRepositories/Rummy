import mongoose, { Schema, Document } from 'mongoose';

export type TournamentStatus =
  | 'DRAFT'
  | 'REGISTERING'
  | 'STARTING'
  | 'RUNNING'
  | 'COMPLETED'
  | 'CANCELLED';

export interface ITournamentRegistration {
  playerId: string;
  displayName: string;
  operatorId: string;
  registeredAt: Date;
  entryTxnId: string; // idempotency key for operator debit
  refunded: boolean;
}

export interface ITournamentRound {
  roundNumber: number;         // 1, 2, 3 …
  tableIds: string[];          // game-service tableIds for each table
  qualifiedPlayerIds: string[]; // who advanced from this round
  status: 'PENDING' | 'RUNNING' | 'DONE';
  startedAt?: Date;
  finishedAt?: Date;
}

export interface ITournamentPrize {
  rank: number;
  percentage: number; // e.g. 35 = 35% of netPrizePool
}

export interface ITournament extends Document {
  tournamentId: string;
  title: string;
  variant: 'DEALS_3';          // only DEALS_3 for now
  tableSizePerRound: 6;        // always 6
  minPlayers: number;          // admin-configurable, default 36 (must be multiple of 6)
  maxPlayers: number;          // admin-configurable, default 120 (must be multiple of 6)
  entryFeeRs: number;          // in INR (0 = freeroll)
  fixedPrizeRs: number;        // fixed total prize amount distributed to winners (e.g. 5000)
  checkInWindowMinutes: number; // minutes after startsAt before match begins (default 5)
  checkInEndsAt?: Date;
  checkedInPlayerIds: string[];
  prizes: ITournamentPrize[];  // prize distribution by rank
  registrationStartsAt: Date;
  registrationEndsAt: Date;
  startsAt: Date;
  status: TournamentStatus;
  operatorScope: string;       // 'ALL' or specific operatorId
  registrations: ITournamentRegistration[];
  rounds: ITournamentRound[];
  finalRankings: Array<{ rank: number; playerId: string; displayName: string; prizeRs: number }>;
  grossPoolRs: number;         // sum of all entry fees collected
  platformProfitRs: number;    // grossPoolRs - fixedPrizeRs (can be negative if overlay)
  createdByAdminId: string;
  cancelledReason?: string;
  createdAt: Date;
  updatedAt: Date;
}

const PrizeSchema = new Schema<ITournamentPrize>(
  { rank: Number, percentage: Number },
  { _id: false }
);

const RegistrationSchema = new Schema<ITournamentRegistration>(
  {
    playerId: { type: String, required: true },
    displayName: { type: String, default: '' },
    operatorId: { type: String, default: 'DEFAULT' },
    registeredAt: { type: Date, default: Date.now },
    entryTxnId: { type: String, required: true },
    refunded: { type: Boolean, default: false },
  },
  { _id: false }
);

const RoundSchema = new Schema<ITournamentRound>(
  {
    roundNumber: { type: Number, required: true },
    tableIds: { type: [String], default: [] },
    qualifiedPlayerIds: { type: [String], default: [] },
    status: { type: String, enum: ['PENDING', 'RUNNING', 'DONE'], default: 'PENDING' },
    startedAt: Date,
    finishedAt: Date,
  },
  { _id: false }
);

const FinalRankingSchema = new Schema(
  {
    rank: { type: Number, required: true },
    playerId: { type: String, required: true },
    displayName: { type: String, default: '' },
    prizeRs: { type: Number, default: 0 },
  },
  { _id: false }
);

const TournamentSchema = new Schema<ITournament>(
  {
    tournamentId: { type: String, required: true, unique: true, index: true },
    title: { type: String, required: true },
    variant: { type: String, default: 'DEALS_3' },
    tableSizePerRound: { type: Number, default: 6 },
    minPlayers: { type: Number, required: true, default: 36 },
    maxPlayers: { type: Number, required: true, default: 120 },
    entryFeeRs: { type: Number, required: true, default: 0 },
    fixedPrizeRs: { type: Number, required: true, default: 0 },
    checkInWindowMinutes: { type: Number, default: 5 },
    checkInEndsAt: { type: Date },
    checkedInPlayerIds: { type: [String], default: [] },
    prizes: { type: [PrizeSchema], default: [] },
    registrationStartsAt: { type: Date, required: true },
    registrationEndsAt: { type: Date, required: true },
    startsAt: { type: Date, required: true, index: true },
    status: {
      type: String,
      enum: ['DRAFT', 'REGISTERING', 'STARTING', 'RUNNING', 'COMPLETED', 'CANCELLED'],
      default: 'DRAFT',
      index: true,
    },
    operatorScope: { type: String, default: 'ALL' },
    registrations: { type: [RegistrationSchema], default: [] },
    rounds: { type: [RoundSchema], default: [] },
    finalRankings: { type: [FinalRankingSchema], default: [] },
    grossPoolRs: { type: Number, default: 0 },
    platformProfitRs: { type: Number, default: 0 },
    createdByAdminId: { type: String, default: '' },
    cancelledReason: String,
  },
  { collection: 'tournaments', timestamps: true }
);

export const Tournament = mongoose.model<ITournament>('Tournament', TournamentSchema);
