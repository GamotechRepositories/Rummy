import mongoose, { Schema, Document } from 'mongoose';

export type PrivateRoomStatus = 'WAITING' | 'RUNNING' | 'COMPLETED' | 'CANCELLED' | 'EXPIRED';

export interface IPrivateRoomPlayer {
  playerId: string;
  displayName: string;
  seat: number;
  entryTxnId?: string;
  debitedAmount: number;
  refunded: boolean;
  penaltyPoints?: number;
  lossAmount?: number;
  refundAmount?: number;
  winAmount?: number;
  netWalletDelta?: number;
  joinedAt: Date;
}

export interface IPrivateRoom extends Document {
  roomCode: string;
  tableId: string;
  hostPlayerId: string;
  hostDisplayName: string;
  rulesetId: string;
  maxPlayers: number;
  stakeTier: number;
  status: PrivateRoomStatus;
  cancelledReason?: string;
  players: IPrivateRoomPlayer[];
  grossCollectionRs: number;
  platformRakeRs: number;
  rakeRate: number;
  winnerPayoutRs: number;
  refundedTotalRs: number;
  winnerPlayerId?: string;
  winnerDisplayName?: string;
  createdAt: Date;
  expiresAt?: Date;
  startedAt?: Date;
  finishedAt?: Date;
}

const RoomPlayerSchema = new Schema<IPrivateRoomPlayer>(
  {
    playerId: { type: String, required: true },
    displayName: { type: String, default: '' },
    seat: { type: Number, default: 0 },
    entryTxnId: { type: String },
    debitedAmount: { type: Number, default: 0 },
    refunded: { type: Boolean, default: false },
    penaltyPoints: { type: Number, default: 0 },
    lossAmount: { type: Number, default: 0 },
    refundAmount: { type: Number, default: 0 },
    winAmount: { type: Number, default: 0 },
    netWalletDelta: { type: Number, default: 0 },
    joinedAt: { type: Date, default: Date.now },
  },
  { _id: false }
);

const PrivateRoomSchema = new Schema<IPrivateRoom>(
  {
    roomCode: { type: String, required: true, unique: true, index: true },
    tableId: { type: String, required: true, index: true },
    hostPlayerId: { type: String, required: true },
    hostDisplayName: { type: String, default: '' },
    rulesetId: { type: String, required: true },
    maxPlayers: { type: Number, default: 6 },
    stakeTier: { type: Number, default: 0 },
    status: {
      type: String,
      enum: ['WAITING', 'RUNNING', 'COMPLETED', 'CANCELLED', 'EXPIRED'],
      default: 'WAITING',
      index: true,
    },
    cancelledReason: { type: String },
    players: { type: [RoomPlayerSchema], default: [] },
    grossCollectionRs: { type: Number, default: 0 },
    platformRakeRs: { type: Number, default: 0 },
    rakeRate: { type: Number, default: 0 },
    winnerPayoutRs: { type: Number, default: 0 },
    refundedTotalRs: { type: Number, default: 0 },
    winnerPlayerId: { type: String },
    winnerDisplayName: { type: String },
    createdAt: { type: Date, default: Date.now },
    expiresAt: { type: Date },
    startedAt: { type: Date },
    finishedAt: { type: Date },
  },
  { collection: 'private_rooms' }
);

export const PrivateRoom = mongoose.model<IPrivateRoom>('PrivateRoom', PrivateRoomSchema);
