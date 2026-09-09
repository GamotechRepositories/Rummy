# System Architecture Overview

## 1. High-Level Design Principles

The platform is designed to scale to **300,000–400,000 concurrent players** (~100,000 active tables) with sub-second response times, zero data corruption, and robust resilience against reconnect storms and node failures.

### Key Architectural Tenets:

1. **Server-Authoritative Gameplay:**
   - Clients send *intent* (e.g. `DRAW_CLOSED`, `DISCARD`, `DECLARE`).
   - The server validates turns, cards, shuffles, sequences, declarations, and scores.
   - The client never dictates winners or calculates points.

2. **In-Memory Hot State (Table Actor Model):**
   - Active games run in memory on dedicated game servers as sequential actors (`TableActor`).
   - Card movements do **not** trigger synchronous database writes.
   - Prevents database bottlenecking under high CCU.

3. **Zero-Knowledge Hand Protection:**
   - Private hands are never broadcast to all table participants.
   - The server creates a filtered, player-specific `GameView` for each client.
   - Bots receive the exact same information visibility as human players.

4. **Event Sequencing & Deterministic Resync:**
   - Every event has a monotonically increasing sequence ID per game.
   - Gaps trigger an idempotent `RESYNC(lastSequence)` to fetch missing events or a full snapshot.

5. **Financial & Gameplay Isolation:**
   - The Game Engine never touches wallet balances.
   - Game completion triggers a `GameFinished` event consumed by a dedicated `SettlementService`.
   - The `WalletService` manages an immutable double-entry ledger in MongoDB.

---

## 2. Component Diagram

```text
                             INTERNET
                                │
                                ▼
                        ┌───────────────┐
                        │ CDN / WAF /   │
                        │ DDoS Defense  │
                        └───────┬───────┘
                                │
                    ┌───────────┴───────────┐
                    │                       │
                    ▼                       ▼
            ┌───────────────┐       ┌───────────────┐
            │ API Load      │       │ WebSocket     │
            │ Balancer      │       │ Load Balancer │
            └───────┬───────┘       └───────┬───────┘
                    │                       │
                    ▼                       ▼
            ┌────────────────┐       ┌────────────────────┐
            │ Spring Boot    │       │ Game Server        │
            │ API Cluster    │       │ Cluster            │
            └───────┬────────┘       └─────────┬──────────┘
                    │                          │
            ┌───────┼─────────┐                │
            │       │         │                │
            ▼       ▼         ▼                ▼
        MongoDB  Redis     Kafka          In-memory
                                          GameState
            │       │         │                │
            │       │         ├── Audit        │
            │       │         ├── Analytics    │
            │       │         ├── History      │
            │       │         └── Notifications│
            │       │                          │
            │       └──────── Table routing ───┘
            │
            ▼
         Backups
```

---

## 3. Real-Time WebSocket Protocol

- **Transport:** Secure WebSocket (`wss://`)
- **Protocol:** JSON messages with `requestId`, `sequence`, `gameId`, and `tableId`.
- **Command Pipeline:** Handled sequentially per table queue to eliminate race conditions.
- **Heartbeats & Timing:** Central scheduler / timing-wheel (no thread-per-table).

---

## 4. Multi-Variant Engine Hierarchy

The engine strictly separates generic primitives from variant-specific rules:

```text
GameEngine
   ├── Shared Primitives: Card, Suit, Rank, Deck, PlayerState, TurnTimer
   └── Variant Rulesets:
         ├── PointsRummyRules (Phase 1 Target)
         ├── Pool101Rules
         ├── Pool201Rules
         ├── DealsRummyRules
         ├── TwentyOneCardRules
         ├── GinRummyRules
         ├── Rummy500Rules
         ├── KalookiRules
         └── CanastaRules
```
