# 👑 Royal Rummy — Standalone MERN Stack Admin Panel Master Plan

> **Architecture:** Completely Independent Full-Stack MERN Application  
> **Tech Stack:** **M**ongoDB · **E**xpress.js · **R**eact (Vite + TypeScript) · **N**ode.js  
> **Aesthetic:** Bespoke Dark Titanium Fintech / Gaming Command Center (*Non-AI Generated, High-Density Data Grid, Live Telemetry*)

---

## 🏛️ 1. Architecture Overview (100% Process & Codebase Isolation)

The Admin Panel operates as an entirely self-contained, independent system. The player-facing frontend (`frontend/`) and game server (`backend/game-service`) contain **zero** administrative routes, secrets, or UI components.

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                            ADMIN OPERATORS & TEAMS                          │
│             (Super Admins, Game Operations, Risk Analysts, Finance)         │
└──────────────────────────────────────┬──────────────────────────────────────┘
                                       │ HTTPS (Port 5180)
                                       ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                      STANDALONE ADMIN FRONTEND                              │
│                      Directory: /admin-frontend                             │
│                      Vite + React 18 + TypeScript                           │
│                      Port: 5180                                             │
└──────────────────────────────────────┬──────────────────────────────────────┘
                                       │ REST + WebSocket / SSE (Port 5050)
                                       ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                      STANDALONE ADMIN BACKEND                               │
│                      Directory: /admin-backend                              │
│                      Node.js (v20+) + Express.js                            │
│                      Port: 5050                                             │
│  - Admin JWT & RBAC Middleware       - Real-Time Variant Aggregation Engine │
│  - Live Table Spectator Bridge       - Game Replay & Dispute Inspector      │
│  - Config & Dynamic Rule Controller  - Audit & Anti-Collusion Alerts        │
└──────────────────────┬───────────────────────────────┬──────────────────────┘
                       │ Direct Mongo Queries          │ Internal IPC / REST
                       ▼                               ▼
    ┌───────────────────────────────────┐    ┌────────────────────────────────┐
    │     SHARED MONGODB CLUSTER        │    │    SPRING BOOT GAME SERVICE    │
    │  - games                          │    │    Port: 8080 (Internal Only)  │
    │  - game_results                   │    │  - Live TableActors in RAM     │
    │  - game_events (step-by-step)     │    │  - Matchmaking Queues          │
    │  - wallet_transactions            │    │  - Cluster Node Drain Control  │
    │  - user_profiles                  │    └────────────────────────────────┘
    │  - operators & ledgers            │
    └───────────────────────────────────┘
```

### Component & Port Mapping:
| Component | Folder Path | Port | Stack | Role |
| :--- | :--- | :--- | :--- | :--- |
| **Admin Frontend** | `admin-frontend/` | `5180` | React 18, Vite, TypeScript, Lucide, Tailwind / Token CSS | High-density control center for operators |
| **Admin Backend** | `admin-backend/` | `5050` | Node.js, Express.js, Mongoose / Mongo Driver, JWT | REST APIs, aggregation, cluster proxy |
| **Game Backend** | `backend/game-service/` | `8080` | Spring Boot 3, Java 21, WebSocket | Authoritative real-time game engine |
| **Player Frontend** | `frontend/` | `5173` | React 18, Vite, TypeScript | Customer game client (isolated) |
| **Database** | MongoDB Atlas / Local | `27017` | MongoDB 7+ | Shared persistence & historical logs |

---

## 🎨 2. UI/UX Design System: "Anti-AI Generated" Aesthetic

Most AI-generated dashboards suffer from the same flaws: oversized pastel purple cards, massive empty whitespace, lack of tabular density, and generic mock statistics. 

This Admin Portal is engineered around **Bloomberg Terminal, Linear, Stripe, and Cloudflare** design principles:

### 1. Data-Dense Layout Architecture
- **Compact Spacing:** Tight 8px / 12px / 16px spatial rhythm allowing dozens of data points and tables to fit comfortably on screen without excessive scrolling.
- **Header Telemetry Bar:** Global CCU, active RAM tables, 24h GGR, server health badge, and real-time clock with pulse indicators.
- **Quick Action Command Palette (`Ctrl+K` / `Cmd+K`):** Jump instantly to any Table ID, Game ID, Player ID, or Variant Dashboard.

### 2. Tailored Color Palette & Dark Titanium Theme
- **Background Core:** `#080B11` (Deepest Obsidian)
- **Surface / Card Layer:** `#0F141E` (Titanium Charcoal) with subtle hairline borders (`rgba(255, 255, 255, 0.06)`)
- **Active Row / Hover:** `#161D2B`
- **Typography:** 
  - UI Labels & Navigation: `Inter` or `Geist` (clean sans-serif).
  - Numbers, Amounts, Timestamps & IDs: `JetBrains Mono` (tabular figures for perfect alignment).

### 3. Variant Signature Color Accents
Every variant dashboard has a dedicated colorway for immediate visual context:
- 🟢 **Points Rummy (`POINTS_13`):** Emerald Glow (`#10B981`)
- 🔵 **Deals Rummy (`DEALS_2`, `DEALS_3`):** Electric Cyan (`#06B6D4`)
- 🟡 **Pool Rummy (`POOL_101`, `POOL_201`):** Amber Gold (`#F59E0B`)
- 🟣 **21-Card Rummy (`RUMMY_21`):** Royal Violet (`#8B5CF6`)

---

## 📊 3. Dashboard Hierarchy & Features

The panel features **1 Global Main Dashboard** plus **4 Dedicated Variant Dashboards**, along with specialized tools for Spectating and Dispute Resolution.

---

### 🌟 A. Main Executive Dashboard (`/dashboard`)
The central command overview for executive operators:
1. **Top Telemetry Bar:**
   - **Active Players (CCU):** Live connected players across all tables.
   - **Live In-Memory Tables:** Active `TableActor` instances running in RAM.
   - **24-Hour Gross Gaming Revenue (GGR):** Total wagers - Player winnings.
   - **Platform Commission (Rake):** Net platform revenue generated today.
   - **Cluster Health Badge:** Online nodes, JVM heap percentage, WebSocket latency.
2. **Variant Performance Breakdown:**
   - Visual comparison chart showing traffic, volume, and revenue across Points vs Deals vs Pool vs 21-Card.
3. **Real-Time Activity Feed:**
   - Live stream of recent match declarations, large pots, and player joins.
4. **Security & Risk Alert Bar:**
   - Collusion warnings (players on same table sharing IP or device signature).
   - Abnormal drop rate spikes or stuck table warnings.
5. **Node Drain Quick Control:**
   - Toggle graceful node draining before rolling deployments without kicking players out of active games.

---

### 🟢 B. Points Rummy Dashboard (`/variants/points`)
Tailored for fast-paced 13-card Points Rummy (`POINTS_13` / `INDIAN_POINTS`):
1. **Variant KPIs:**
   - Active Points Tables (2-Player vs 6-Player split).
   - Volume by Stake Tier (₹0.05, ₹0.10, ₹0.50, ₹1, ₹5, ₹25, ₹50 pt).
   - Average Hand Duration (seconds to first declaration).
   - Drop Distribution: First Drop (20 pts) vs Middle Drop (40 pts) vs Wrong Declare (80 pts).
2. **Live Points Table Grid:**
   - Table list with seated players, current turn, discard pile card, and live pot.
3. **Points Rule & Rake Configuration:**
   - Live adjustment of table timers (15s turn, 30s declare), max point penalty (80), and rake percentage.

---

### 🔵 C. Deals Rummy Dashboard (`/variants/deals`)
Tailored for tournament-style fixed deal matches (`DEALS_2` and `DEALS_3`):
1. **Variant KPIs:**
   - Best-of-2 vs Best-of-3 volume share.
   - Tie-Breaker Deal Trigger Rate (% of matches entering Deal 3 or Deal 4).
   - Chip Balance Distribution across players.
   - Early Forfeit vs Full Completion percentage.
2. **Live Deals Table Grid:**
   - Displays Deal Number progress (`Deal 1/2`, `Deal 2/2`, or `⚡ Tie-Breaker`), chip leader at each table.
3. **Deals Rule Configuration:**
   - Inter-deal countdown (e.g., 5s break), tie-breaker threshold, minimum chip requirements.

---

### 🟡 D. Pool Rummy Dashboard (`/variants/pool`)
Tailored for elimination games (`POOL_101` and `POOL_201`):
1. **Variant KPIs:**
   - Pool 101 vs Pool 201 active table share.
   - Rejoin Rate & Rejoin Fee Revenue (how many players pay to rejoin after crossing threshold).
   - Prize Split Acceptance Rate (% of survivors who agree to split the pot).
   - Average Deals to Match Elimination.
2. **Live Pool Table Grid:**
   - Real-time cumulative score monitoring (e.g., Player A: 65/101, Player B: 85/101).
   - Elimination status flags.
3. **Pool Rule Configuration:**
   - First drop (20/25), Middle drop (40/50), Rejoin score formula, Split pot eligibility threshold.

---

### 🟣 E. 21-Card Rummy Dashboard (`/variants/21-card`)
Tailored for high-stakes 3-deck 21-Card Rummy (`RUMMY_21`):
1. **Variant KPIs:**
   - Special Melds Hit Rate: Dublees (pairs) and Tunnellas (triplets of same suit).
   - Value Card Points (Upper Joker, Lower Joker, Paplu/Marriage combinations).
   - Hand declaration distribution.
2. **Live 21-Card Table Grid:**
   - Active high-stake tables, deck status, wild cut cards.

---

### 👁️ F. Live Table Spectator & Hand Replay Inspector (`/inspector`)
A mission-critical operational tool for player dispute resolution and anti-fraud:
1. **Real-Time Spectator Mode:**
   - View any live table in real time without being seated.
   - Inspect all players' current card hands, melded sequences, and discard history.
2. **Step-by-Step Historical Hand Replay:**
   - Enter any past `gameId` or `tableId`.
   - Scrub through every move sequentially (Draw from closed deck ➔ Discard ➔ Declare ➔ Showdown).
   - Inspect exactly which cards were melded and verify mathematical scoring accuracy.
3. **Emergency Table Controls:**
   - **Force Refund & Terminate:** Safely cancel a frozen/stuck table and refund all player stakes to wallet accounts.
   - **Kick/Flag Player:** Flag suspicious player accounts for anti-collusion review.

---

## 💻 4. Technical Architecture: Node.js / Express Backend

The `admin-backend/` service is built with Node.js & Express.js.

### Project Structure (`admin-backend/`):
```text
admin-backend/
├── package.json
├── tsconfig.json
├── .env.example
├── src/
│   ├── index.ts                     # Express app setup & server bootstrap
│   ├── config/
│   │   ├── database.ts              # MongoDB Mongoose connection
│   │   ├── env.ts                   # Environment variables validation
│   │   └── gameService.ts           # Axios client for internal Game Server (8080)
│   ├── models/
│   │   ├── AdminUser.ts             # Admin authentication & roles
│   │   ├── AuditLog.ts              # Action logs (who changed what rake/rule)
│   │   └── VariantConfig.ts         # Override configs per variant
│   ├── controllers/
│   │   ├── authController.ts        # Admin login, 2FA, token refresh
│   │   ├── overviewController.ts    # Main dashboard KPI aggregations
│   │   ├── variantController.ts     # Variant-specific metric aggregations
│   │   ├── tableController.ts       # Live RAM tables inspector & spectator proxy
│   │   ├── replayController.ts      # Move-by-move hand history & events
│   │   ├── configController.ts      # Live rule & rake updating
│   │   └── healthController.ts      # Node diagnostics & drain controls
│   ├── routes/
│   │   ├── authRoutes.ts
│   │   ├── overviewRoutes.ts
│   │   ├── variantRoutes.ts
│   │   ├── tableRoutes.ts
│   │   ├── replayRoutes.ts
│   │   └── configRoutes.ts
│   ├── services/
│   │   ├── mongoAggregationService.ts  # Pipeline queries on games & transactions
│   │   ├── gameServerProxyService.ts   # IPC connection to TableManager (8080)
│   │   └── fraudDetectionService.ts    # IP/device overlap & chip dumping detection
│   └── middleware/
│       ├── authMiddleware.ts        # JWT validation
│       ├── rbacMiddleware.ts        # Role-based authorization
│       └── errorMiddleware.ts       # Centralized error handler
```

### Core REST Endpoints (`admin-backend`):
- `POST /api/admin/auth/login` — Authenticate admin user
- `GET /api/admin/overview/metrics` — Global CCU, revenue, active tables, variants distribution
- `GET /api/admin/overview/health` — Cluster JVM, RAM, Redis, and drain state
- `GET /api/admin/variants/:variantId/metrics` — Metrics for `POINTS_13`, `DEALS_2`, `POOL_101`, etc.
- `GET /api/admin/variants/:variantId/tables` — Live table list for specific variant
- `GET /api/admin/tables/live` — All live RAM tables across all variants
- `GET /api/admin/tables/:tableId/inspect` — Detailed state of table (players, hands, discards)
- `POST /api/admin/tables/:tableId/terminate` — Safely force-close and refund table
- `GET /api/admin/replay/:gameId` — Full chronological game event history for dispute playback
- `GET /api/admin/config/rulesets` — Current ruleset configurations
- `PUT /api/admin/config/rulesets/:variantId` — Dynamically adjust rake %, timers, or penalties

---

## 🖥️ 5. Technical Architecture: React / TypeScript Frontend

The `admin-frontend/` is built with React 18, Vite, and TypeScript.

### Project Structure (`admin-frontend/`):
```text
admin-frontend/
├── package.json
├── vite.config.ts
├── index.html
├── src/
│   ├── main.tsx
│   ├── App.tsx
│   ├── routes/
│   │   └── index.tsx                # React Router v6 layout & route guards
│   ├── api/
│   │   ├── apiClient.ts             # Axios instance with auth interceptors
│   │   └── endpoints.ts             # Typed API functions
│   ├── context/
│   │   └── AuthContext.tsx          # Current admin user state
│   ├── components/
│   │   ├── layout/
│   │   │   ├── AdminLayout.tsx      # Sidebar + Topbar + Content shell
│   │   │   ├── Sidebar.tsx          # Nav links with variant color dots
│   │   │   └── Topbar.tsx           # Global telemetry badges & search
│   │   ├── common/
│   │   │   ├── MetricCard.tsx       # Compact KPI card with delta & sparkline
│   │   │   ├── DataTable.tsx        # High-density sortable/searchable table
│   │   │   ├── StatusBadge.tsx      # Live glowing status indicator
│   │   │   └── CommandPalette.tsx   # Ctrl+K jump navigation
│   │   ├── tables/
│   │   │   ├── LiveTableGrid.tsx    # Live table monitoring cards
│   │   │   └── TableInspectModal.tsx# In-depth RAM TableActor view
│   │   └── replay/
│   │       ├── ReplayTimeline.tsx   # Scrub bar for game steps
│   │       ├── HandCardViewer.tsx   # Mini card faces showing melds/groups
│   │       └── MoveLogViewer.tsx    # Chronological list of events
│   ├── pages/
│   │   ├── LoginPage.tsx
│   │   ├── MainDashboardPage.tsx
│   │   ├── variants/
│   │   │   ├── PointsDashboardPage.tsx
│   │   │   ├── DealsDashboardPage.tsx
│   │   │   ├── PoolDashboardPage.tsx
│   │   │   └── TwentyOneDashboardPage.tsx
│   │   ├── LiveTablesPage.tsx
│   │   ├── HandReplayPage.tsx
│   │   ├── FinancialLedgerPage.tsx
│   │   └── RulesConfigPage.tsx
│   └── styles/
│       ├── tokens.css               # Titanium dark theme variables
│       └── globals.css
```

---

## 📅 6. Step-by-Step Implementation Roadmap

```text
Phase 1 ──► Scaffold Node.js Backend & React Frontend Projects
Phase 2 ──► Admin Auth, MongoDB Models & Shared DB Connection
Phase 3 ──► Main Executive Dashboard & Cluster Telemetry Bridge
Phase 4 ──► Dedicated Variant Dashboards (Points, Deals, Pool, 21-Card)
Phase 5 ──► Live Table Spectator & Move-by-Move Replay Engine
Phase 6 ──► Dynamic Rules & Rake Tuning Engine
Phase 7 ──► End-to-End Testing & Docker Integration
```

### Detailed Breakdown:

#### 🔹 Phase 1: Environment & Project Scaffolding
- Initialize `admin-backend/` (`package.json`, TypeScript, Express, Mongoose, dotenv, cors).
- Initialize `admin-frontend/` (`package.json`, Vite, React, TypeScript, Lucide React).
- Verify independent ports: Admin Backend (`5050`) and Admin Frontend (`5180`).

#### 🔹 Phase 2: Core Backend Engine & Security
- Setup MongoDB connection to shared database (`games`, `game_results`, `wallet_transactions`).
- Implement Admin JWT Authentication (`/api/admin/auth/login`) with role-based permissions (`SUPER_ADMIN`, `OPERATOR`, `AUDITOR`).
- Seed initial super-admin credentials.

#### 🔹 Phase 3: Main Executive Dashboard
- Implement backend aggregation pipelines for 24h GGR, total rake, CCU, and variant traffic share.
- Build the `AdminLayout` and `MainDashboardPage` with real-time KPI cards and variant comparison charts.
- Connect to game-service (`8080`) diagnostics for JVM heap, thread count, and graceful node draining.

#### 🔹 Phase 4: Dedicated Variant Dashboards
- Build specialized pages and aggregation queries for:
  - `PointsDashboardPage` (stake tier breakdown, drop penalty distribution).
  - `DealsDashboardPage` (deal counts, tie-breaker frequencies, chip trends).
  - `PoolDashboardPage` (101 vs 201 elimination curves, rejoin revenue, prize split stats).
  - `TwentyOneDashboardPage` (special meld rates).

#### 🔹 Phase 5: Live Table Spectator & Hand Replay Inspector
- Implement internal proxy to read live in-memory `TableActor` states from Spring Boot game-service.
- Build `LiveTablesPage` allowing operators to view all active tables with filtering by variant.
- Build `HandReplayPage` reading from `game_events` collection to provide a step-by-step playback scrub bar for disputes.

#### 🔹 Phase 6: Dynamic Rule & Rake Configuration
- Create endpoints to view and adjust live rake %, turn timers, and penalty values without restarting the Java game engine.
- Audit log all configuration changes with operator ID and timestamps.

#### 🔹 Phase 7: Verification & Orchestration
- Verify full MERN stack integration while game-service (`8080`) and player frontend (`5173`) are actively running matches.
- Add admin services to `deployment/docker/docker-compose.yml` for unified one-command deployment.

---

## 🔒 7. Security & Compliance Principles

1. **Air-Gapped Player Client:** The player-facing frontend build (`frontend/dist`) will not contain any admin code or references to port `5050` / `5180`.
2. **Read-Heavy Direct Database Access:** Complex historical analytics run directly against MongoDB read-replicas, preventing any performance degradation on the Java game engine.
3. **Audited Actions:** Every administrative action (table termination, rake modification, player flag) is written to the immutable `audit_logs` collection.
