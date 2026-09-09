# Australian Compliance & Interactive Gambling Act 2001 (IGA) Manual

## 1. Regulatory Context
Under Australia's *Interactive Gambling Act 2001 (IGA)* and ACMA (Australian Communications and Media Authority) guidelines:
- Providing prohibited interactive gambling services (such as online real-money casinos and unlicensed wagering) to customers in Australia is strictly illegal.
- ACMA actively maintains enforcement actions and blocks unlicensed offshore gambling services.

## 2. Platform Compliance Controls & Safe Play Architecture

### 2.1 Virtual Free-Play Mode Enforcement
- The platform default operating state is locked to **`FREE_PLAY_ONLY`**.
- All currency balances are designated as `AUD_FREE_PLAY` with non-redeemable virtual tokens.
- Real-money deposits, wagering, and withdrawals remain disabled until Australian legal counsel written approval is logged.

### 2.2 Responsible Gambling & Player Protection Tools
The platform provides comprehensive player protection controls:
1. **Daily Play Duration Limits**: Enforced per-player daily limits (default 120 mins).
2. **Loss Limits**: Configurable token loss threshold per 24-hour cycle.
3. **Reality Checks**: Periodic automated reminders (every 15/30/60 minutes).
4. **Cool-off Periods**: Temporary exclusion breaks for 24 hours, 7 days, or 30 days.
5. **Self-Exclusion**: Permanent or timed account exclusion that blocks table seat allocation at the WebSocket gateway.

### 2.3 National Support Helpline Integration
Prominently integrated into all player-facing protection modals:
- **Gambling Help Online**: Free, confidential 24/7 counseling via `1800 858 858` or [gamblinghelponline.org.au](https://www.gamblinghelponline.org.au)
- **BetStop**: The National Self-Exclusion Register ([betstop.gov.au](https://www.betstop.gov.au))
