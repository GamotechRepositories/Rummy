# Technical Architecture & System Overview

## 1. High-Level Architecture
The Royal Rummy Platform is engineered for extreme concurrency (300,000–400,000 concurrent players) with low latency (<1ms p99 state updates) and strict server-authoritative game rule execution.

```
[React + TypeScript Web Client]
               │ (WSS / HTTPS)
               ▼
   [Nginx Edge Ingress / LB]
               │
      ┌────────┴────────┐
      ▼                 ▼
[Game Node 1]     [Game Node 2]  (Spring Boot Java 21 Cluster)
   (TableActor)      (TableActor)
      │                 │
      ├─────────────────┼───────────────┐
      ▼                 ▼               ▼
[Redis Cluster]    [MongoDB 7]    [Kafka Event Stream]
(Routing/Presence) (Persistence)  (Audit/Compliance)
```

## 2. Core Subsystems

### 2.1 Server-Authoritative TableActor Model
- Every active game lounge table is managed in-memory by an independent, single-threaded or synchronized `TableActor` instance.
- Clients never validate hands, calculate scores, or decide game winners. The server maintains the true deck sequence and generates filtered, zero-knowledge `PlayerGameView` payloads ensuring players only see their own private cards.

### 2.2 10-Variant Ruleset Engine
All variants are dynamically resolved at runtime via [`RulesetRegistry.java`](file:///e:/Rummy/backend/game-engine/src/main/java/com/rummy/engine/rules/RulesetRegistry.java):
1. **Points Rummy (`INDIAN_POINTS`)**: 13 cards, 2 decks, 20/40/80 drop scoring.
2. **Pool 101 (`POOL_101`)**: Cumulative elimination at 101 points.
3. **Pool 201 (`POOL_201`)**: Cumulative elimination at 201 points.
4. **Deals Rummy (`DEALS_RUMMY`)**: 2/3/6 deal chip pot distributions.
5. **21-Card Rummy (`INDIAN_21`)**: 3 decks, upper/lower jokers, marriage combinations.
6. **Gin Rummy (`GIN_RUMMY`)**: 10 cards, knock threshold <= 10, undercut bonuses.
7. **500 Rummy (`RUMMY_500`)**: Melded card scoring, race to 500.
8. **Kalooki (`KALOOKI`)**: Contract melds, 50 pt unmelded joker penalties.
9. **Canasta (`CANASTA`)**: 2 decks + 4 jokers, 7-card natural/mixed canastas.
10. **Indian 13 (`INDIAN_13`)**: Base Indian rummy sequence rules.

### 2.3 MongoDB Wallet Edition & Double-Entry Ledger
- Strict separation between Game Engine and Ledger mutations.
- All financial state changes (`DEPOSIT`, `WITHDRAWAL`, `GAME_ENTRY`, `GAME_WIN`, `GAME_REFUND`, `PROMOTIONAL_CREDIT`) are recorded with unique idempotency keys to prevent duplicate execution during network retries.

### 2.4 Security & Anti-Fraud Engine
- Cryptographic HMAC-SHA256 JWT tokens verified during WebSocket handshake.
- Token-bucket rate limiting (100 req/sec limit) with `RATE_LIMIT_EXCEEDED` protection.
- Same-IP Table Collision Detector to prevent multi-accounting.
- Chip Dumping Analyzer detecting anomalous drop frequencies.
