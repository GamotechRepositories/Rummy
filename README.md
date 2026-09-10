# Royal Rummy Platform

A production-grade, distributed, real-time Rummy platform designed for 300,000–400,000 concurrent players.

> [!NOTE]
> **Platform Architecture:** This repository implements a production-grade, distributed, real-time multiplayer card gaming engine. The system operates server-authoritative TableActor clusters with zero-knowledge hand security. Development and testing are conducted in **Free-Play (Virtual Points) Mode**.

---

## 🏗️ Technology Stack

- **Frontend:** React + TypeScript (Vite), Zustand, WebSocket client (WSS)
- **Backend:** Java 21 + Spring Boot (WebFlux / Netty)
- **Real-Time Transport:** Secure WebSocket (WSS) with event sequence tracking
- **In-Memory Game Engine:** Server-authoritative Actor model (`TableActor`)
- **Coordination & Routing:** Redis Cluster (sessions, routing, presence, matchmaking)
- **Persistence:** MongoDB (application data, game history, user profiles)
- **Financial Ledger:** MongoDB (dedicated immutable double-entry transaction ledger)
- **Event Streaming:** Apache Kafka (audit, history, analytics, notifications)
- **Deployment:** Docker & Kubernetes / ECS

---

## 📁 Repository Structure

```text
rummy-platform/
├── frontend/                     # React + TypeScript web application
│   └── rummy-web/
├── backend/                      # Java 21 + Spring Boot microservices
│   ├── api-service/              # REST APIs (Auth, User, Profile)
│   ├── game-service/             # WebSocket Game Server & Engine
│   ├── matchmaking-service/      # Queue & table allocation
│   └── notification-service/     # Push & in-app alerts
├── shared/                       # Shared schemas and libraries
│   ├── game-contracts/           # Commands, events, and state models
│   ├── event-contracts/          # Kafka event schemas
│   └── common-security/          # Auth & token validation helpers
├── infrastructure/               # Database & messaging setup scripts
│   ├── mongodb/                  # Indexes, schemas, initial seeds
│   ├── postgres/                 # Migration scripts (if applicable)
│   ├── redis/                    # Cluster configuration
│   ├── kafka/                    # Topic configurations
│   └── monitoring/               # Prometheus & Grafana configs
├── load-test/                    # Scale & concurrency simulation
│   ├── player-simulator/         # Virtual player bots
│   ├── websocket-load/           # Connection & reconnect storm tests
│   └── scenarios/                # Gameplay load profiles
├── deployment/                   # Containerization & orchestration
│   ├── docker/                   # Dockerfiles and docker-compose
│   ├── kubernetes/               # Helm charts / K8s manifests
│   └── environments/             # Environment configs (dev/stage/prod)
└── docs/                         # Engineering and architecture documentation
    ├── architecture/             # High-level architecture & diagrams
    ├── api/                      # REST & WebSocket protocol specifications
    ├── game-rules/               # Rulesets (Points, Pool, Deals, 21-Card, etc.)
    ├── security/                 # Security controls & threat modeling
    ├── operations/               # Runbooks, monitoring, DR
    └── compliance/               # Player protection and responsible gaming
```

---

## 🎯 Master Roadmap & Specification

For complete details on game variants, table state machines, scoring algorithms, and multi-phase roadmap, see:
- [Master Specification README](file:///D:/Projects/Rummy/Rummy/Rummy_Real_Money_Australia_Master_README%20%284%29.md)
- [Architecture Overview](file:///D:/Projects/Rummy/Rummy/docs/architecture/overview.md)
- [Game Rules Specification](file:///D:/Projects/Rummy/Rummy/docs/game-rules/rummy-rules.md)
- [Compliance & Legal Notice](file:///D:/Projects/Rummy/Rummy/docs/compliance/legal-notice.md)

---

## 🚀 Development Roadmap

1. **Step 01:** Repository Setup & Architecture Docs *(Completed)*
2. **Step 02:** Game Domain Model & Primitives (`Card`, `Suit`, `Rank`, `Deck`)
3. **Step 03:** GameState, TurnState, and PlayerState
4. **Step 04:** Indian 13-Card / Points Rummy Rules & Validators
5. **Step 05:** Server-Authoritative Game Engine & Tests
6. **Step 06:** AI Bot Player Engine
7. **Step 07:** WebSocket Gateway & Real-Time Protocol
8. **Step 08:** React Game Client (Interactive Table UI)
9. **Step 09:** Event Sequencing, Reconnection, and Resync
10. **Step 10:** MongoDB Persistence & History Tracking
