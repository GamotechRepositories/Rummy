export type Suit = 'HEARTS' | 'DIAMONDS' | 'CLUBS' | 'SPADES' | 'NONE';

export type Rank =
  | 'TWO'
  | 'THREE'
  | 'FOUR'
  | 'FIVE'
  | 'SIX'
  | 'SEVEN'
  | 'EIGHT'
  | 'NINE'
  | 'TEN'
  | 'JACK'
  | 'QUEEN'
  | 'KING'
  | 'ACE'
  | 'JOKER';

export interface CardInstance {
  instanceId: string;
  suit: Suit;
  rank: Rank;
  deckIndex: number;
  printedJoker: boolean;
}

export type GameStatus =
  | 'WAITING_FOR_PLAYERS'
  | 'DEALING'
  | 'IN_PROGRESS'
  | 'DECLARING'
  | 'COMPLETED'
  | 'ABORTED';

export type TurnPhase =
  | 'DRAW'
  | 'DISCARD'
  | 'FINISH'
  | 'AWAITING_DRAW'
  | 'AWAITING_DISCARD'
  | 'AWAITING_DECLARE_VALIDATION'
  | 'COMPLETED';

export type PlayerStatus =
  | 'WAITING'
  | 'READY'
  | 'ACTIVE'
  | 'DROPPED'
  | 'DECLARED'
  | 'ELIMINATED';

export interface PublicPlayerView {
  playerId: string;
  displayName: string;
  seatIndex: number;
  status: PlayerStatus;
  cardCount: number;
  score: number;
  isBot: boolean;
}

export interface TurnStateView {
  currentPlayerId: string;
  phase: TurnPhase;
  turnDeadline: string; // ISO string
  consecutiveMissedTurns: number;
}

export interface PlayerStanding {
  playerId: string;
  displayName: string;
  seatIndex: number;
  cumulativeScore: number;
  isEliminated: boolean;
  status: PlayerStatus;
  chipBalance?: number;
}

export interface DealScoreRecord {
  dealNumber: number;
  winnerPlayerId: string;
  roundScores: Record<string, number>;
  cumulativeScores: Record<string, number>;
}

export interface OpponentView {
  playerId: string;
  displayName: string;
  seatIndex: number;
  status: PlayerStatus;
  cardCount: number;
  score: number;
  cumulativeScore?: number;
  isEliminated?: boolean;
  isBot: boolean;
  hand?: CardInstance[];
  chipBalance?: number;
  avatarId?: string;
}

export interface PlayerGameView {
  tableId: string;
  gameId: string;
  viewerPlayerId: string;
  gameStatus: GameStatus;
  sequence: number;
  hand: CardInstance[];
  opponents: OpponentView[];
  topDiscard: CardInstance | null;
  cutJoker: CardInstance | null;
  closedDeckRemaining: number;
  activePlayerId: string | null;
  turnPhase: TurnPhase | null;
  turnDeadline: string | null;
  isMyTurn: boolean;
  winnerId?: string | null;
  discardHistory?: CardInstance[];
  viewerScore?: number;
  viewerStatus?: PlayerStatus;
  winningGroups?: { cards: CardInstance[] }[];
  viewerSeatIndex?: number;
  rulesetId?: string;
  viewerDropped?: boolean;
  dealNumber?: number;
  eliminationThreshold?: number;
  viewerCumulativeScore?: number;
  viewerIsEliminated?: boolean;
  standings?: PlayerStanding[];
  dealHistory?: DealScoreRecord[];
  nextDealCountdown?: number | null;
  tournamentWinnerId?: string | null;
  dealerSeatIndex?: number;
  canRejoin?: boolean;
  rejoinScore?: number;
  rejoinFee?: number;
  freshlyEliminatedNames?: string[];
  totalDeals?: number;
  viewerChipBalance?: number;
  hasTakenFirstTurn?: boolean;
  /** Server-computed: dropping now costs the first-drop penalty. */
  firstDropAvailable?: boolean;
  drawnCardInstanceId?: string | null;
  isDrawnFromDiscard?: boolean;
  /** Entry stake in rupees; on points tables the most a player can lose (point value = stake / max penalty). */
  stakeTier?: number;
  /** The active player's normal turn time ran out and they are on extra time. */
  inExtraTime?: boolean;
  /** Server-computed: the open card may be taken now (a joker only as the deal's first open card). */
  topDiscardPickable?: boolean;
}

export type GroupValidationType = 'PURE_SEQUENCE' | 'IMPURE_SEQUENCE' | 'SET' | 'INVALID';

export interface VisualCardGroup {
  id: string;
  cards: CardInstance[];
  groupType: GroupValidationType;
  deadwoodPoints: number;
}

export interface WsClientMessage {
  type: string;
  requestId?: string;
  tableId?: string;
  payload?: Record<string, unknown>;
}

export interface WsServerMessage<T = unknown> {
  type: string;
  requestId?: string;
  tableId?: string;
  sequence?: number;
  payload: T;
}


export interface WsErrorMessage {
  errorCode: string;
  message: string;
  requestId?: string;
}

export interface UserProfile {
  userId: string;
  displayName: string;
  virtualPoints: number;
  gamesPlayed: number;
  gamesWon: number;
  totalScore: number;
  createdAt: string;
  updatedAt: string;
}

export interface GameResultItem {
  id: string;
  gameId: string;
  tableId: string;
  playerId: string;
  displayName: string;
  finalScore: number;
  won: boolean;
  status: string;
  createdAt: string;
}

export interface PlayerHistoryResponse {
  playerId: string;
  profile: UserProfile;
  winRate: number;
  results: GameResultItem[];
}

export interface PlayerSettlementDetail {
  playerId: string;
  isWinner: boolean;
  penaltyPoints: number;
  initialStake: number;
  lossAmount: number;
  refundAmount: number;
  winAmount: number;
  netWalletDelta: number;
}

export interface GameSettlementResult {
  gameId: string;
  tableId: string;
  rulesetId: string;
  winnerPlayerId: string;
  stakeTier: number;
  totalGrossPot: number;
  platformRakeRate: number;
  platformRakeAmount: number;
  netWinnerPrize: number;
  playerDetails: Record<string, PlayerSettlementDetail>;
}

