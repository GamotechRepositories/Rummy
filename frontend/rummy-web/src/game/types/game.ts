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

export interface OpponentView {
  playerId: string;
  displayName: string;
  seatIndex: number;
  status: PlayerStatus;
  cardCount: number;
  score: number;
  isBot: boolean;
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
