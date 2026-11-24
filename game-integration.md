# Integration Guide

End-to-end guide for onboarding an operator, listing enabled games, launching a player, and running the game session.

**Base URL (all external calls):**

```
https://api.dpbossking.com/api/v1
```

Local dev: `http://localhost:3000/api/v1`

---

## Who does what

| Actor | Responsibility |
|-------|----------------|
| **Platform admin** | Creates operator, game, and assigns enabled games in the admin panel |
| **Operator backend** | Calls launch API with HMAC; redirects player to `launchUrl` |
| **Operator frontend** | Calls enabled-games API to show game lobby |
| **Game client** | Reads `sessionToken` from URL, validates session, sends lifecycle events |

```
Admin panel
    │  creates operator + assigns games
    ▼
Operator frontend ──GET /enabled-games──► game lobby
    │
Operator backend ──POST /launch────────► sessionToken + launchUrl
    │
    ▼
Player browser opens launchUrl (?sessionToken=...)
    │
    ▼
Game client ──POST /sessions/validate──► gameplay
Game client ──POST /sessions/events────► lifecycle tracking
```

---

## Phase 1 — Platform onboarding (admin)

Before any API calls work, the following must exist in the **admin panel** (`admin.dpbossking.com`).

### 1. Create the game

Each game needs a record in the Games API with at minimum:

| Field | Example | Notes |
|-------|---------|-------|
| `code` | `TEENPATTI` | Used in launch request `gameCode` |
| `slug` | `teen-patti` | Alternative lookup key |
| `name` | `Teen Patti` | Display name |
| `status` | `ACTIVE` | Must be active to launch |
| `launchUrl` | `https://your-game.com/` | Where the player is redirected |
| `demoUrl` | `https://your-game.com/demo` | Optional demo link |
| `thumbnail` | `https://...` | Shown in operator lobby |
| `maintenanceMode` | `false` | Blocks launch if `true` |

The game client must be deployed at `launchUrl` and ready to read `sessionToken` from the query string.

### 2. Create the operator

Each operator needs:

| Field | Example | Notes |
|-------|---------|-------|
| `operatorId` | `AAKDA-001` | Sent in every launch request |
| `apiKey` | `pk_live_...` | Sent as `X-API-Key` header |
| `apiSecretPath` | AWS secret path | Used server-side for HMAC — **never sent in requests** |
| `status` | `ACTIVE` | Must be active |
| `currency` | `INR` | Launch body `currency` must match exactly |
| `language` | `en` | Passed into session |
| `timezone` | `Asia/Kolkata` | Passed into session |
| `sessionTimeout` | `3600` | Session TTL in seconds (default 1 hour) |
| `maintenanceMode` | `false` | Blocks all launches if `true` |
| `enabledGames` | `[{ _id, code }]` | Games this operator can launch |

### 3. Enable games for the operator

In the operator record, add each game to `enabledGames`:

```json
{
  "enabledGames": [
    { "_id": "6a95526f52ceb66fa14e0012", "code": "TEENPATTI" },
    { "_id": "7b06637g63dfc77gb25f1123", "code": "ANDARBAHAR" }
  ]
}
```

Launch will fail with **403** if the requested `gameCode` is not in this list.

### 4. Store the HMAC secret

The operator's signing secret is stored in **AWS Secrets Manager** at the path defined by `apiSecretPath`. The operator backend uses this secret to sign launch requests. Provider fetches it server-side during validation — it is never exposed in API responses.

---

## Phase 2 — Operator integration

The operator integrates on their **backend** (for launch) and **frontend** (for game lobby).

### Step 1 — List enabled games (lobby)

No authentication required. Call from the operator's frontend to render the game catalog.

```http
GET /api/v1/enabled-games?operatorId=AAKDA-001
```

**Success (200):**

```json
{
  "success": true,
  "operatorId": "AAKDA-001",
  "operatorName": "Aakda",
  "count": 2,
  "enabledGames": [
    {
      "_id": "6a95526f52ceb66fa14e0012",
      "name": "Teen Patti",
      "slug": "teen-patti",
      "code": "TEENPATTI",
      "status": "ACTIVE",
      "thumbnail": "https://cdn.example.com/teen-patti.png",
      "launchUrl": "https://your-game.com/",
      "demoUrl": "https://your-game.com/demo",
      "maintenanceMode": false
    }
  ]
}
```

Use `code` as the `gameCode` when calling launch. Show `thumbnail` and `name` in the lobby UI.

**Errors:**

| Status | Meaning |
|--------|---------|
| 400 | Missing `operatorId` query param |
| 404 | Operator not found |
| 502 | Enabled games service unavailable |

---

### Step 2 — Validate operator credentials (optional test)

Use this during integration to verify HMAC signing before attempting a full launch.

```http
POST /api/v1/validate-operator
Content-Type: application/json
X-API-Key: pk_live_...
X-Timestamp: 1725440000
X-Signature: <hmac-hex>

{
  "operatorId": "AAKDA-001",
  "playerId": "P1001",
  "playerUsername": "john_doe",
  "gameCode": "TEENPATTI",
  "currency": "INR"
}
```

**Success (200):**

```json
{
  "success": true,
  "message": "Operator validated successfully",
  "operator": {
    "operatorId": "AAKDA-001",
    "name": "Aakda",
    "currency": "INR",
    "sessionTimeout": 3600
  },
  "playerId": "P1001",
  "playerUsername": "john_doe",
  "game": {
    "code": "TEENPATTI",
    "name": "Teen Patti",
    "launchUrl": "https://your-game.com/"
  }
}
```

This does **not** create a session or return a `sessionToken`.

---

### Step 3 — Launch a game (required)

Call from the **operator backend only** — never from the browser (the secret must stay server-side).

```http
POST /api/v1/launch
Content-Type: application/json
X-API-Key: pk_live_...
X-Timestamp: 1725440000
X-Signature: <hmac-hex>

{
  "operatorId": "AAKDA-001",
  "playerId": "P1001",
  "playerUsername": "john_doe",
  "gameCode": "TEENPATTI",
  "currency": "INR"
}
```

**Success (200):**

```json
{
  "success": true,
  "message": "Launch successful",
  "sessionToken": "a1b2c3d4e5f6789...",
  "expiresAt": "2026-09-06T13:00:00.000Z",
  "launchUrl": "https://your-game.com/?sessionToken=a1b2c3d4e5f6789...",
  "launch": {
    "operatorId": "AAKDA-001",
    "playerId": "P1001",
    "playerUsername": "john_doe",
    "gameCode": "TEENPATTI",
    "gameName": "Teen Patti",
    "launchUrl": "https://your-game.com/?sessionToken=a1b2c3d4e5f6789..."
  }
}
```

**Redirect the player** to `launchUrl` (iframe or new tab). The URL contains only `sessionToken` — no `operatorId` or `playerId`.

**Launch errors:**

| Status | Meaning |
|--------|---------|
| 400 | Missing body fields or currency mismatch |
| 401 | Invalid/missing HMAC headers or bad signature |
| 403 | Operator inactive, game not enabled, or game inactive |
| 404 | Operator not found |
| 500 | Session creation failed or game missing `launchUrl` |
| 503 | Operator or game in maintenance mode |

---

### HMAC signing (operator backend)

Every signed request (`/launch`, `/validate-operator`) requires three headers:

| Header | Value |
|--------|-------|
| `X-API-Key` | Operator's `apiKey` from admin panel |
| `X-Timestamp` | Current Unix timestamp (seconds) |
| `X-Signature` | HMAC-SHA256 hex digest |

**Signature payload:**

```
{timestamp}\n{METHOD}\n{path}\n{rawBody}
```

| Part | Value |
|------|-------|
| `timestamp` | Same as `X-Timestamp` header |
| `METHOD` | Uppercase HTTP method, e.g. `POST` |
| `path` | Gateway path: `/api/v1/launch` or `/api/v1/validate-operator` |
| `rawBody` | Exact JSON string sent in the request body (no extra whitespace) |

**Example payload:**

```
1725440000
POST
/api/v1/launch
{"operatorId":"AAKDA-001","playerId":"P1001","playerUsername":"john_doe","gameCode":"TEENPATTI","currency":"INR"}
```

**Node.js signing example:**

```javascript
import crypto from "crypto";

function signRequest({ secret, method, path, body }) {
  const timestamp = Math.floor(Date.now() / 1000).toString();
  const rawBody = JSON.stringify(body);
  const payload = `${timestamp}\n${method.toUpperCase()}\n${path}\n${rawBody}`;
  const signature = crypto.createHmac("sha256", secret).update(payload).digest("hex");

  return { timestamp, signature, rawBody };
}

// Usage
const { timestamp, signature, rawBody } = signRequest({
  secret: process.env.OPERATOR_SECRET,
  method: "POST",
  path: "/api/v1/launch",
  body: {
    operatorId: "AAKDA-001",
    playerId: "P1001",
    playerUsername: "john_doe",
    gameCode: "TEENPATTI",
    currency: "INR",
  },
});

const res = await fetch("https://api.dpbossking.com/api/v1/launch", {
  method: "POST",
  headers: {
    "Content-Type": "application/json",
    "X-API-Key": process.env.OPERATOR_API_KEY,
    "X-Timestamp": timestamp,
    "X-Signature": signature,
  },
  body: rawBody,
});
```

**Dev helper** (generate a signature locally):

```bash
node launchService/scripts/generate-signature.js \
  POST /api/v1/launch 1725440000 sk_live_test \
  '{"operatorId":"AAKDA-001","playerId":"P1001","playerUsername":"john_doe","gameCode":"TEENPATTI","currency":"INR"}'
```

Timestamp tolerance is **300 seconds** (5 minutes). Requests outside this window are rejected.

---

## Phase 3 — Game client integration

Once the player is redirected to `launchUrl`, the **game** takes over.

### What the game receives

```
https://your-game.com/?sessionToken=a1b2c3d4e5f6789...
```

The game must:

1. Read `sessionToken` from the query string on page load.
2. Store it in memory for all subsequent API calls.
3. Never expect `operatorId` or `playerId` in the URL — those come from validate.

---

### Game lifecycle

```
Player opens launchUrl (?sessionToken=...)
        │
        ▼
1. POST /sessions/validate          ← required first step
        │
        ▼
2. Game creates table locally
        │
        ▼
3. POST /sessions/events  TABLE_CREATED
        │
        ▼
4. Game creates round locally
        │
        ▼
5. POST /sessions/events  ROUND_CREATED
6. POST /sessions/events  ROUND_STARTED
        │
        ▼
   (gameplay)
        │
        ▼
7. POST /sessions/events  ROUND_ENDED
        │
        ▼
   (repeat steps 4–7 for more rounds)
        │
        ▼
8. POST /sessions/events  SESSION_ENDED   ← when player leaves
```

---

### Step 1 — Validate session (required)

Call **once on load**, before any gameplay logic.

```http
POST /api/v1/sessions/validate
Content-Type: application/json

{
  "sessionToken": "a1b2c3d4e5f6789..."
}
```

**Alternative:** pass the token as a Bearer header:

```http
Authorization: Bearer a1b2c3d4e5f6789...
```

**Success (200):**

```json
{
  "success": true,
  "message": "Session valid",
  "session": {
    "sessionToken": "a1b2c3d4e5f6789...",
    "operatorId": "AAKDA-001",
    "playerId": "P1001",
    "playerUsername": "john_doe",
    "gameCode": "TEENPATTI",
    "currency": "INR",
    "language": "en",
    "timezone": "UTC",
    "status": "ACTIVE",
    "gameContext": {
      "tableId": null,
      "currentRoundId": null
    },
    "createdAt": "2026-09-06T12:00:00.000Z",
    "expiresAt": "2026-09-06T13:00:00.000Z"
  }
}
```

Use `session.operatorId`, `session.playerId`, `session.playerUsername`, `session.gameCode`, and `session.currency` to configure the game. Do **not** trust values from the URL.

**Errors — block the player:**

| Status | Meaning | Game action |
|--------|---------|-------------|
| 401 | Missing token, expired session | Show "Session expired" |
| 403 | Session `ENDED` or `REVOKED` | Show "Session closed" |
| 404 | Token not found | Show "Invalid session" |

---

### Step 2 — Report lifecycle events

```http
POST /api/v1/sessions/events
Content-Type: application/json

{
  "sessionToken": "a1b2c3d4e5f6789...",
  "event": "TABLE_CREATED",
  "tableId": "table_789",
  "payload": {}
}
```

#### Event reference

| Event | When to send | Required fields | Updates session state |
|-------|--------------|-----------------|----------------------|
| `TABLE_CREATED` | After game creates a table | `tableId` | Sets `gameContext.tableId` |
| `ROUND_CREATED` | After game creates a round | `roundId` | Sets `gameContext.currentRoundId` |
| `ROUND_STARTED` | When betting/gameplay begins | `roundId` | Sets `gameContext.currentRoundId` |
| `ROUND_ENDED` | When round finishes | `roundId` | No context change |
| `SESSION_ENDED` | Player leaves or game closes | — | Sets `status` → `ENDED` |

`tableId` and `roundId` can be top-level fields **or** inside `payload`.

#### Examples

**Table created:**

```json
{
  "sessionToken": "...",
  "event": "TABLE_CREATED",
  "tableId": "table_789"
}
```

**Round started:**

```json
{
  "sessionToken": "...",
  "event": "ROUND_STARTED",
  "roundId": "round_001",
  "payload": { "betAmount": 100 }
}
```

**Round ended:**

```json
{
  "sessionToken": "...",
  "event": "ROUND_ENDED",
  "roundId": "round_001",
  "payload": { "result": "WIN", "payout": 200 }
}
```

**Session ended:**

```json
{
  "sessionToken": "...",
  "event": "SESSION_ENDED",
  "payload": { "reason": "PLAYER_LEFT" }
}
```

After `SESSION_ENDED`, further event calls return **403**.

---

### Game client JavaScript example

```javascript
const API_BASE = "https://api.dpbossking.com/api/v1";

function getSessionTokenFromUrl() {
  return new URLSearchParams(window.location.search).get("sessionToken");
}

async function validateSession(sessionToken) {
  const res = await fetch(`${API_BASE}/sessions/validate`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ sessionToken }),
  });
  const data = await res.json();
  if (!res.ok) throw new Error(data.message || "Session invalid");
  return data.session;
}

async function sendEvent(sessionToken, event, { tableId, roundId, payload } = {}) {
  const res = await fetch(`${API_BASE}/sessions/events`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ sessionToken, event, tableId, roundId, payload }),
  });
  const data = await res.json();
  if (!res.ok) throw new Error(data.message || "Event failed");
  return data;
}

async function bootGame() {
  const sessionToken = getSessionTokenFromUrl();
  if (!sessionToken) throw new Error("Missing sessionToken in URL");

  const session = await validateSession(sessionToken);

  const tableId = `table_${Date.now()}`;
  await sendEvent(sessionToken, "TABLE_CREATED", { tableId });

  const roundId = `round_${Date.now()}`;
  await sendEvent(sessionToken, "ROUND_CREATED", { roundId });
  await sendEvent(sessionToken, "ROUND_STARTED", { roundId, payload: { betAmount: 100 } });

  // ... gameplay using session.operatorId, session.playerId, session.playerUsername, session.currency ...

  await sendEvent(sessionToken, "ROUND_ENDED", { roundId, payload: { result: "WIN" } });
}

window.addEventListener("beforeunload", () => {
  const sessionToken = getSessionTokenFromUrl();
  if (!sessionToken) return;
  navigator.sendBeacon(
    `${API_BASE}/sessions/events`,
    JSON.stringify({ sessionToken, event: "SESSION_ENDED", payload: { reason: "PLAYER_LEFT" } })
  );
});
```

---

## End-to-end flow (all phases)

```
┌─────────────────────────────────────────────────────────────────┐
│ PHASE 1 — Admin setup (one-time)                                │
│  • Create game with launchUrl + code                            │
│  • Create operator with apiKey + apiSecretPath                  │
│  • Assign enabledGames to operator                              │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│ PHASE 2 — Operator (every session)                              │
│                                                                 │
│  Lobby:  GET  /enabled-games?operatorId=AAKDA-001               │
│          → show game list with thumbnails                         │
│                                                                 │
│  Launch: POST /launch  (HMAC signed, server-side)               │
│          → { launchUrl: "https://game.com/?sessionToken=..." }  │
│          → redirect player to launchUrl                         │
└─────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│ PHASE 3 — Game client (every session)                           │
│                                                                 │
│  1. POST /sessions/validate   → get playerId, operatorId, etc.  │
│  2. POST /sessions/events     → TABLE_CREATED                   │
│  3. POST /sessions/events     → ROUND_CREATED / STARTED / ENDED │
│  4. POST /sessions/events     → SESSION_ENDED on exit           │
└─────────────────────────────────────────────────────────────────┘
```

---

## API reference by caller

| Endpoint | Caller | Auth |
|----------|--------|------|
| `GET /enabled-games?operatorId=` | Operator frontend | None |
| `POST /validate-operator` | Operator backend | HMAC |
| `POST /launch` | Operator backend | HMAC |
| `POST /sessions/validate` | Game client | `sessionToken` |
| `POST /sessions/events` | Game client | `sessionToken` |
| `POST /sessions` | Launch Service (internal) | — |
| `GET /sessions/track` | Admin / support | `sessionToken` |

---

## Onboarding checklist

### Admin

- [ ] Game created with `code`, `launchUrl`, `status: ACTIVE`
- [ ] Operator created with `apiKey`, `apiSecretPath`, `currency`
- [ ] Game added to operator's `enabledGames`
- [ ] HMAC secret stored in AWS at `apiSecretPath`
- [ ] Operator `status` is `ACTIVE`, `maintenanceMode` is `false`

### Operator backend

- [ ] HMAC signing implemented with path `/api/v1/launch`
- [ ] `validate-operator` tested successfully
- [ ] `launch` returns `launchUrl` with `sessionToken`
- [ ] Player redirected to `launchUrl` (not constructing URL manually)
- [ ] Secret never exposed to browser

### Operator frontend

- [ ] `GET /enabled-games` populates game lobby
- [ ] Launch button calls operator backend (not Provider API directly)

### Game client

- [ ] Reads `sessionToken` from URL query param
- [ ] Calls `/sessions/validate` before gameplay
- [ ] Sends `TABLE_CREATED`, round events, and `SESSION_ENDED`
- [ ] Handles 401/403/404 with user-friendly error screens
- [ ] Uses gateway URL (`api.dpbossking.com`), not session service directly

---

## Troubleshooting

| Problem | Phase | Likely cause |
|---------|-------|--------------|
| Empty enabled games list | Operator | Game not assigned in admin `enabledGames` |
| 403 "Game not enabled" | Operator | `gameCode` not in operator's enabled list |
| 401 Invalid signature | Operator | Wrong secret, wrong path, or body mismatch |
| 401 Timestamp expired | Operator | Clock skew > 5 minutes |
| 400 Currency mismatch | Operator | Body `currency` ≠ operator's configured currency |
| 401 on validate | Game | Token missing, wrong, or expired |
| 403 on events | Game | Session already ended |
| 502 Bad Gateway | Any | Backend microservice not running |
| Empty `sessionToken` in URL | Launch | Session creation failed — check Session Service |
| Game loads but no player data | Game | Skipped `/sessions/validate` step |
