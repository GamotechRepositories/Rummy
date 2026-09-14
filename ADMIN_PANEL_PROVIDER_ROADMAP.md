# 👑 Royal Rummy — B2B Provider Master Admin Portal Specification & Implementation Roadmap

> **Architectural Pattern:** B2B Game Provider & Cross-Platform Shared Liquidity Hub  
> **Target:** Multi-tenant operator aggregation (Platform A, B, C players competing on unified real-time tables)

---

## 🏛️ 1. Architecture & Business Model Overview

In this B2B Game Provider model, our platform does **NOT** collect payments directly via consumer UPI/Gateways. Instead, we act as the **Central Game Engine Provider** (similar to *Pragmatic Play, Evolution Gaming, or Spribe*).

```
                        [ PARTNER PLATFORMS / OPERATORS ]
              ┌─────────────────────┬─────────────────────┐
              │                     │                     │
      [ Platform A App ]    [ Platform B App ]    [ Platform C App ]
      (Own UPI/Payments)    (Own UPI/Payments)    (Own UPI/Payments)
              │                     │                     │
              │ Webview / iFrame    │ Webview / iFrame    │ Webview / iFrame
              │ + Token API         │ + Token API         │ + Token API
              ▼                     ▼                     ▼
      ┌───────────────────────────────────────────────────────────┐
      │          ROYAL RUMMY GLOBAL GAME SERVER GATEWAY            │
      │   (Unified Cross-Platform Matchmaking & TableActors)      │
      └─────────────────────────────┬─────────────────────────────┘
                                    │
                         [ SHARED TABLES (RAM) ]
         ┌───────────────────────────────────────────────────────┐
         │ Table #1042:                                          │
         │  Seat 1: Player A1 (from Platform A) ─── ₹100 Stake   │
         │  Seat 2: Player B4 (from Platform B) ─── ₹100 Stake   │
         │  Seat 3: Player C9 (from Platform C) ─── ₹100 Stake   │
         └──────────────────────────┬────────────────────────────┘
                                    │
                    ┌───────────────┴───────────────┐
                    ▼                               ▼
       [ Master Admin Portal (Separate) ]   [ Seamless Wallet Callback ]
        (admin.domain.com - Port 5174)      (Debit/Credit Operator APIs)
```

### Key Principles:
1. **Zero Player Leakage:** Players from different platforms play against each other seamlessly without seeing each other's host platform or private details.
2. **Seamless Balance Synchronisation:** When a player buys in or settles, our server triggers real-time Debit/Credit Webhooks to the respective partner's API.
3. **Total Separation of Admin from Player UI:** The player client (`frontend/rummy-web`) contains **ZERO** administrative code, endpoints, or debugging hooks. The Admin Portal is a completely separate application.

---

## 🎛️ 2. Master Admin Portal Features (What will be built)

The standalone Master Admin Portal (`frontend/admin-portal`) will feature 6 core operational modules:

### Module 1: Executive Dashboard & Live Metrics
- **Real-Time Global CCU:** Active players across all connected partner platforms.
- **Active TableActors:** Total live games running in memory.
- **Global Gross Gaming Revenue (GGR):** Total volume wagered and platform commission (Rake).
- **Tenant Performance Pie-Chart:** Traffic share per operator (Platform A vs Platform B vs Platform C).

### Module 2: Tenant / Operator Management
- **Operator Provisioning:** Create partner accounts (e.g. "BetKing", "RoyalPlay", "GameZone").
- **API Key & Secret Generation:** Cryptographically secure keys for partner authentication.
- **Webhook Configuration:** Configure partner Seamless Wallet endpoints:
  - `POST /api/v1/wallet/debit` (on game join / buy-in)
  - `POST /api/v1/wallet/credit` (on game win / refund)
  - `GET /api/v1/wallet/balance` (pre-table check)
- **Rake & Revenue Share Configuration:** Set custom commission percentages per operator (e.g. 80% Operator / 20% Provider).

### Module 3: Cross-Platform Live Table Monitor & Spectator
- **Live Table Grid:** Inspect every table currently in memory.
- **Player Cross-Origin Tagging:** View which operator each player at the table originated from.
- **Live Card & Action Inspector:** Real-time visibility into discards, hand sequences, and turn timers.
- **Administrative Force-Close / Table Terminate:** Safely cancel or refund stuck games.

### Module 4: Financial Ledger & Operator Settlements
- **Operator Account Balances:** Running ledger of total buy-ins vs total winnings.
- **Commission (Rake) Breakdown:** Calculated gross revenue and net provider revenue.
- **Dispute & Game Replay:** Card-by-card historical playback for contested rounds.
- **Export Reports:** Download CSV / Excel statements for weekly/monthly operator billing.

### Module 5: Fraud, Collusion & Risk Engine
- **Cross-Operator Collusion Detection:** Detect if players on the same table are sharing IP addresses, device fingerprints, or Wi-Fi BSSIDs even if they logged in via different platforms.
- **Chip-Dumping Alerts:** Flag intentionally bad declarations designed to transfer money.
- **Global Ban & Exclusion:** Instantly blackball fraudulent device IDs across all platforms.

### Module 6: System Infrastructure & Node Health
- **Spring Boot Actuator Health:** JVM heap usage, garbage collection pauses, CPU utilization.
- **Redis Cluster & Kafka Queue Rates:** Matchmaking queue latency, event throughput.
- **Graceful Server Draining:** Place server into "Draining Mode" before rolling updates so existing games finish naturally without assigning new tables.

---

## 📋 3. Step-by-Step Implementation Roadmap

We will execute this transformation systematically in **5 distinct phases**:

```text
Phase 1 (Completed) ──► Architecture Specification (this document)
Phase 2 (Next)       ──► Clean Player Client (remove admin modal/buttons from rummy-web)
Phase 3              ──► Backend B2B Tenant & Admin REST Engine (game-service)
Phase 4              ──► Standalone Admin Portal Frontend (frontend/admin-portal)
Phase 5              ──► Integration, Docker Setup & End-to-End Verification
```

---

### 🔹 Phase 2: Player Client Sanitization
- Remove `AdminDashboardModal.tsx` from `frontend/rummy-web`.
- Remove the `Admin` button from `LobbyScreen.tsx`.
- Ensure zero exposure of internal `/api/admin/` routes in the player bundle.

---

### 🔹 Phase 3: Backend B2B Multi-Tenant & Admin Architecture
- Create database collections in MongoDB Atlas:
  - `operators` (Tenant credentials, Webhooks, RevShare %).
  - `operator_ledgers` (Financial debit/credit audit logs).
  - `admin_users` (Role-Based Access: `SUPER_ADMIN`, `FINANCE`, `OPS`, `COMPLIANCE`).
- Implement Security Layer:
  - Admin JWT Authentication (`/api/admin/auth/login`).
  - Operator API Key Authentication Filter for external incoming launch requests.
- Implement Provider Admin REST APIs:
  - `/api/admin/operators` (CRUD operators & API keys).
  - `/api/admin/tables/live` (Real-time TableActor inspect endpoint).
  - `/api/admin/finance/settlements` (GGR and commission reports).
  - `/api/admin/fraud/collusion` (Suspicious cross-platform alerts).

---

### 🔹 Phase 4: Standalone Admin Portal Frontend (`frontend/admin-portal`)
- Initialize a dedicated modern React + TypeScript + Vite app in `frontend/admin-portal`.
- Port: `http://localhost:5174` (separate from player client on `5173`).
- Design: High-tech dark glassmorphism dashboard inspired by enterprise gaming control centers (Lucide icons, real-time polling, responsive layout).
- Views:
  1. `DashboardView` (Live stats, CCU, tables, server health).
  2. `OperatorsView` (Manage partner platforms & API keys).
  3. `LiveTablesView` (Inspect active games & cross-platform seats).
  4. `FinanceView` (Rake revenue, GGR, settlements).
  5. `FraudView` (Collusion detection & alerts).
  6. `SystemHealthView` (JVM metrics, Table Draining switch).

---

### 🔹 Phase 5: Docker & Deployment Integration
- Add `rummy-admin` service to `deployment/docker/docker-compose.yml` (mapped to port `8083` or `8080`).
- Update `deployment/kubernetes/ingress.yaml` to route `admin.royalrummy.example.com` to the Admin Portal.
- Update `HOW_TO_RUN.md` with standalone Admin Portal startup commands.
