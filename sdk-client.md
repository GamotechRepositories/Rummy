# Client SDK (Browser)

**Package:** `@gamotech/game-sdk-client`  
**Folder:** `sdks/client/`  
**Runs on:** Browser (React, Vue, plain JavaScript)

**API base URL:** `https://api.dpbossking.com/api/v1`

For wallet operations use [sdk-nodejs.md](./sdk-nodejs.md) or [sdk-java.md](./sdk-java.md) on your game server — never in the browser.

See also: [game-sdk.md](./game-sdk.md) · [sdk-nodejs.md](./sdk-nodejs.md) · [sdk-java.md](./sdk-java.md)

---

## Purpose

- Validate `sessionToken` from launch URL
- Send session lifecycle events (`TABLE_CREATED`, `ROUND_STARTED`, etc.)
- Expose player info (`playerId`, `operatorId`, `currency`) after validation

---

## Install

```bash
npm install @gamotech/game-sdk-client
```

---

## Quick start

```javascript
import { ProviderGameSDK } from "@gamotech/game-sdk-client";

const sdk = new ProviderGameSDK({
  apiBaseUrl: "https://api.dpbossking.com/api/v1",
});

// Player arrives at: https://your-game.com/?sessionToken=...
const session = await sdk.initFromUrl();
sdk.attachUnloadHandler();

console.log(session.playerId, session.operatorId, session.currency);

await sdk.createTable({});
const roundId = await sdk.createRound({});
await sdk.startRound({ roundId, betAmount: 100 });
await sdk.endRound({ roundId, result: "WIN" });
```

---

## API

| Method | Description |
|--------|-------------|
| `initFromUrl(search?)` | Read `sessionToken` from URL query and validate |
| `init(sessionToken)` | Validate a token manually |
| `createTable({ tableId?, ...payload })` | Send `TABLE_CREATED` event |
| `createRound({ roundId?, ...payload })` | Send `ROUND_CREATED` event |
| `startRound({ roundId, ...payload })` | Send `ROUND_STARTED` event |
| `endRound({ roundId, ...payload })` | Send `ROUND_ENDED` event |
| `endSession(payload?)` | Send `SESSION_ENDED` event |
| `sendEvent(event, { tableId?, roundId?, payload? })` | Send custom event |
| `attachUnloadHandler()` | Auto-send `SESSION_ENDED` when player closes tab |

---

## Properties (after init)

| Property | Description |
|----------|-------------|
| `sessionToken` | Current session token |
| `session` | Full session object from validate |
| `operatorId` | Operator id (from session) |
| `playerId` | Player id (from session) |
| `playerUsername` | Player username (from session) |
| `gameCode` | Game code (from session) |
| `currency` | Currency code (from session) |

---

## REST endpoints used

| Action | Method | Path |
|--------|--------|------|
| Validate session | POST | `/sessions/validate` |
| Send event | POST | `/sessions/events` |

No secret key required for client calls.

---

## Environment

```env
NEXT_PUBLIC_PROVIDER_API_URL=https://api.dpbossking.com/api/v1
```

Safe to expose in frontend — this SDK has no secrets.

---

## Errors

Throws `ProviderSDKError`:

| Property | Description |
|----------|-------------|
| `message` | Error description |
| `status` | HTTP status (if available) |
| `data` | Raw API response |

| Error | Fix |
|-------|-----|
| Missing sessionToken | Player must launch from operator URL with `?sessionToken=` |
| SDK not initialized | Call `init()` or `initFromUrl()` first |
| Session has ended | Player must re-launch from operator |

---

## Publish (maintainers)

```bash
cd sdks/client
npm publish --access public
```
