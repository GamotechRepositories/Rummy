# Java SDK (Game Server)

**Maven:** `in.oreng:game-sdk:1.0.0`  
**Folder:** `sdks/java/`  
**Runs on:** Java 11+ game server (Spring Boot, etc.)

**API base URL:** `https://api.dpbossking.com/api/v1`

For browser session/events use [sdk-client.md](./sdk-client.md). For Node.js servers use [sdk-nodejs.md](./sdk-nodejs.md).

See also: [game-sdk.md](./game-sdk.md) · [sdk-client.md](./sdk-client.md) · [sdk-nodejs.md](./sdk-nodejs.md)

---

## Purpose

Server-side wallet operations:

- `getBalance` — fetch player balance
- `getPlayerProfile` — fetch player profile
- `debit` — deduct from wallet
- `credit` — add to wallet

`operatorId` is resolved from `sessionToken` on Provider — do **not** pass it.

---

## Install

**Maven Central** (after publish):

```xml
<dependency>
  <groupId>in.oreng</groupId>
  <artifactId>game-sdk</artifactId>
  <version>1.0.0</version>
</dependency>
```

**Local build** (before Maven Central):

```bash
cd sdks/java
mvn install
```

---

## Quick start

```java
import in.oreng.gamesdk.ProviderGameServerSDK;
import in.oreng.gamesdk.WalletOperationRequest;
import com.google.gson.JsonObject;

ProviderGameServerSDK wallet = new ProviderGameServerSDK(
    System.getenv("PROVIDER_API_URL"),
    System.getenv("GAME_SERVER_API_KEY")
);

JsonObject balance = wallet.getBalance(sessionToken);
JsonObject profile = wallet.getPlayerProfile(sessionToken);

JsonObject debitResult = wallet.debit(
    WalletOperationRequest.builder()
        .sessionToken(sessionToken)
        .amount(100)
        .transactionId("tx_abc123")
        .roundId("round_001")
        .tableId("table_001")
        .build()
);

JsonObject creditResult = wallet.credit(
    WalletOperationRequest.builder()
        .sessionToken(sessionToken)
        .amount(200)
        .transactionId("tx_def456")
        .roundId("round_001")
        .build()
);
```

---

## Spring Boot example

```java
@RestController
@RequestMapping("/api")
public class BetController {

  private final ProviderGameServerSDK wallet = new ProviderGameServerSDK(
      System.getenv("PROVIDER_API_URL"),
      System.getenv("GAME_SERVER_API_KEY")
  );

  @PostMapping("/bet")
  public JsonObject bet(@RequestBody BetRequest request) {
    return wallet.debit(
        WalletOperationRequest.builder()
            .sessionToken(request.getSessionToken())
            .amount(request.getAmount())
            .transactionId(request.getTransactionId())
            .roundId(request.getRoundId())
            .build()
    );
  }
}
```

---

## API

| Method | Description |
|--------|-------------|
| `getBalance(String sessionToken)` | Fetch balance → `JsonObject` |
| `getPlayerProfile(String sessionToken)` | Fetch profile → `JsonObject` |
| `debit(WalletOperationRequest request)` | Debit wallet → `JsonObject` |
| `credit(WalletOperationRequest request)` | Credit wallet → `JsonObject` |

### WalletOperationRequest.builder()

| Field | Required | Description |
|-------|----------|-------------|
| `sessionToken` | Yes | Player session token |
| `amount` | Yes | Amount to debit/credit |
| `transactionId` | Yes | Unique id per operation |
| `roundId` | No | Round reference |
| `tableId` | No | Table reference |
| `extra(key, value)` | No | Extra fields sent to Provider |

---

## REST endpoints used

| Action | Method | Path |
|--------|--------|------|
| Get balance | POST | `/adapters/balance` |
| Debit | POST | `/adapters/debit` |
| Credit | POST | `/adapters/credit` |
| Player profile | POST | `/adapters/player-profile` |

Headers sent by SDK:

```http
Content-Type: application/json
X-Game-Server-Key: your-game-server-secret
Authorization: Bearer {sessionToken}
```

---

## Environment

```env
PROVIDER_API_URL=https://api.dpbossking.com/api/v1
GAME_SERVER_API_KEY=your-long-random-secret
```

Or pass both values directly to the constructor:

```java
new ProviderGameServerSDK("https://api.dpbossking.com/api/v1", "your-secret");
```

`GAME_SERVER_API_KEY` must match Operator Adapter (`opa.dpbossking.com`).

---

## Requirements

- Java 11+
- Maven 3.6+ (for build)
- Gson (included as dependency)

---

## Errors

Throws `ProviderSDKException`:

| Method | Description |
|--------|-------------|
| `getMessage()` | Error description |
| `getStatus()` | HTTP status (if available) |
| `getData()` | Raw API response |

| Error | Fix |
|-------|-----|
| gameServerKey is required | Set `GAME_SERVER_API_KEY` |
| 401 on wallet | Wrong or missing server key |
| 404 integration | Operator integration not on OPA |
| Network error | Check API URL and connectivity |

---

## Publish (maintainers)

**Namespace:** `in.oreng` (verified via `oreng.in` DNS on [central.sonatype.com](https://central.sonatype.com/))

1. Sonatype token in `~/.m2/settings.xml` — run `./setup-maven-settings.sh`
2. GPG public key on `keys.openpgp.org` (verify email after upload)
3. Mac signing setup:

```bash
brew install pinentry-mac
./setup-gpg-mac.sh
```

4. Deploy from **Terminal.app** (not Cursor — avoids GPG timeout):

```bash
export GPG_TTY=$(tty)
cd sdks/java
mvn clean deploy -Prelease
```

If `gpg: signing failed: Timeout`, use loopback mode in `~/.gnupg/gpg.conf`:

```
pinentry-mode loopback
```

Then add `gpg.passphrase` to the gpg profile in `settings.local.xml` (local only, never commit).

Appears on [search.maven.org](https://search.maven.org/) after ~15–30 minutes.
