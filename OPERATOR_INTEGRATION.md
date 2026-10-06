# Operator integration (B2B, seamless wallet)

Royal Rummy is offered to licensed operators. The operator owns the player account, KYC,
deposits, withdrawals and the player's money. We run the game and move money only by calling the
operator's wallet API. Currency is INR (₹); amounts are strings with two decimals, e.g. `"100.00"`.

There are two directions:

1. **Operator → us:** launch a player into the game.
2. **Us → operator:** wallet calls (`balance`, `debit`, `credit`, `rollback`).

Both directions are signed the same way.

## 1. Onboarding

We create the operator with the admin API (header `X-Admin-Key`):

```http
POST /api/admin/operators
{"id": "acme", "name": "Acme Games",
 "walletUrl": "https://wallet.acme.com/rummy",
 "cashierUrl": "https://acme.com/cashier"}
```

The response contains `secret`. It is shown **only here** (and on rotation); send it to the
operator over a secure channel. Other admin calls:

| Call | Purpose |
|------|---------|
| `GET /api/admin/operators` | List operators (secrets never shown) |
| `PATCH /api/admin/operators/{id}` | Change `name`, `walletUrl`, `cashierUrl`, `enabled` |
| `POST /api/admin/operators/{id}/rotate-secret` | New secret; the old one stops working at once |

Disabling an operator blocks new launches and new sessions immediately.

## 2. Request signing

Every request in both directions carries:

| Header | Value |
|--------|-------|
| `X-Rummy-Operator` | operator id, e.g. `acme` |
| `X-Rummy-Timestamp` | Unix time in seconds |
| `X-Rummy-Signature` | `hex(HMAC_SHA256(secret, timestamp + "." + rawBody))`, lowercase |

The receiver recomputes the signature over the **exact raw body bytes** and rejects the request
if it does not match or if the timestamp is more than 5 minutes away from its clock.

## 3. Launching a player (operator → us)

From the operator's server (never from the browser):

```http
POST https://<game-host>/api/operator/launch
Content-Type: application/json
X-Rummy-Operator / X-Rummy-Timestamp / X-Rummy-Signature

{"playerId": "user-12345", "displayName": "Ravi"}
```

* `playerId`: the operator's own id for the player, 1–64 chars of `A-Z a-z 0-9 _ . @ : -`.
  It never reaches the browser. In our system the player gets an opaque id (`P_...`).
* `displayName`: shown to other players at the table (max 24 chars after cleaning).

Response `200`:

```json
{"launchUrl": "https://play.example.com/?launch=<one-time code>", "expiresInSeconds": 120}
```

Send the player's browser (or webview) to `launchUrl`. The code works **once** and expires after
2 minutes; get a new URL for every launch. The session that results lasts 12 hours.

Errors: `401` bad signature, unknown or disabled operator; `400` invalid body.

## 4. Wallet API (us → operator)

We POST JSON to `{walletUrl}/balance`, `{walletUrl}/debit`, `{walletUrl}/credit` and
`{walletUrl}/rollback`, signed as above with `X-Rummy-Operator` set to the operator's id.

Request body:

```json
{
  "transactionId": "STAKE_TKT_92fa3afa7119411f",
  "playerId": "user-12345",
  "amount": "100.00",
  "currency": "INR",
  "gameId": "G_...",
  "type": "GAME_ENTRY_STAKE",
  "description": "Table entry stake for INDIAN_POINTS"
}
```

`balance` has only `playerId` and `currency`. `gameId` may be absent (stakes are taken before a
table exists).

Response (HTTP 200 for every business outcome):

```json
{"status": "OK", "balance": "9900.00"}
```

| `status` | Meaning |
|----------|---------|
| `OK` | Done (or already done earlier with this `transactionId`) |
| `INSUFFICIENT_FUNDS` | Debit refused, not enough money |
| `PLAYER_NOT_FOUND` | Unknown player |
| `PLAYER_BLOCKED` | Player may not play (self-exclusion, limits, KYC, region, ...) |
| `REJECTED` | Any other refusal |

Always return the player's balance after the call when you can.

### Idempotency (required)

`transactionId` is unique per money movement. If you receive a `transactionId` you have already
processed, **do not apply it again**: answer `OK` with the current balance. We retry with the same
id after timeouts and errors, so this is what prevents double charges and double payouts.

### Transaction types

| `type` | Call | When |
|--------|------|------|
| `GAME_ENTRY_STAKE` | debit | Player joins a table |
| `REJOIN_FEE` | debit | Pool rummy rejoin |
| `GAME_ENTRY_REFUND` | credit | Matchmaking cancelled, table never started |
| `GAME_REFUND` | credit | Unlost part of a points-rummy stake |
| `GAME_WIN` | credit | Winnings (includes the winner's own stake back in points rummy) |
| `GAME_ABORT_REFUND` / `GAME_CRASH_REFUND` | credit | A match was cancelled; stake returned |
| `REJOIN_REFUND` | credit | A rejoin fee returned (rejoin could not complete) |

### Rollback

`POST {walletUrl}/rollback` with `transactionId` = the id of a **debit** we could not confirm.

* If you applied that debit: undo it (return the money) and answer `OK`.
* If you never saw it: answer `OK`, and **refuse any later debit with that id** (`REJECTED`).
* Repeated rollbacks of the same id: answer `OK`, change nothing.

### Timeouts and failures

* Each call has a 5 second timeout.
* **Debit:** a timeout, connection error or HTTP 5xx is retried once with the same id. If the
  outcome is still unknown, the player is told to try again, and we send a `rollback` for that id
  until you answer `OK`.
* **Credit:** a credit that fails is never dropped. It is queued and retried with the same id
  (backoff up to 10 minutes) until you answer `OK`. Please accept credits even for players who are
  blocked, since this money is theirs.
* HTTP 4xx without a `status` field counts as a refusal.

### Cashier

`cashierUrl` is opened in a new tab when the player taps "Add Cash / Withdraw" in the game. All
deposits and withdrawals happen there; the game never handles payment details.

## 5. Testing without an operator

Run the backend with `RUMMY_MOCK_OPERATOR_ENABLED=true` (locally: `backend/run-local.ps1`), then open
`<backend>/mock-operator/`. The built-in test operator gives each new test player ₹10,000 of test
money, stored in MongoDB (`mock_operator_wallets`, `mock_operator_tx`), with top-ups on the same page.

- Test players are matched only with other test players (and bots), never with real-money players.
- On a live server (`prod` profile) it also needs `RUMMY_MOCK_OPERATOR_KEY` (12+ characters); the
  page then asks for that key, and the server refuses to start with the test operator on but no key.
- Turn it off again by removing `RUMMY_MOCK_OPERATOR_ENABLED`.
