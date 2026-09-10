# Player Protection & Responsible Gaming Architecture

## 1. Safety & Fair Play Context
The Royal Rummy platform is engineered with proactive player safety controls, server-authoritative integrity, and responsible play safeguards.

---

## 2. Platform Compliance Controls & Safe Play Architecture

### 2.1 Virtual Free-Play Mode Enforcement
- The platform default operating state is locked to **`FREE_PLAY_ONLY`**.
- All currency balances are designated as non-redeemable virtual tokens (`TOKENS`).
- Player balance updates and table entry stakes operate via an immutable double-entry ledger.

### 2.2 Responsible Gaming & Player Protection Tools
The platform provides comprehensive player protection controls:
1. **Daily Play Duration Limits**: Enforced per-player daily limits (default 120 mins).
2. **Loss Limits**: Configurable token loss threshold per 24-hour cycle.
3. **Reality Checks**: Periodic automated reminders (every 15/30/60 minutes).
4. **Cool-off Periods**: Temporary exclusion breaks for 24 hours, 7 days, or 30 days.
5. **Self-Exclusion**: Account exclusion that blocks table seat allocation at the WebSocket gateway.

### 2.3 Player Support Resources
Integrated into all player-facing protection modals:
- Confidential gameplay consultation and time management tools.
- Self-imposed account cool-off breaks and limits.
