# Real-Money Rummy Platform --- Australia

## Full-Stack Engineering Roadmap — MongoDB Wallet Edition

### React + TypeScript + Java Spring Boot + WebSocket + Redis + Kafka + MongoDB + MongoDB

**Document version:** 1.0\
**Target:** Production-grade real-time rummy platform\
**Target concurrency:** 300,000--400,000 concurrent players\
**Primary frontend:** React + TypeScript\
**Primary backend:** Java 21 + Spring Boot\
**Real-time transport:** Secure WebSocket (WSS)\
**Application database:** MongoDB\
**Financial ledger:** MongoDB (dedicated wallet + immutable transaction collections)\
**Cache/coordination:** Redis Cluster\
**Event streaming:** Kafka\
**Deployment:** Docker + Kubernetes/ECS\
**Cloud:** AWS or equivalent

------------------------------------------------------------------------


This document is an engineering plan. It is **not legal advice** and
does not establish that a real-money online rummy product is legal to
launch in Australia.

Australia's Interactive Gambling Act 2001 (IGA) restricts/prohibits
certain online gambling services. ACMA currently states that prohibited
online services include online casinos and certain other services, and
ACMA maintains a register of licensed interactive gambling providers.
ACMA is actively blocking illegal online gambling websites. The Federal
Court also imposed substantial penalties in 2026 in proceedings
involving prohibited online poker services.

Therefore:

> **Do not enable Australian real-money deposits, wagering, or
> withdrawals in production until the exact product has been reviewed by
> an Australian gambling lawyer/compliance specialist and the required
> licence/classification/approvals have been confirmed in writing.**

The engineering plan below intentionally puts a **LEGAL/COMPLIANCE GATE
before real-money production**.

Official references: - ACMA --- About the Interactive Gambling Act:
https://www.acma.gov.au/interactivegambling - ACMA --- Check if a
gambling operator is legal:
https://www.acma.gov.au/check-if-gambling-operator-legal - Federal
Register of Legislation --- Interactive Gambling Act 2001:
https://www.legislation.gov.au/Latest/C2023C00408 - ACMA --- Federal
Court online poker penalties, 6 July 2026:
https://www.acma.gov.au/articles/2026-07/federal-court-sets-24-million-penalties-illegal-online-gambling-services

**Engineering rule:** legal approval is a release dependency, not a
document to complete after development.

------------------------------------------------------------------------



# MASTER RUMMY VARIANT CATALOGUE — 10 SEPARATE GAME VARIANTS

## Purpose

This section defines the **10 game variants** that the platform will support as separate rulesets.

Important distinction:

- **Points / Pool / Deals / 21 Card** are Indian online-Rummy formats.
- **Indian Rummy / 13 Card Rummy** is the underlying Indian-style game family.
- **Basic/Classic Rummy, Gin Rummy, 500 Rummy, Kalooki, and Canasta** are different Rummy-family games and must NOT be forced through the Indian 13-card declaration logic.
- Some of these games have many published regional/house-rule versions. Where a rule is not universal, the implementation below chooses a named **platform ruleset** instead of pretending there is one globally correct rule.

The 10 platform rulesets are:

```text
01. Indian 13-Card Rummy
02. Points Rummy
03. Pool 101
04. Pool 201
05. Deals Rummy
06. 21-Card Rummy
07. Gin Rummy
08. 500 Rummy
09. Kalooki
10. Canasta
```

---

# VARIANT 01 — INDIAN 13-CARD RUMMY

## 1.1 Identity

```text
Ruleset ID: INDIAN_13
Family: Indian Rummy
Players: 2–6
Cards per player: 13
Standard deck model: 2 packs + printed jokers
Primary objective: Arrange all cards into valid sequences/sets and declare
```

Indian Rummy commonly uses 13 cards per player and requires a valid arrangement containing sequences and sets. RummyCircle's published 13-card rules require two sequences including at least one pure sequence. citeturn0search5turn0search3

## 1.2 Card groups

### Pure Sequence

Three or more consecutive cards of the same suit without joker substitution.

```text
4H 5H 6H
```

### Impure Sequence

Three or more cards forming a sequence with joker substitution.

```text
4H 5H Joker 7H
```

### Set

Three or four cards of the same rank, normally different suits.

```text
7H 7D 7S
```

A joker can substitute for a missing card in an impure set.

## 1.3 Winning declaration

Platform rule:

```text
13 cards
↓
minimum 2 sequences
↓
minimum 1 pure sequence
↓
every card belongs to a valid group
↓
DECLARE = valid
```

A client cannot declare the game valid by itself. The server validates the actual private hand.

## 1.4 Turn

```text
DRAW from closed OR top discard
↓
temporary 14 cards
↓
DISCARD one
↓
13 cards
↓
next player
```

## 1.5 Scoring

Default Indian-Rummy scoring:

```text
A/K/Q/J = 10
10 = 10
2–9 = face value
Jokers = 0
```

If a losing player has no pure sequence, the full hand can count; if the player has a pure sequence but not two sequences, the pure sequence is exempt and the remaining cards count. Maximum loss is commonly capped at 80 in the published RummyCircle rules. citeturn0search3turn0search5

## 1.6 Drop

Platform base configuration:

```text
First Drop  = 20
Middle Drop = 40
Auto Drop   = 40
Wrong Declaration = 80
Maximum Loss = 80
```

These are configurable rather than hard-coded. RummyCircle publishes the same 20/40/80 configuration for its 13-card rules. citeturn0search5

---

# VARIANT 02 — POINTS RUMMY

## 2.1 Identity

```text
Ruleset ID: POINTS_13
Players: 2–6
Cards/player: 13
Deals: 1
Scoring: Points × configured point value
```

RummyCircle describes Points Rummy as a 13-card Indian Rummy variant where the first valid declarer wins and the losers' points determine the amount. citeturn0search3

## 2.2 Game lifecycle

```text
Table created
↓
Players join
↓
Entry/eligibility checks
↓
Deal 13 cards
↓
Turns
↓
Valid declaration
↓
Score losers
↓
Calculate game result
↓
Settlement
↓
Game complete
```

## 2.3 Winner

The valid declarer receives:

```text
0 penalty points
```

## 2.4 Loser scoring

```text
A/K/Q/J/10 = 10
2–9 = face value
Joker = 0
```

Only cards not properly grouped are normally counted, subject to the pure-sequence/two-sequence rules.

Maximum common penalty:

```text
80
```

## 2.5 Example

```text
P1 declares valid
P2 = 20
P3 = 35
P4 = 10

Total = 65 points
Point value = AUD 1

Gross prize basis = AUD 65
```

The actual real-money fee/settlement model must be separately configured and legally approved.

## 2.6 Timeout

```text
3 consecutive missed turns
↓
automatic drop
↓
middle-drop penalty
```

RummyCircle documents this behavior for Points Rummy. citeturn0search3

---

# VARIANT 03 — POOL 101 RUMMY

## 3.1 Identity

```text
Ruleset ID: POOL_101_13
Players: 2–6
Cards/player: 13
Structure: Multiple deals
Elimination threshold: 101
```

Pool Rummy uses cumulative points; a player reaching the table's maximum threshold is eliminated. RummyCircle documents 101 and 201 as Pool formats. citeturn0search6

## 3.2 Objective

Unlike Points Rummy:

```text
LOW cumulative score = better
```

The winner of an individual deal normally receives:

```text
0 points
```

Other players' scores are added to their cumulative totals.

## 3.3 Lifecycle

```text
Deal
↓
Valid declaration
↓
Calculate each player's deal score
↓
Add to cumulative score
↓
Eliminate players >= 101
↓
If more than one remains:
    next deal
↓
Last remaining player = Pool winner
```

## 3.4 Drop configuration

Platform default:

```text
First Drop  = 20
Middle Drop = 40
```

## 3.5 Example

```text
P1 = 65 cumulative
P2 = 95 cumulative
P3 = 101 cumulative
P4 = 30 cumulative

P3 is eliminated.

Next deal:
P1 = 70
P2 = 110 → eliminated
P4 = 50

P1 vs P4 continue.
```

## 3.6 Edge cases

Must explicitly define:

```text
score == 101 → eliminated
score > 101  → eliminated
multiple players cross threshold → all qualifying players eliminated
one player remains → winner
```

---

# VARIANT 04 — POOL 201 RUMMY

## 4.1 Identity

```text
Ruleset ID: POOL_201_13
Players: 2–6
Cards/player: 13
Structure: Multiple deals
Elimination threshold: 201
```

## 4.2 Difference from Pool 101

Core game is the same.

Only the threshold and selected penalty configuration differ.

Platform default:

```text
Threshold = 201
First Drop = 25
Middle Drop = 50
```

These values should remain configuration-driven because published implementations can differ.

## 4.3 Lifecycle

```text
Deal
↓
Declaration
↓
Score
↓
Cumulative update
↓
Threshold check
↓
Eliminate qualifying players
↓
Continue until one remains
```

## 4.4 Example

```text
P1 = 190
P2 = 150
P3 = 205

P3 eliminated.

P1 plays another deal:
+15

P1 = 205 → eliminated.

P2 is now the last remaining player → winner.
```

---

# VARIANT 05 — DEALS RUMMY

## 5.1 Identity

```text
Ruleset ID: DEALS_13
Players: 2–6
Cards/player: 13
Structure: Fixed number of deals
Currency inside game: Chips
Winner: Highest final chip balance
```

RummyCircle's published Deals format ranks players after a pre-decided number of deals according to chip count; tied chip counts can be resolved with a tie-breaker game. citeturn0search9

## 5.2 Starting state

Example platform configuration:

```text
Deals = 6
Starting chips = 480
```

These are table configuration values, not universal Rummy laws.

## 5.3 Deal flow

```text
Allocate chips
↓
Deal #1
↓
Play
↓
Calculate result
↓
Transfer chips
↓
Deal #2
↓
...
↓
Final deal
↓
Final chip balances
↓
Rank players
```

## 5.4 Scoring

Common Indian scoring:

```text
J/Q/K/A = 10
2–10 = face value
Joker = 0
```

The hand score is based on ungrouped cards, with the standard pure/two-sequence rules and an 80-point cap in RummyCircle's published Deals rules. citeturn0search9

## 5.5 Deal Show

RummyCircle documents a special "Deal Show" case where a player who has not taken their first turn when another player declares receives half the normal hand score, subject to minimum and maximum limits. citeturn0search9

Platform implementation:

```text
if playerHasNotTakenFirstTurn
    apply dealShowRule
else
    normalScore
```

## 5.6 Final ranking

```text
Highest chips = Rank 1
Second highest = Rank 2
...
```

Tie:

```text
same chips
↓
tie-breaker ruleset
```

---

# VARIANT 06 — 21-CARD RUMMY

## 6.1 Identity

```text
Ruleset ID: INDIAN_21
Players: 2–6
Cards/player: 21
Decks: 3
Printed jokers: 1 per deck
```

RummyCircle's published 21-card rules describe 2–6 players, three 53-card packs, and a cut joker plus upper/lower jokers. Its current help page also notes that 21 Card Rummy has been discontinued there, so this section is a **historical/reference ruleset**, not a claim that it is currently offered by RummyCircle. citeturn0search1turn0search8

## 6.2 Cut Joker

A joker is selected at the beginning.

Example:

```text
Cut Joker = Q♥
```

Then:

```text
Upper Joker = K♥
Lower Joker = J♥
```

For:

```text
Cut Joker = 7♠
```

then:

```text
Lower = 6♠
Upper = 8♠
```

RummyCircle documents this upper/lower-joker model. citeturn0search1

## 6.3 Ace edge case

If:

```text
Cut Joker = A♥
```

then:

```text
Lower Joker = K♥
Upper Joker = 2♥
```

according to the published 21-card rules. citeturn0search1

## 6.4 Marriage / Jackpot

A documented special combination is:

```text
Upper Joker
+
Lower Joker
+
same-suit Cut Joker
=
Marriage / Jackpot
```

RummyCircle's historical 21-card rules award 100 points per non-dropped opponent for one marriage, with higher bonuses for multiple marriages. citeturn0search1

## 6.5 Value-card scoring

Historical RummyCircle configuration:

```text
1 value joker = 10
2 value jokers = 30
3 value jokers = 50
```

A marriage:

```text
100 points
```

Two marriages:

```text
300 points
```

Three marriages:

```text
500 points
```

The published rules cap card-loss at 120 and total loss including value-card scoring at 200. citeturn0search1

## 6.6 Engineering warning

21-card Rummy MUST NOT reuse the 13-card score calculator.

Create:

```text
TwentyOneCardRules
TwentyOneDeclarationValidator
TwentyOneScoreCalculator
MarriageDetector
UpperLowerJokerDetector
```

---

# VARIANT 07 — GIN RUMMY

## 7.1 Identity

```text
Ruleset ID: GIN_RUMMY
Players: 2
Cards/player: 10
Decks: 1 standard 52-card deck
Jokers: none in base rules
Objective: form melds and minimize deadwood
```

Gin Rummy is a distinct two-player Rummy game. A standard rules description uses one 52-card deck and 10 cards per player. citeturn0search0

## 7.2 Card values

```text
A = 1
2–10 = face value
J/Q/K = 10
```

Ace is low.

Therefore:

```text
A-2-3 = valid
Q-K-A = invalid
```

citeturn0search0

## 7.3 Melds

### Set

```text
7H 7D 7S
```

### Run

```text
4H 5H 6H
```

A card cannot simultaneously belong to two melds. citeturn0search0

## 7.4 Deal

```text
10 cards each
21st card face-up
remaining cards = stock
```

The face-up card begins the discard pile. citeturn0search0

## 7.5 Turn

```text
Draw one:
    stock
    OR top discard
↓
evaluate hand
↓
discard one
```

citeturn0search0

## 7.6 Knock

A player can end a hand by knocking when deadwood is sufficiently low according to the ruleset.

Platform base:

```text
Knock threshold = 10 deadwood points
```

This threshold should be configurable because Gin has recognized variants.

## 7.7 Gin

Gin means:

```text
all 10 cards are melded
deadwood = 0
```

The standard scoring described by Pagat gives a 20-point gin bonus plus opponent deadwood. citeturn0search0

## 7.8 Undercut

If the knocker does not have lower deadwood than the opponent:

```text
opponent wins the hand
+
undercut bonus
```

A commonly documented base rule uses a 10-point undercut bonus. citeturn0search0

## 7.9 Game target

Base rules:

```text
100 cumulative points
```

The standard rules also include game/box bonuses. citeturn0search0

## 7.10 Gin variants

The engine should support:

```text
Standard Gin
Oklahoma Gin
Hollywood Gin
Custom Gin
```

For Oklahoma Gin, the initial face-up card can determine the maximum deadwood with which a player may knock; a spade up-card can double the hand score, and the target is commonly 150. citeturn0search0

---

# VARIANT 08 — 500 RUMMY

## 8.1 Identity

```text
Ruleset ID: RUMMY_500
Players: 2–8
Deck: 52 cards + jokers
Objective: reach 500 cumulative points
```

Published rules commonly use 2–8 players, with one deck for smaller games and two decks for larger tables. citeturn1search1

## 8.2 Cards

Common base configuration:

```text
2–4 players → 1 deck
5+ players  → 2 decks
```

A standard version may use two jokers per deck. citeturn1search1

## 8.3 Deal

One common ruleset:

```text
2 players → 13 cards each
3+ players → 7 cards each
```

The remaining cards form the stock and one card starts the discard pile. citeturn1search1

## 8.4 Melds

### Group

```text
7H 7D 7S
```

### Sequence

```text
4H 5H 6H
```

Melds stay on the table.

## 8.5 Unique discard rule

This is a major difference from Indian Rummy.

A player can take more than just the top discard.

To take a deeper card:

```text
select target card
+
take every discard above it
+
immediately meld the target card
```

This is one of the defining features of 500 Rummy. citeturn1search1

## 8.6 Turn

```text
Draw stock
OR
take qualifying discard pile cards
↓
Meld
↓
Lay off cards if allowed
↓
Discard
```

Players can lay off cards onto existing melds. citeturn1search1

## 8.7 Scoring

Base published scoring:

```text
melded card points
-
cards remaining in hand
=
hand score
```

The cumulative score continues across hands.

## 8.8 Target

```text
500 points
```

First player to reach/exceed the target wins under the base rules; ties can require additional hands. citeturn1search1

## 8.9 Common card values

One published ruleset:

```text
2–10 = face value
J/Q/K = 10
A = 15
Joker = 15
```

An Ace used as A-2-3 may score 1 in the meld. citeturn1search1

## 8.10 Custom options

Engine configuration should support:

```text
with/without jokers
card-value model
discard-pile pickup restrictions
calling "Rummy"
must-discard-to-go-out
must-go-out-to-win
target score
```

These are documented variations of 500 Rummy. citeturn1search1

---

# VARIANT 09 — KALOOKI / KALUKI

## 9.1 Identity

Kalooki is not one globally standardized ruleset. Different regions use substantially different contracts, deck counts, joker values, and calling rules.

Therefore the platform must define an explicit ruleset version.

```text
Ruleset ID: KALOOKI
Recommended platform implementation: Caribbean Kalooki-style contract Rummy
```

A documented Caribbean Kalooki version uses contracts across seven rounds, with players accumulating penalty points and the lowest total winning. citeturn1search7

## 9.2 Platform base configuration

```text
Players: 2–5
Rounds: 7
Objective: complete the contract for each round
Winner: lowest cumulative penalty score
```

## 9.3 Contract model

Each round has a required meld structure.

Example conceptual structure:

```text
Round 1 → required combination A
Round 2 → required combination B
Round 3 → required combination C
...
Round 7 → final contract
```

The exact contract table must be stored as data:

```json
{
  "round": 1,
  "requiredMelds": [
    {
      "type": "SET",
      "count": 2,
      "cards": 3
    }
  ]
}
```

Do not hard-code contracts into UI.

## 9.4 Joker replacement

A documented Caribbean Kalooki rule allows a player to replace a joker in another player's run with the natural card it represents, but the joker must be moved to an end of that same run rather than moved to another meld. citeturn1search7

This is fundamentally different from Indian Rummy and therefore needs its own validator.

## 9.5 Calls

The documented Caribbean rules include a limited number of calls per round to take the top discard out of turn, with an additional card from the face-down deck. citeturn1search7

Platform configuration:

```text
callsPerRound = 3
```

The server must track:

```text
callsUsed
callsRemaining
```

## 9.6 Challenge

The documented rules allow a challenge when another player appears to exceed the allowed hand size from calls. A wrong challenge can itself cause a penalty. citeturn1search7

This requires:

```text
ChallengeCommand
ChallengeValidator
ChallengePenalty
```

## 9.7 Scoring

A documented Caribbean version scores remaining cards:

```text
Joker = 50
J/Q/K = 10
Black Ace = 15
Red Ace = 1
2–10 = face value
```

The lowest cumulative score after the seven rounds wins. citeturn1search7

## 9.8 Engineering requirement

Because Kalooki is highly variant-sensitive:

```text
KalookiRules
KalookiContractValidator
KalookiCallManager
KalookiChallengeManager
KalookiScoreCalculator
```

must be separate modules.

---

# VARIANT 10 — CANASTA

## 10.1 Identity

Canasta is a Rummy-family game centered on melds and seven-card canastas.

The platform should use **Classic Canasta** as the base implementation and treat American/New Canasta as separate ruleset versions.

```text
Ruleset ID: CANASTA_CLASSIC
Players: 4 preferred
Teams: 2 teams of 2
Cards: 2 standard 52-card decks + 4 jokers
```

Published Classic Canasta rules use partnerships and melds; the exact deck/team setup can vary by edition. citeturn1search3

## 10.2 Teams

```text
Team A:
Player A1
Player A2

Team B:
Player B1
Player B2
```

Partners sit opposite in traditional four-player play.

## 10.3 Melds

A meld contains:

```text
minimum 3 cards
same rank
```

Example:

```text
8H 8D 8S
```

A meld can contain wild cards.

Classic Canasta requires at least two natural cards in a meld, and a meld cannot contain more than three wild cards. citeturn1search3

## 10.4 Canasta

A meld containing:

```text
7+ cards of the same rank
```

is a Canasta.

### Natural / Clean Canasta

No wild cards.

### Mixed / Dirty Canasta

Contains wild cards.

A published Classic Canasta scoring model gives:

```text
Natural Canasta = 500
Mixed Canasta   = 300
```

citeturn1search3

## 10.5 Wild cards

Common wild cards:

```text
2s
Jokers
```

Wild-card rules are heavily restricted compared with Indian Rummy.

## 10.6 Red Threes

Red threes have special treatment.

They are normally placed face-up and replaced from the stock.

They affect scoring rather than functioning as ordinary meld cards. citeturn1search3

## 10.7 Black Threes

Black threes have special discard restrictions and generally cannot be melded normally.

They have strategic importance when controlling the discard pile. citeturn1search3

## 10.8 Taking the discard pile

Canasta has a major rule difference from Indian Rummy.

A player may take the discard pile only when the top discard can be legally used in a qualifying meld.

A documented Classic Canasta rule requires the player to show that the top card can be used before taking the pile. citeturn1search3

## 10.9 Going out

A player/team ends the round by legally getting rid of all cards.

Classic Canasta has additional conditions and bonuses for going out, including a concealed-out bonus. citeturn1search3

## 10.10 Scoring

Published Classic Canasta scoring includes:

```text
Going out = 100
Concealed going out = additional 100
Natural Canasta = 500
Mixed Canasta = 300
Red-three bonuses subject to conditions
Melded card values
minus cards remaining in hand
```

citeturn1search3

## 10.11 Game target

Classic Canasta commonly uses a cumulative target score; the exact target is a ruleset configuration because published Canasta families use different targets.

Platform example:

```text
targetScore = 5000
```

The production implementation must store the selected ruleset/version rather than assume one universal target.

---

# CROSS-VARIANT RULE ENGINE DESIGN

## A. Do NOT use one validator for all 10 games

Wrong:

```text
RummyValidator
    ↓
all 10 games
```

Correct:

```text
RummyGameEngine
│
├── Indian13Engine
│
├── Points13Engine
│
├── Pool101Engine
│
├── Pool201Engine
│
├── Deals13Engine
│
├── TwentyOneEngine
│
├── GinEngine
│
├── Rummy500Engine
│
├── KalookiEngine
│
└── CanastaEngine
```

## B. Shared infrastructure

The following can be shared:

```text
Card
Suit
Rank
Deck
CardInstance
Player
Table
GameCommand
GameEvent
TurnTimer
Randomness service
Event sequencing
Reconnect
Audit
Persistence
Observability
```

## C. Variant-specific logic

Must remain separate:

```text
DeclarationValidator
MeldValidator
Scoring
Drop rules
Knock rules
Contract rules
Canasta rules
Discard-pile pickup
Settlement calculation
Game-end condition
```

---

# VARIANT COMPARISON

| # | Variant | Players | Cards/player | Main objective | Main mechanic |
|---|---|---:|---:|---|---|
| 1 | Indian 13-Card | 2–6 | 13 | Valid declaration | 2 sequences + 1 pure |
| 2 | Points | 2–6 | 13 | Win one deal | Point-based scoring |
| 3 | Pool 101 | 2–6 | 13 | Survive | Eliminate at 101 |
| 4 | Pool 201 | 2–6 | 13 | Survive | Eliminate at 201 |
| 5 | Deals | 2–6 | 13 | Highest final chips | Fixed deals |
| 6 | 21-Card | 2–6 | 21 | Valid declaration | Upper/lower jokers |
| 7 | Gin | 2 | 10 | Minimize deadwood | Knock/Gin |
| 8 | 500 Rummy | 2–8 | 7/13* | Reach target | Meld scoring + deep discard pickup |
| 9 | Kalooki | 2–5* | Ruleset-specific | Complete contracts | Contract/call system |
| 10 | Canasta | 4 typical | Ruleset-specific | Build canastas | 7-card melds |

`*` Player/card counts vary by published version. The platform must select and freeze a specific ruleset version.

---

# VARIANT-SPECIFIC GAME STATE

Each game state must identify its ruleset:

```json
{
  "gameId": "G123",
  "variant": "GIN_RUMMY",
  "rulesetVersion": "1.0.0",
  "players": [],
  "turn": {},
  "deck": {},
  "discardPile": {},
  "score": {}
}
```

Do not infer rules from table name.

---

# RULESET VERSIONING

Every production game stores:

```text
variantId
rulesetVersion
engineVersion
```

Example:

```text
variantId      = POOL_101
rulesetVersion = 1.0.0
engineVersion  = 2.3.1
```

If the rules change:

```text
POOL_101 v1.0.0
POOL_101 v1.1.0
```

old games remain evaluated using their original ruleset.

---

# CUSTOM RULE MODIFIER LAYER

The platform can later add house-rule modifiers without pretending they are separate historical Rummy games.

Examples:

```text
JOKER_MODE
DROP_MODE
TURN_TIMER
DEAL_COUNT
TARGET_SCORE
MAX_PENALTY
KNOCK_THRESHOLD
GIN_BONUS
UNDERCUT_BONUS
CANASTA_TARGET
KALOOKI_CONTRACT_SET
```

Example:

```json
{
  "variant": "GIN_RUMMY",
  "rulesetVersion": "1.0.0",
  "modifiers": {
    "knockThreshold": 10,
    "ginBonus": 20,
    "undercutBonus": 10,
    "targetScore": 100
  }
}
```

This gives the platform the equivalent of **variant/mode selection** without corrupting the core game model.

---

# IMPORTANT: NO FALSE "UNIVERSAL RULE" CLAIMS

For:

```text
Gin
500 Rummy
Kalooki
Canasta
Basic Rummy
21-card Rummy
```

there are multiple published variants.

Therefore the codebase must never contain comments such as:

```text
"This is the universal Gin rule."
```

Instead:

```text
"Platform Gin Rules v1.0"
```

is the authoritative production specification.

For Indian 13-card formats, use the chosen product ruleset and version, not a mixture of rules copied from different operators.

---

# IMPLEMENTATION CHECKLIST FOR ALL 10

For every variant, create:

```text
[ ] Rules class
[ ] Rules configuration
[ ] Card/deck configuration
[ ] Deal algorithm
[ ] Turn rules
[ ] Draw rules
[ ] Discard rules
[ ] Meld/sequence validator
[ ] Declaration/finish validator
[ ] Scoring calculator
[ ] Game-end detector
[ ] Timeout rules
[ ] Disconnect rules
[ ] Reconnect rules
[ ] Event model
[ ] Settlement adapter
[ ] Unit tests
[ ] Property tests
[ ] Replay tests
[ ] Fuzz tests
```

For real-money variants additionally:

```text
[ ] Entry authorization
[ ] Wallet hold
[ ] Settlement
[ ] Idempotency
[ ] Audit
[ ] Fraud checks
[ ] Dispute replay
[ ] KYC/eligibility gate
[ ] Compliance approval
```

---

# AUTHORITATIVE REFERENCES USED FOR THIS CATALOGUE

The exact platform rules should be frozen into versioned internal specifications before coding.

Reference material:

- RummyCircle 13-card / Points Rummy rules — player counts, deck model, sequences, scoring, drops and maximum points. citeturn0search3turn0search5
- RummyCircle Pool Rummy rules — 101/201 cumulative elimination model. citeturn0search6
- RummyCircle Deals Rummy rules — fixed deals, chip ranking and deal-show scoring. citeturn0search9
- RummyCircle 21-card rules — three-pack format, upper/lower jokers and marriage/value-card scoring. citeturn0search1
- Pagat Gin Rummy rules — 2-player, 10-card, knock/gin/undercut and scoring model. citeturn0search0
- Pagat 500 Rummy rules — discard-pile pickup, meld scoring, 500-point target and variants. citeturn1search1
- Pagat Caribbean Kalooki rules — contract/call/challenge model and scoring example. citeturn1search7
- Pagat Canasta rules — melds, canastas, red/black threes, discard-pile rules and scoring. citeturn1search3

These references are for rules research. They do not establish Australian legal permission for real-money operation.


# COMPLETE RUMMY RULEBOOK — VARIANTS, RULES & ENGINE SPECIFICATION

> **Purpose:** This section is the authoritative product/game-engine rules specification for the implementation roadmap. It describes the rules that the platform should support as configurable rulesets. It is intentionally separated from payment, wallet, compliance, and UI logic.
>
> **Important:** Do not assume that every rule below is legally permitted for an Australian real-money product. Real-money launch requires legal/compliance review of the exact game format, player eligibility, wagering model, bot usage, promotions, payments, and state/territory requirements.

---

## R0. RULESET ARCHITECTURE

The game engine must NOT contain one giant `if/else` implementation for every variant.

Use:

```text
GameEngine
   |
   +-- RummyRules
   |      |
   |      +-- PointsRummyRules
   |      +-- Pool101Rules
   |      +-- Pool201Rules
   |      +-- DealsRummyRules
   |      +-- TwentyOneCardRules
   |
   +-- Validation
   +-- Scoring
   +-- TurnManager
   +-- DeclarationValidator
   +-- DropManager
   +-- JokerManager
   +-- SettlementCalculator
```

Every ruleset must expose a machine-readable configuration:

```json
{
  "rulesetId": "POINTS_13",
  "rulesetVersion": "1.0.0",
  "cardsPerPlayer": 13,
  "minPlayers": 2,
  "maxPlayers": 6,
  "decks": 2,
  "printedJokers": 2,
  "requiresPureSequence": true,
  "minimumSequences": 2,
  "maximumPenalty": 80
}
```

Never rely on UI labels to determine rules. The server owns the ruleset.

---

# R1. COMMON 13-CARD RUMMY RULES

These rules form the base ruleset shared by Points, Pool, and Deals formats unless a variant explicitly overrides them.

## R1.1 Players

Supported table size:

```text
Minimum players: 2
Maximum players: 6
```

The implementation should support configurable table capacity so the platform can later support other formats without rewriting the table service.

## R1.2 Cards

Standard 13-card Indian Rummy configuration:

```text
2 standard 52-card decks
+ printed jokers
= 106 cards
```

Each player receives:

```text
13 cards
```

The exact deck/joker configuration must be stored in the ruleset rather than hard-coded.

## R1.3 Suits

Four standard suits:

```text
HEARTS
DIAMONDS
CLUBS
SPADES
```

## R1.4 Ranks

```text
ACE
2
3
4
5
6
7
8
9
10
JACK
QUEEN
KING
```

## R1.5 Card point values

```text
A  = 10
K  = 10
Q  = 10
J  = 10
10 = 10

9 = 9
8 = 8
7 = 7
6 = 6
5 = 5
4 = 4
3 = 3
2 = 2

Joker = 0
```

For scoring, face cards and Ace are normally capped at 10.

## R1.6 Objective

The player must arrange all 13 cards into valid groups.

The standard winning declaration requires:

```text
At least 2 sequences
AND
at least 1 pure sequence
AND
all cards must belong to valid groups
```

A common valid arrangement:

```text
Pure Sequence:
4♥ 5♥ 6♥ 7♥

Impure Sequence:
9♣ 10♣ Joker Q♣

Set:
K♠ K♥ K♦

Set:
3♠ 3♥ 3♦
```

Total:

```text
4 + 4 + 3 + 3 = 14 cards
```

This is only an illustrative grouping. A real 13-card hand must contain exactly 13 cards at declaration.

---

# R2. SEQUENCES

## R2.1 Pure Sequence

A pure sequence contains:

```text
3 or more consecutive cards
same suit
no joker substitution
```

Examples:

```text
4♥ 5♥ 6♥
```

```text
10♠ J♠ Q♠ K♠
```

```text
A♦ 2♦ 3♦
```

The exact Ace-low/Ace-high handling must be explicit in the ruleset. Do not leave it to implementation assumptions.

## R2.2 Impure Sequence

An impure sequence contains consecutive cards of the same suit with one or more joker substitutions.

Example:

```text
4♥ 5♥ Joker 7♥
```

where Joker represents `6♥`.

Another example:

```text
10♠ Joker Q♠
```

where Joker represents `J♠`.

## R2.3 Sequence validation algorithm

The engine should:

1. Verify group size >= 3.
2. Verify suits are compatible.
3. Identify natural cards.
4. Identify joker cards.
5. Sort natural cards by rank.
6. Verify consecutive gaps.
7. Verify joker count can fill all gaps.
8. Reject duplicate natural ranks where the ruleset does not permit them.
9. Classify as pure or impure.

Pseudo-rule:

```text
requiredJokers <= availableJokers
```

A sequence is valid only if every missing rank can be filled.

---

# R3. SETS

A set normally contains:

```text
3 or 4 cards
same rank
different suits
```

Examples:

```text
7♠ 7♥ 7♦
```

```text
K♠ K♥ K♦ K♣
```

Jokers may substitute missing cards in an impure set.

Example:

```text
7♠ 7♥ Joker
```

The same physical card should not appear twice in one group.

---

# R4. JOKER SYSTEM

The implementation must distinguish:

```text
Printed Joker
Wild Joker
Natural Card
```

## R4.1 Printed Joker

The physical joker card included in the deck.

It can normally substitute for another card in an impure sequence or set.

## R4.2 Wild Joker

At the beginning of the game, one eligible card rank is selected as the wild joker.

Example:

```text
Wild Joker = 8♥
```

Then cards of that rank may act as jokers according to the ruleset.

## R4.3 Joker in a pure sequence

A joker cannot normally be used as a substitute in a pure sequence.

Therefore:

```text
4♥ 5♥ 6♥
```

is pure.

But:

```text
4♥ 5♥ Joker
```

is not pure.

The engine must explicitly classify the group.

---

# R5. DEALING

## R5.1 Game creation

```text
CREATE TABLE
      ↓
SELECT RULESET
      ↓
SELECT PLAYER COUNT
      ↓
CREATE DECK
      ↓
SHUFFLE
      ↓
SELECT JOKER
      ↓
DEAL CARDS
      ↓
CREATE CLOSED DECK
      ↓
CREATE OPEN DISCARD PILE
      ↓
START TURN
```

## R5.2 Server authority

The client must never decide:

- shuffle result
- joker
- dealt cards
- next player
- legal draw
- legal discard
- declaration validity
- score
- winner
- settlement amount

All of these are server-authoritative.

---

# R6. TURN RULES

Normal turn:

```text
Player Turn
   ↓
Draw ONE card
   |
   +-- Closed Deck
   |
   +-- Open Discard Pile
   ↓
Arrange cards
   ↓
Discard ONE card
   ↓
Next Player
```

The player must not finish a normal turn with more than 13 cards.

Temporary state:

```text
Before draw = 13
After draw  = 14
After discard = 13
```

A declaration can be triggered according to the ruleset after the player has the required hand state.

---

# R7. DISCARD RULES

A player normally must discard exactly one card after drawing.

The discarded card is added to the open pile.

The server must reject:

```text
discard card not owned by player
discard after wrong turn
discard duplicate event
discard after game finished
discard malformed card ID
```

The physical card instance ID, not only rank/suit, should be tracked internally.

---

# R8. DRAW RULES

A player may draw from:

```text
Closed Deck
OR
Top card of Open Discard Pile
```

The engine must reject:

```text
draw twice in same turn
draw after timeout
draw from empty source without reshuffle policy
draw when not player's turn
```

If the closed deck becomes empty, the ruleset must define how the discard pile is recycled.

A common policy is:

```text
Keep top discard card
Shuffle remaining discard cards
Create new closed deck
```

This must be deterministic in the engine implementation and covered by tests.

---

# R9. FIRST-DROP RULE

A first drop means the player leaves without playing a completed turn.

For Points Rummy, the common penalty configuration is:

```text
First Drop = 20 points
```

Pool variants may use variant-specific drop penalties.

The exact value must come from `RummyRules`.

---

# R10. MIDDLE-DROP RULE

After the player has participated in the game, leaving/dropping before declaration produces a middle-drop penalty.

Common 13-card configurations:

```text
Points Rummy = 40 points
Pool 101     = 40 points
Pool 201     = 50 points
```

Do not hard-code these values globally.

---

# R11. MISSED-TURN / AUTO-DROP

If a player repeatedly fails to act within the turn timer, the server can automatically process a timeout.

A common rule:

```text
3 consecutive missed turns
      ↓
Automatic drop
```

For Points Rummy this can result in the applicable middle-drop penalty.

The timer implementation must not create one operating-system thread per table/player.

Store:

```text
turnStartedAt
turnDeadline
timeoutCount
```

and use a scalable scheduler.

---

# R12. DECLARATION

Declaration is the most important validation operation.

When a player declares:

```text
Client → DECLARE command
        ↓
Authentication
        ↓
Table ownership check
        ↓
Turn/state validation
        ↓
Server reads player's private hand
        ↓
Group cards
        ↓
Validate sequences
        ↓
Validate sets
        ↓
Check minimum sequence count
        ↓
Check pure sequence
        ↓
Check all 13 cards grouped
        ↓
VALID / INVALID
```

Never trust a client-provided declaration result.

---

# R13. INVALID DECLARATION

If a player declares incorrectly:

```text
Invalid Declaration
       ↓
Game continues or penalty applied
       ↓
Player becomes inactive according to ruleset
```

The exact invalid-declaration penalty must be configurable.

The engine must record:

```text
declarationAttemptId
playerId
gameId
handHash
groupsSubmitted
validationErrors
rulesetVersion
timestamp
```

This is important for dispute handling.

---

# R14. SCORING

For an unfinished hand, penalty points are calculated from cards that cannot be placed into valid groups.

Example:

```text
Unmatched:
K♠ = 10
9♥ = 9
4♣ = 4

Penalty = 23
```

Jokers contribute:

```text
0
```

Maximum penalty for the common Points Rummy configuration:

```text
80 points
```

The maximum must be a ruleset property.

---

# R15. POINTS RUMMY

## R15.1 Format

```text
Players: 2–6
Cards/player: 13
Decks: 2
Game length: One deal
Objective: Finish first with valid declaration
```

## R15.2 Flow

```text
Join
 ↓
Deal
 ↓
Player turns
 ↓
One player declares
 ↓
Validate declaration
 ↓
Calculate losers' points
 ↓
Calculate winnings
 ↓
Settlement
```

## R15.3 Winning player

The valid declaring player receives:

```text
0 penalty points
```

Other players receive their calculated penalty points.

## R15.4 Point value

The table has a configured point value.

Example:

```text
Point Value = AUD 1
```

If opponents have:

```text
10 + 20 + 30
```

then gross game value is:

```text
60 × AUD 1
= AUD 60
```

The actual wallet settlement model, fees, limits, and legal availability are separate concerns.

## R15.5 Common drop values

```text
First Drop  = 20
Middle Drop = 40
Auto Drop   = 40
Maximum Loss = 80
```

These values must be configuration-driven.

---

# R16. POOL RUMMY

Pool Rummy is a multi-deal elimination format.

The player's cumulative score increases after each deal.

The objective is to survive while keeping the score below the elimination threshold.

---

# R17. POOL 101

## R17.1 Format

```text
Players: 2–6
Cards/player: 13
Decks: 2
Elimination threshold: 101 points
```

## R17.2 Flow

```text
Deal #1
 ↓
Winner determined
 ↓
Scores updated
 ↓
Check 101 threshold
 ↓
Eliminate players at/above threshold
 ↓
If >1 player remains → next deal
 ↓
Repeat
 ↓
Last surviving player = winner
```

## R17.3 Common drop values

```text
First Drop  = 20
Middle Drop = 40
```

## R17.4 Cumulative score

Example:

```text
Player A
Deal 1 = 25
Deal 2 = 30
Deal 3 = 20
Deal 4 = 30

Total = 105

A is eliminated.
```

The engine must perform threshold checks after every settlement.

---

# R18. POOL 201

## R18.1 Format

```text
Players: 2–6
Cards/player: 13
Decks: 2
Elimination threshold: 201 points
```

## R18.2 Flow

Same as Pool 101, except:

```text
Threshold = 201
```

## R18.3 Common drop values

```text
First Drop  = 25
Middle Drop = 50
```

The threshold and drop penalties must be configurable.

---

# R19. POOL ELIMINATION EDGE CASES

The engine must define behavior for:

### Exactly threshold

```text
score == 101
```

or:

```text
score == 201
```

means eliminated.

### Above threshold

```text
score > threshold
```

also means eliminated.

### Multiple players cross threshold

The ruleset must define whether:

```text
all qualifying players are eliminated
```

or another tie-resolution rule applies.

Do not leave this ambiguous.

### One player remains

```text
Last remaining player
      ↓
Pool winner
```

### Simultaneous declaration / server race

The authoritative game server must serialize commands so there is only one valid state transition order.

---

# R20. DEALS RUMMY

Deals Rummy is a fixed-deal format.

## R20.1 Format

```text
Players: 2–6
Cards/player: 13
Number of deals: Configurable
Starting chips: Configurable
```

A common example uses:

```text
6 deals
480 chips/player
```

The exact values should be table configuration.

## R20.2 Objective

Players accumulate chips across a fixed number of deals.

At the end:

```text
Highest chip balance
        ↓
Winner
```

## R20.3 Flow

```text
Allocate starting chips
       ↓
Deal #1
       ↓
Settlement
       ↓
Deal #2
       ↓
Settlement
       ↓
...
       ↓
Final deal
       ↓
Final chip balances
       ↓
Highest balance = winner
```

## R20.4 Important distinction

Deals Rummy is NOT the same as Pool Rummy.

Pool:

```text
Low cumulative penalty
→ elimination
```

Deals:

```text
Fixed number of deals
→ chip accumulation
→ final ranking
```

The engine should therefore use separate rules classes.

---

# R21. 21-CARD RUMMY

This must be implemented as a separate ruleset.

## R21.1 Format

Common configuration:

```text
Players: 2–6
Cards/player: 21
Decks: 3
```

## R21.2 Groups

The same broad concepts apply:

```text
Pure Sequence
Impure Sequence
Set
```

But the 21-card ruleset must define its own:

```text
minimum sequences
special groups
joker handling
scoring
declaration conditions
drop rules
```

Do NOT reuse the 13-card declaration validator blindly.

---

# R22. 21-CARD SPECIAL JOKER CONCEPTS

Some 21-card Rummy formats use additional joker concepts such as:

```text
Upper Joker
Lower Joker
Wild Joker
Printed Joker
```

The rules engine should represent joker behavior as a strategy/configuration rather than hard-code it.

Example:

```java
interface JokerRule {
    boolean isJoker(Card card, GameState state);
    boolean canSubstitute(Card card, Group group, GameState state);
}
```

---

# R23. TOURNAMENT MODE

Tournament is a meta-game layer, not a replacement for the underlying Rummy rules.

Architecture:

```text
Tournament
   |
   +-- Round
   |     |
   |     +-- Table
   |     +-- Table
   |
   +-- Round
   |
   +-- Final
```

A tournament can use:

```text
Points Rummy
Pool Rummy
Deals Rummy
```

depending on tournament configuration.

Tournament service controls:

```text
entry
seating
table balancing
rounds
elimination
qualification
prize calculation
leaderboard
final ranking
```

GameEngine controls only the actual game.

---

# R24. TABLE STATE MACHINE

Each active table must behave like an actor/state machine.

```text
WAITING
   ↓
DEALING
   ↓
IN_PROGRESS
   ↓
DECLARATION
   ↓
SETTLEMENT
   ↓
COMPLETED
```

Pool/Deals:

```text
SETTLEMENT
   ↓
CHECK_NEXT_DEAL
   |
   +-- YES → DEALING
   |
   +-- NO  → COMPLETED
```

Error/administrative states:

```text
PAUSED
CANCELLED
ABORTED
```

---

# R25. PLAYER STATE

Each player needs at least:

```java
PlayerState {
    playerId
    seatIndex
    hand
    status
    score
    cumulativeScore
    chipBalance
    hasDeclared
    hasDropped
    consecutiveMissedTurns
    lastActionAt
}
```

Do not expose the internal `PlayerState` directly to clients.

---

# R26. PRIVATE GAME VIEW

The server creates a player-specific view.

Example:

```text
Player A sees:
- own 13 cards
- opponents' card counts
- top discard
- public game state

Player A does NOT see:
- opponent hands
- hidden closed-deck cards
- future shuffle order
- server RNG state
```

This is mandatory for a fair game.

---

# R27. GAME COMMANDS

Minimum command model:

```text
JOIN
LEAVE
READY
DRAW_CLOSED
DRAW_OPEN
DISCARD
DECLARE
DROP
RECONNECT
RESYNC
```

Each command contains:

```json
{
  "commandId": "cmd-123",
  "gameId": "game-123",
  "playerId": "player-123",
  "clientSequence": 42,
  "type": "DRAW_CLOSED",
  "timestamp": "..."
}
```

The server must treat `commandId` as idempotent.

---

# R28. GAME EVENTS

Minimum events:

```text
GAME_CREATED
PLAYER_JOINED
PLAYER_READY
GAME_STARTED
JOKER_SELECTED
CARDS_DEALT
CARD_DRAWN
CARD_DISCARDED
TURN_CHANGED
PLAYER_DROPPED
DECLARE_ATTEMPTED
DECLARE_ACCEPTED
DECLARE_REJECTED
GAME_FINISHED
SCORE_CALCULATED
SETTLEMENT_REQUESTED
SETTLEMENT_COMPLETED
PLAYER_DISCONNECTED
PLAYER_RECONNECTED
GAME_ABORTED
```

Every event should have:

```text
gameId
eventId
sequenceNumber
eventType
timestamp
rulesetVersion
gameEngineVersion
```

---

# R29. EVENT SEQUENCING

Example:

```text
1001 GAME_STARTED
1002 JOKER_SELECTED
1003 CARDS_DEALT
1004 TURN_CHANGED
1005 CARD_DRAWN
1006 CARD_DISCARDED
1007 TURN_CHANGED
```

If a client receives:

```text
1004
1005
1007
```

it knows:

```text
1006 missing
```

and requests:

```text
RESYNC
```

---

# R30. RECONNECT RULES

```text
Disconnect
   ↓
Grace period
   ↓
Player reconnects
   ↓
Authenticate
   ↓
Find active game
   ↓
Send snapshot
   ↓
Send missing events
   ↓
Resume
```

The server must never trust client-side local game state after reconnect.

---

# R31. TIMEOUT ENGINE

Never create:

```text
new Thread()
```

for every player/table.

Use:

```text
central scheduler
+
deadline timestamps
+
partitioned game workers
```

Timeout validation:

```text
currentTime >= turnDeadline
AND
gameState.currentPlayer == playerId
AND
turnSequence unchanged
```

Then generate:

```text
TURN_TIMEOUT
```

and apply the ruleset's timeout policy.

---

# R32. BOT / AI PLAYER RULES

Bots may be supported only where the product/legal rules allow them.

A bot must be clearly identified to the player as:

```text
AI
BOT
```

Do not disguise a bot as a human opponent.

Architecture:

```text
PlayerAgent
   |
   +-- HumanPlayerAgent
   |
   +-- BotPlayerAgent
              |
              ↓
           BotEngine
              |
              ↓
         GameCommand
              |
              ↓
          GameEngine
```

The bot must receive only information available to a normal player.

The bot must NOT access:

```text
opponent hidden cards
future deck order
server RNG state
internal game state unavailable to humans
```

Bot difficulty:

```text
EASY
MEDIUM
HARD
```

The same game engine must process both human and bot commands.

---

# R33. RANDOMNESS AND FAIRNESS

Shuffle and joker selection must be server-side.

Never use:

```text
Math.random()
```

for real-money game randomness.

Use a cryptographically secure RNG where appropriate.

For a production fairness model, record:

```text
rulesetVersion
gameEngineVersion
shuffle algorithm version
randomness metadata
game seed / commitment design
deck hash
event hash chain
```

Do not expose secrets that would allow prediction of future cards.

---

# R34. AUDIT TRAIL

Every financially relevant game must be reconstructable.

Store:

```text
Game ID
Players
Seats
Ruleset
Ruleset version
Engine version
Start time
End time
Commands
Events
Declaration attempt
Final groups
Scores
Winner
Settlement reference
```

For high-value/disputed games, retain sufficient immutable evidence to replay the game state.

---

# R35. DISPUTE REPLAY

A game should be replayable from:

```text
initial state
+
validated commands/events
+
ruleset version
```

Target:

```text
same input
+
same ruleset version
=
same result
```

If the engine changes later:

```text
Engine v1.2
```

must not silently reinterpret old games.

Store:

```text
rulesetVersion = 1.0.0
gameEngineVersion = 1.4.2
```

---

# R36. GAME RESULT MODEL

Example:

```json
{
  "gameId": "G123",
  "rulesetId": "POINTS_13",
  "rulesetVersion": "1.0.0",
  "players": [
    {
      "playerId": "P1",
      "seat": 0,
      "result": "WIN",
      "points": 0
    },
    {
      "playerId": "P2",
      "seat": 1,
      "result": "LOSS",
      "points": 32
    }
  ],
  "winnerPlayerId": "P1",
  "status": "SETTLED"
}
```

---

# R37. REAL-MONEY SETTLEMENT BOUNDARY

The GameEngine must NOT directly modify wallet balances.

Correct:

```text
GameEngine
   ↓
GameFinished Event
   ↓
Settlement Service
   ↓
Wallet Service
   ↓
MongoDB Wallet + Immutable Ledger
```

Incorrect:

```text
GameEngine
   ↓
wallet.balance += winnings
```

The second design is unsafe.

---

# R38. GAME ENTRY

For a real-money game:

```text
Player requests join
       ↓
Eligibility checks
       ↓
Wallet hold / entry authorization
       ↓
Matchmaking
       ↓
Table join
       ↓
Game starts
```

Do not deduct money simply because the frontend says:

```text
"join successful"
```

The server must confirm the financial state.

---

# R39. GAME SETTLEMENT

Correct flow:

```text
Game ends
   ↓
Immutable game result
   ↓
Settlement calculation
   ↓
Idempotency check
   ↓
Wallet transaction
   ↓
Ledger entry
   ↓
Settlement completed
```

If settlement is retried:

```text
same settlementId
```

must not pay twice.

---

# R40. WALLET TRANSACTION TYPES

Minimum:

```text
DEPOSIT
WITHDRAWAL
GAME_ENTRY
GAME_WIN
GAME_REFUND
REVERSAL
PROMOTIONAL_CREDIT
ADJUSTMENT
```

Every balance mutation must be represented by a ledger transaction.

---

# R41. GAME CANCELLATION / ABORT

Possible causes:

```text
server failure
database failure
critical rules error
operator cancellation
network/regional failure
compliance block
```

The ruleset must define whether:

```text
entry is refunded
game is voided
game resumes
game is settled
```

Do not let individual microservices invent their own cancellation behavior.

---

# R42. CONNECTION FAILURE

If one player disconnects:

```text
Game continues
+
player enters reconnect grace period
```

If grace expires:

```text
apply ruleset timeout/drop policy
```

If the game server itself fails:

```text
Game server failure
      ↓
detect ownership loss
      ↓
recover authoritative state
      ↓
new game-server owner
      ↓
resume or safely abort
```

Do not reconstruct money outcomes from client state.

---

# R43. MULTI-GAME-SERVER OWNERSHIP

Every active table has exactly one authoritative owner:

```text
gameId → gameServerId
```

Redis can maintain short-lived routing metadata.

Example:

```text
game:routing:G123
    owner = game-server-17
    epoch = 42
```

Ownership changes must use an epoch/version so an old server cannot continue writing after ownership is lost.

---

# R44. CONCURRENCY RULE

For one table:

```text
one authoritative command sequence
```

Example:

```text
DRAW
DISCARD
TURN_CHANGE
```

must be serialized.

Two simultaneous:

```text
DRAW
```

commands from the same player must not both succeed.

Use:

```text
commandId
turnSequence
gameVersion
```

and reject stale commands.

---

# R45. IDEMPOTENCY

Every client command:

```text
commandId
```

Every settlement:

```text
settlementId
```

Every payment webhook:

```text
providerEventId
```

must be idempotent.

Example:

```text
GAME_WIN settlementId = SET-123

Request 1 → SUCCESS
Request 2 → already processed
Request 3 → already processed
```

Never create three wallet credits.

---

# R46. RULESET CONFIGURATION MODEL

Example:

```json
{
  "rulesetId": "POOL_101_13",
  "version": "1.0.0",
  "playerRange": {
    "min": 2,
    "max": 6
  },
  "cardsPerPlayer": 13,
  "deckCount": 2,
  "printedJokers": 2,
  "minimumSequences": 2,
  "requiredPureSequences": 1,
  "maximumPenalty": 80,
  "firstDropPenalty": 20,
  "middleDropPenalty": 40,
  "eliminationThreshold": 101
}
```

Pool 201:

```json
{
  "rulesetId": "POOL_201_13",
  "eliminationThreshold": 201,
  "firstDropPenalty": 25,
  "middleDropPenalty": 50
}
```

Points:

```json
{
  "rulesetId": "POINTS_13",
  "maximumPenalty": 80,
  "firstDropPenalty": 20,
  "middleDropPenalty": 40
}
```

Deals:

```json
{
  "rulesetId": "DEALS_13",
  "deals": 6,
  "startingChips": 480
}
```

21-card:

```json
{
  "rulesetId": "RUMMY_21",
  "cardsPerPlayer": 21,
  "deckCount": 3,
  "playerRange": {
    "min": 2,
    "max": 6
  }
}
```

---

# R47. VARIANT MATRIX

| Variant | Players | Cards/player | Decks | Structure | End condition |
|---|---:|---:|---:|---|---|
| Points Rummy | 2–6 | 13 | 2 | Single deal | Valid declaration |
| Pool 101 | 2–6 | 13 | 2 | Multi-deal | Players eliminated at 101+ |
| Pool 201 | 2–6 | 13 | 2 | Multi-deal | Players eliminated at 201+ |
| Deals Rummy | 2–6 | 13 | 2 | Fixed deals | Highest final chips |
| 21-Card Rummy | 2–6 | 21 | 3 | Ruleset-specific | Valid declaration / variant rules |
| Tournament | Configurable | Underlying variant | Underlying variant | Multi-table | Tournament rules |

---

# R48. RULE PRIORITY

When rules conflict, use this priority:

```text
1. Published product ruleset
2. Versioned server rules configuration
3. GameEngine implementation
4. Client UI
```

The UI is never the source of truth.

---

# R49. REQUIRED UNIT TESTS

Every ruleset must have tests for:

### Deck

```text
correct deck size
correct card uniqueness
joker count
shuffle
```

### Deal

```text
2 players
3 players
4 players
5 players
6 players
```

### Sequence

```text
valid pure
invalid pure
valid impure
too short
wrong suit
gap too large
duplicate rank
joker substitution
```

### Set

```text
valid 3-card set
valid 4-card set
invalid same-suit duplicate
joker set
invalid mixed rank
```

### Declaration

```text
valid 13-card declaration
missing pure sequence
only one sequence
unmatched card
invalid set
too many cards
too few cards
```

### Drops

```text
first drop
middle drop
timeout
three missed turns
```

### Points

```text
face cards = 10
joker = 0
maximum penalty
winner = 0
```

### Pool

```text
score accumulation
101 elimination
201 elimination
multiple eliminations
last player
```

### Deals

```text
chip allocation
deal settlement
final ranking
tie handling
```

### Reconnect

```text
disconnect
reconnect
stale sequence
missing event
resync
```

### Idempotency

```text
duplicate draw
duplicate discard
duplicate declaration
duplicate settlement
duplicate webhook
```

---

# R50. REQUIRED PROPERTY / FUZZ TESTS

The rules engine should eventually include property-based tests.

Examples:

```text
For every valid declaration:
    all 13 cards are accounted for exactly once.
```

```text
For every invalid declaration:
    at least one validation rule fails.
```

```text
After every normal turn:
    player hand size == 13
```

```text
After every draw:
    player hand size == 14
```

```text
No card instance exists in two locations simultaneously.
```

```text
Total cards across:
hands + closed deck + discard pile
=
constant deck size
```

This is critical for finding rare game-state corruption.

---

# R51. SECURITY RULES FOR THE GAME ENGINE

Never accept from the client:

```text
winner
score
joker
deck
opponent cards
settlement amount
```

The client sends intent:

```text
DRAW
DISCARD
DECLARE
DROP
```

The server determines the result.

---

# R52. OBSERVABILITY

Every game action should be traceable using:

```text
gameId
playerId
commandId
eventId
sequenceNumber
rulesetId
rulesetVersion
gameEngineVersion
```

Metrics:

```text
active games
active tables
commands/sec
events/sec
declaration failures
timeouts
disconnects
reconnects
invalid commands
game duration
settlement latency
```

---

# R53. LOAD-TEST SCENARIOS

The game engine must be tested independently and then through the complete distributed system.

Recommended progression:

```text
1,000 players
5,000
10,000
25,000
50,000
100,000
200,000
300,000
400,000
```

At each stage test:

```text
login
matchmaking
table creation
join
ready
draw
discard
declare
settlement
disconnect
reconnect
```

Also test reconnect storms:

```text
400,000 disconnected
       ↓
30–60 second reconnect burst
```

---

# R54. PERFORMANCE TARGETS

Do not invent capacity based only on CPU core count.

Measure:

```text
WebSocket connections/server
commands/sec/server
events/sec/server
game actions/sec
p50 latency
p95 latency
p99 latency
GC pauses
heap usage
network throughput
Redis latency
MongoDB latency
Kafka lag
```

Then calculate production capacity from benchmark results plus failure headroom.

---

# R55. FINAL IMPLEMENTATION ORDER FOR RULES

Implement in this exact order:

```text
01. Suit
02. Rank
03. Card
04. Joker
05. Deck
06. Card instance IDs
07. PlayerState
08. GameState
09. GameStatus
10. TurnState
11. Group
12. SequenceValidator
13. SetValidator
14. DeclarationValidator
15. ScoreCalculator
16. DropManager
17. JokerManager
18. PointsRummyRules
19. Pool101Rules
20. Pool201Rules
21. DealsRummyRules
22. TwentyOneCardRules
23. GameCommand
24. GameEvent
25. GameEngine
26. GameState transition tests
27. BotPlayerAgent
28. WebSocket adapter
29. Player-specific GameView
30. Event sequencing
31. Reconnect/resync
32. MongoDB persistence
33. Redis routing/presence
34. Matchmaking
35. Multi-game-server ownership
36. Kafka event pipeline
37. Settlement Service
38. MongoDB Wallet Service
39. Payment integration
40. KYC/compliance gates
41. Anti-cheat/fraud
42. Admin/dispute replay
43. Observability
44. Load testing
45. Failure testing
46. Disaster recovery
47. Production deployment
```

---

# R56. IMPORTANT PRODUCT DECISION

Do not build all variants simultaneously just because the engine supports them.

Build the engine generically, but launch one ruleset first:

```text
Phase 1:
Indian 13-card Rummy + Points Rummy

Phase 2:
Pool 101 + Pool 201

Phase 3:
Deals Rummy

Phase 4:
21-card Rummy

Phase 5:
Gin Rummy

Phase 6:
500 Rummy

Phase 7:
Kalooki

Phase 8:
Canasta

Phase 9:
Tournament layer
```

This reduces debugging complexity and makes game-result verification much easier.

The architecture must support all variants from day one, but production rollout should be staged.

---



# 1. PRODUCT GOAL

Build a scalable online rummy platform where:

-   Users register and authenticate.
-   Eligible users can enter rummy tables.
-   Real players can be matched against each other.
-   If the product/legal model permits AI opponents, the AI opponent is
    **clearly labelled as AI/Bot**.
-   The server is authoritative for cards, shuffle, turns, rules,
    scores, and results.
-   Active games run in memory on dedicated game servers.
-   MongoDB stores application/game history.
-   MongoDB stores the financial ledger if real money is legally
    permitted.
-   Redis handles sessions, matchmaking, presence, routing metadata, and
    short-lived coordination.
-   Kafka handles asynchronous events.
-   WebSocket provides real-time gameplay.
-   The system is designed for 300k--400k concurrent connections, but
    actual capacity is determined through load testing.
-   The system has auditability, fraud controls, security,
    observability, backups, disaster recovery, and compliance controls.

------------------------------------------------------------------------

# 2. CORE ARCHITECTURE PRINCIPLE

Do NOT build:

``` text
React
  ↓
Spring Boot
  ↓
MongoDB
  ↓
every card action
```

For 300k--400k concurrent players, use:

``` text
React
  ├── REST/HTTPS ──→ Spring Boot API
  │                       ├── MongoDB
  │                       ├── MongoDB Wallet + Ledger
  │                       ├── Redis
  │                       └── Kafka
  │
  └── WSS ─────────→ Game Gateway
                           ↓
                     Game Server Cluster
                           ↓
                     In-memory GameState
                           ↓
                       Game Engine
                           ↓
                     WebSocket Events
```

### Source of truth

For an active game:

``` text
Game Server memory
       ↓
Game Engine
       ↓
Authoritative GameState
```

MongoDB is not the hot-path source of truth for every card movement.

------------------------------------------------------------------------

# 3. TARGET CAPACITY MODEL

Do not use only "400k users" as the capacity requirement.

Assuming 4 players per table:

``` text
400,000 concurrent players
÷ 4
≈ 100,000 concurrent tables
```

The actual engineering targets must include:

``` text
CCU
Active tables
WebSocket connections
Game commands/sec
Game events/sec
REST requests/sec
Reconnects/sec
Redis operations/sec
MongoDB writes/sec
Kafka events/sec
Network bandwidth
CPU
RAM
GC pauses
p95 latency
p99 latency
```

These values must be measured.

### Example only

If a game server benchmark proves:

``` text
15,000 stable concurrent connections/instance
```

then:

``` text
400,000 / 15,000 ≈ 27 instances
```

Production capacity would require additional headroom and failure
capacity.

**Never use this example as the final production sizing number.**

------------------------------------------------------------------------

# 4. HIGH-LEVEL SYSTEM ARCHITECTURE

``` text
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

                 FINANCIAL PATH
                 ───────────────
       Payment Provider
              │
              ▼
        Wallet Service
              │
              ▼
     MongoDB Wallet + Ledger
```

------------------------------------------------------------------------

# 5. TECHNOLOGY STACK

## Frontend

-   React
-   TypeScript
-   Vite or Next.js
-   React Router
-   Zustand or Redux Toolkit
-   WebSocket client
-   Axios/fetch
-   Form validation
-   Error boundary
-   Feature flags

## Backend

-   Java 21+
-   Spring Boot
-   Spring Security
-   Spring WebFlux / Netty for WebSocket-heavy workloads
-   WebSocket
-   Bean Validation
-   Micrometer
-   OpenTelemetry

## Data

-   MongoDB
-   MongoDB
-   Redis Cluster
-   Kafka

## Infrastructure

-   Docker
-   Kubernetes/EKS or ECS
-   CDN
-   WAF
-   Load Balancer
-   Object Storage
-   Secrets Manager
-   Monitoring
-   Centralized logs

## Testing

-   JUnit 5
-   Mockito
-   Testcontainers
-   Playwright/Cypress
-   Gatling/k6/Locust or equivalent
-   Custom bot/player simulator

------------------------------------------------------------------------

# 6. REPOSITORY STRUCTURE

Recommended starting structure:

``` text
rummy-platform/
│
├── frontend/
│   └── rummy-web/
│
├── backend/
│   ├── api-service/
│   ├── game-service/
│   ├── matchmaking-service/
│   └── notification-service/
│
├── shared/
│   ├── game-contracts/
│   ├── event-contracts/
│   └── common-security/
│
├── infrastructure/
│   ├── mongodb/
│   ├── postgres/
│   ├── redis/
│   ├── kafka/
│   └── monitoring/
│
├── load-test/
│   ├── player-simulator/
│   ├── websocket-load/
│   └── scenarios/
│
├── deployment/
│   ├── docker/
│   ├── kubernetes/
│   └── environments/
│
└── docs/
    ├── architecture/
    ├── api/
    ├── game-rules/
    ├── security/
    ├── operations/
    └── compliance/
```

------------------------------------------------------------------------

# 7. DEVELOPMENT RULE --- ONE PHASE AT A TIME

Do not start all components simultaneously.

Use this order:

``` text
01. Requirements
02. Legal/compliance gate
03. Game rules specification
04. Game engine
05. Game-engine tests
06. Bot engine
07. WebSocket game server
08. React game client
09. MongoDB persistence
10. Redis
11. Matchmaking
12. Multi-game-server routing
13. Kafka/event system
14. Authentication/security
15. Wallet/ledger
16. Payments
17. KYC/compliance
18. Admin
19. Notifications
20. Observability
21. CI/CD
22. Load testing
23. Failure testing
24. Security testing
25. Staging
26. Production readiness
27. Legal release gate
28. Production launch
```

Do not skip ahead because a later component looks easier.

------------------------------------------------------------------------

# 8. PHASE 01 --- REQUIREMENTS

## Goal

Freeze what the application actually needs to do.

Create:

``` text
docs/requirements/product-requirements.md
```

Define:

### Players

-   Registration
-   Login
-   Logout
-   Profile
-   Session management
-   Device management

### Game

-   Rummy variant
-   Number of players/table
-   Entry rules
-   Turn duration
-   Draw rules
-   Discard rules
-   Declare rules
-   Scoring
-   Timeout
-   Disconnect
-   Reconnect
-   Game cancellation
-   Settlement

### Money

Only after legal confirmation:

-   Deposit
-   Wallet
-   Entry fee
-   Prize
-   Refund
-   Withdrawal
-   Transaction history

### Responsible gambling

-   Age verification
-   KYC
-   Self-exclusion
-   Account limits
-   Session controls
-   Suspicious activity detection
-   Responsible-gambling messaging

### Definition of done

Requirements document approved.

------------------------------------------------------------------------

------------------------------------------------------------------------

# 10. PHASE 03 --- FREE-PLAY / ENGINE DEVELOPMENT

Before money:

Build the game completely using virtual/non-withdrawable points.

This lets you develop:

``` text
Game Engine
WebSocket
React
Bots
Matchmaking
Persistence
Load testing
```

without coupling the entire project to payment flows.

------------------------------------------------------------------------

# 11. PHASE 04 --- GAME RULE SPECIFICATION

Create:

``` text
docs/game-rules/rummy-rules.md
```

Document:

## Deck

-   Number of decks
-   Jokers
-   Cards
-   Shuffle method

## Deal

-   Cards/player
-   Starting player
-   Initial discard

## Turn

``` text
DRAW
  ↓
OPTIONAL ACTIONS
  ↓
DISCARD
  ↓
NEXT PLAYER
```

## Winning

Define:

-   Valid sequence
-   Pure sequence
-   Impure sequence
-   Set
-   Invalid declare
-   Scoring
-   Maximum/minimum score

## Timeout

Define:

``` text
player timeout
    ↓
automatic action
OR
drop
OR
game rule
```

## Disconnect

Define:

``` text
temporary disconnect
permanent disconnect
reconnect
timeout
```

No developer should have to guess game rules.

------------------------------------------------------------------------

# 12. PHASE 05 --- JAVA GAME ENGINE

Create a framework-independent game engine.

Suggested packages:

``` text
game/
├── model/
│   ├── Card.java
│   ├── Deck.java
│   ├── PlayerState.java
│   ├── GameState.java
│   ├── GameStatus.java
│   └── TurnState.java
│
├── command/
│   ├── JoinCommand.java
│   ├── ReadyCommand.java
│   ├── DrawCommand.java
│   ├── DiscardCommand.java
│   ├── DeclareCommand.java
│   └── LeaveCommand.java
│
├── event/
│   ├── PlayerJoined.java
│   ├── GameStarted.java
│   ├── CardDrawn.java
│   ├── CardDiscarded.java
│   ├── TurnChanged.java
│   ├── PlayerDisconnected.java
│   └── GameFinished.java
│
├── rules/
│   ├── RummyRules.java
│   ├── SequenceValidator.java
│   └── ScoreCalculator.java
│
└── engine/
    └── GameEngine.java
```

Core model:

``` java
public final class GameState {
    private String gameId;
    private String tableId;
    private List<PlayerState> players;
    private Deck deck;
    private List<Card> discardPile;
    private String currentPlayerId;
    private long sequence;
    private Instant turnDeadline;
    private GameStatus status;
}
```

Core principle:

``` text
GameState + Command
        ↓
GameEngine
        ↓
New GameState + Events
```

The engine must not directly call MongoDB, Redis, Kafka, HTTP, or
WebSocket.

------------------------------------------------------------------------

# 13. PHASE 06 --- GAME ENGINE TESTS

Before WebSocket:

Test:

``` text
deck creation
shuffle
deal
draw
discard
turn order
invalid turn
invalid card
invalid declare
valid declare
score
timeout
disconnect
reconnect
```

Use property-based/simulation testing where useful.

Target:

``` text
No illegal state transitions.
No duplicate cards.
No card leakage.
No impossible game state.
```

------------------------------------------------------------------------

# 14. PHASE 07 --- BOT ENGINE

Bots must be clearly identified in the UI.

Architecture:

``` text
PlayerAgent
   ├── HumanPlayerAgent
   └── BotPlayerAgent
```

Interface:

``` java
public interface PlayerAgent {
    GameCommand chooseAction(GameState state);
}
```

Bot:

``` text
BotEngine
   ↓
Visible game state
   ↓
Bot's private hand
   ↓
Legal moves
   ↓
Strategy
   ↓
GameCommand
```

Difficulty:

``` text
EASY
MEDIUM
HARD
```

The bot must not receive hidden future cards or privileged information
unavailable to a human player.

------------------------------------------------------------------------

# 15. PHASE 08 --- BOT STRATEGY

## Easy

-   Legal moves
-   Basic sequence preservation
-   Basic discard selection
-   Small amount of randomness

## Medium

-   Hand evaluation
-   Sequence potential
-   Set potential
-   Discard analysis
-   Opponent public discard history

## Hard

-   Probability estimates
-   Opponent discard modelling
-   Better deadwood minimization
-   Risk-aware discard selection

Do not give bots secret information.

------------------------------------------------------------------------

# 16. PHASE 09 --- WEBSOCKET GAME SERVER

Use WSS.

Example:

``` text
wss://api.example.com/game
```

Message structure:

``` json
{
  "type": "GAME_COMMAND",
  "requestId": "req-123",
  "tableId": "T123",
  "payload": {
    "action": "DISCARD",
    "card": "7H"
  }
}
```

Server response:

``` json
{
  "type": "GAME_EVENT",
  "eventId": "evt-123",
  "sequence": 184,
  "event": "CARD_DISCARDED",
  "payload": {
    "playerId": "P123",
    "card": "7H"
  }
}
```

------------------------------------------------------------------------

# 17. PHASE 10 --- AUTHORITATIVE SERVER

Never trust React.

Client can request:

``` text
DRAW
DISCARD
DECLARE
```

Server validates:

``` text
authenticated?
authorized?
correct table?
correct player?
correct turn?
valid card?
valid game state?
valid action?
```

Only then:

``` text
GameEngine.apply(command)
```

The server owns:

``` text
deck
shuffle
hands
turn
rules
score
winner
```

------------------------------------------------------------------------

# 18. PHASE 11 --- SEQUENCE NUMBERS

Every game event receives:

``` text
sequence = 1
sequence = 2
sequence = 3
...
```

Client stores the last sequence.

If a gap occurs:

``` text
100
101
103
```

Client requests:

``` text
RESYNC(lastSequence=101)
```

Server sends:

-   missing events, or
-   a full state snapshot.

This is required for robust reconnect handling.

------------------------------------------------------------------------

# 19. PHASE 12 --- RECONNECTION

Flow:

``` text
WebSocket disconnect
        ↓
Player marked disconnected
        ↓
Grace period
        ↓
Player reconnects
        ↓
Authenticate
        ↓
Find current table
        ↓
Validate session
        ↓
Send state/event recovery
        ↓
Resume
```

Never assume the client has the latest state.

------------------------------------------------------------------------

# 20. PHASE 13 --- REACT GAME CLIENT

Structure:

``` text
src/
├── app/
├── auth/
├── lobby/
├── matchmaking/
├── game/
│   ├── components/
│   ├── store/
│   ├── websocket/
│   ├── hooks/
│   └── types/
├── wallet/
├── profile/
└── history/
```

Game state:

``` typescript
interface GameState {
  tableId: string;
  gameId: string;
  players: Player[];
  ownHand: Card[];
  discardPile: Card[];
  currentTurn: string;
  sequence: number;
  status: GameStatus;
  turnDeadline: number;
}
```

Use one central game store.

------------------------------------------------------------------------

# 21. PHASE 14 --- PRIVATE INFORMATION

Never broadcast every player's hand.

Server creates a player-specific view.

Player A receives:

``` text
A's own cards
+
public game state
```

Player B receives:

``` text
B's own cards
+
public game state
```

Never:

``` text
all players' private cards
```

------------------------------------------------------------------------

# 22. PHASE 15 --- MONGODB

Collections:

``` text
users
profiles
sessions
games
game_results
game_events
tournaments
leaderboards
notifications
audit_documents
```

Example:

``` json
{
  "_id": "G12345",
  "tableId": "T123",
  "players": [
    {"playerId": "P1", "score": 0},
    {"playerId": "P2", "score": 25}
  ],
  "winner": "P1",
  "startedAt": "2026-09-08T10:00:00Z",
  "finishedAt": "2026-09-08T10:08:00Z"
}
```

Do not update a MongoDB game-state document every second.

------------------------------------------------------------------------

# 23. PHASE 16 --- MONGODB INDEXES

Plan indexes based on actual query patterns.

Examples:

``` text
users:
  email
  phone
  userId

games:
  gameId
  tableId
  status
  createdAt

game_results:
  playerId + createdAt
  gameId

transactions:
  playerId + createdAt
```

Use `explain()` on important queries.

Do not create indexes blindly.

------------------------------------------------------------------------

# 24. PHASE 17 --- REDIS

Use Redis for:

``` text
sessions
presence
matchmaking queues
table → server routing
rate limiting
short-lived locks
temporary state
```

Example:

``` text
table:T123 → game-server-07
player:P123 → game-server-07
```

Redis should not become the only source of truth for active game logic.

------------------------------------------------------------------------

# 25. PHASE 18 --- MATCHMAKING

Flow:

``` text
Player clicks PLAY
       ↓
Eligibility check
       ↓
Matchmaking queue
       ↓
Find compatible players
       ↓
Create table
       ↓
Assign game server
       ↓
Connect players
       ↓
Start game
```

Redis queues:

``` text
rummy:mode:100
rummy:mode:200
rummy:tournament:123
```

If AI is permitted:

``` text
No real opponent
       ↓
Allowed AI fallback
       ↓
Create table
       ↓
Add clearly-labelled AI player
```

------------------------------------------------------------------------

# 26. PHASE 19 --- MULTI-GAME-SERVER ARCHITECTURE

A table should have one authoritative owner.

``` text
T123 → Game Server 07
T456 → Game Server 12
T789 → Game Server 03
```

Table routing:

``` text
tableId
   ↓
Redis/table-router
   ↓
Game Server
```

All commands for T123 go to the owner.

This avoids distributed locking for every card action.

------------------------------------------------------------------------

# 27. PHASE 20 --- TABLE ACTOR MODEL

Each table behaves like a sequential actor:

``` text
TableActor
├── GameState
├── command queue
├── timer/deadline
└── connected players
```

Commands:

``` text
JOIN
READY
DRAW
DISCARD
DECLARE
LEAVE
TIMEOUT
RECONNECT
```

Process one logical command at a time.

This reduces race conditions.

------------------------------------------------------------------------

# 28. PHASE 21 --- TIMER SYSTEM

Do not create a dedicated Java thread/timer for every table.

Use a scalable scheduler/timing-wheel style design.

Store:

``` text
turnDeadline
```

When a timeout event fires:

``` text
TIMEOUT command
   ↓
TableActor
   ↓
verify deadline
   ↓
GameEngine
```

Always verify the actual deadline; do not blindly trust a scheduler
callback.

------------------------------------------------------------------------

# 29. PHASE 22 --- KAFKA

Publish events such as:

``` text
GAME_STARTED
PLAYER_JOINED
CARD_DRAWN
CARD_DISCARDED
GAME_FINISHED
PLAYER_DISCONNECTED
WALLET_TRANSACTION_COMPLETED
```

Consumers:

``` text
Game History
Analytics
Leaderboard
Audit
Notifications
Fraud Detection
```

Game server should not wait synchronously for every downstream consumer.

------------------------------------------------------------------------

# 30. PHASE 23 --- FINANCIAL WALLET

If real-money operation is legally approved:

Use a separate Wallet Service.

``` text
React
 ↓
Wallet API
 ↓
Wallet Service
 ↓
MongoDB Wallet + Ledger
 ↓
Payment Provider
```

Do not let the Game Service directly modify wallet balances.

------------------------------------------------------------------------

# 31. PHASE 24 --- LEDGER DESIGN

Example ledger entry:

``` json
{
  "transactionId": "TX123",
  "playerId": "P123",
  "type": "GAME_ENTRY",
  "gameId": "G123",
  "amount": 20.00,
  "currency": "AUD",
  "status": "COMPLETED",
  "createdAt": "..."
}
```

Transaction types:

``` text
DEPOSIT
WITHDRAWAL
GAME_ENTRY
GAME_WIN
GAME_REFUND
PROMOTIONAL_CREDIT
REVERSAL
```

Use immutable transaction history.

Do not simply overwrite:

``` text
wallet.balance = wallet.balance - 20
```

without a corresponding auditable ledger transaction.

------------------------------------------------------------------------

# 32. PHASE 25 --- PAYMENT INTEGRATION

Only after legal/compliance approval.

Flow:

``` text
Deposit request
      ↓
Payment provider
      ↓
Provider webhook
      ↓
Verify signature
      ↓
Idempotency check
      ↓
Ledger transaction
      ↓
Wallet balance
      ↓
Audit event
```

Never trust the browser saying:

``` text
payment = success
```

The payment provider's verified server-side callback is authoritative.

------------------------------------------------------------------------

# 33. PHASE 26 --- WITHDRAWAL

Flow:

``` text
Player requests withdrawal
        ↓
Eligibility check
        ↓
KYC status
        ↓
Fraud/risk checks
        ↓
Balance/ledger check
        ↓
Create withdrawal transaction
        ↓
Payment provider
        ↓
Provider result
        ↓
Ledger update
        ↓
Notification
```

Withdrawal must be idempotent.

------------------------------------------------------------------------

# 34. PHASE 27 --- KYC / AGE / RESPONSIBLE GAMBLING

Create:

``` text
ComplianceService
```

Responsibilities:

``` text
identity verification
age verification
self-exclusion
account restrictions
responsible-gambling limits
risk flags
transaction monitoring
audit
regulatory reporting
```

Do not store more personal data than necessary.

Encrypt sensitive data.

------------------------------------------------------------------------

# 35. PHASE 28 --- AUTHENTICATION

Use:

``` text
Spring Security
JWT or secure session
HTTPS
WSS
refresh-token/session rotation
rate limiting
```

Authentication identity must come from the verified token/session.

Do not trust:

``` text
playerId
```

from arbitrary WebSocket payloads.

------------------------------------------------------------------------

# 36. PHASE 29 --- SECURITY

Implement:

``` text
TLS
WSS
WAF
rate limiting
input validation
authorization
CSRF strategy where applicable
secure cookies if used
secret management
encryption at rest
audit logs
dependency scanning
SAST
DAST
container scanning
```

Game-specific protections:

``` text
duplicate command prevention
replay protection
request IDs
sequence validation
table authorization
rapid action throttling
```

------------------------------------------------------------------------

# 37. PHASE 30 --- ANTI-CHEAT / FRAUD

Monitor:

``` text
multiple accounts
device reuse
unusual win rates
collusion indicators
abnormal game timing
IP/device patterns
rapid account creation
unusual deposit/withdrawal patterns
bot abuse
bonus abuse
```

Do not make automatic permanent account bans based on a single weak
signal.

Use:

``` text
risk score
→ review
→ restriction
→ decision
```

where appropriate.

------------------------------------------------------------------------

# 38. PHASE 31 --- GAME FAIRNESS / AUDIT

The server controls:

``` text
shuffle
deal
cards
turn
rules
score
result
```

Every important game should have:

``` text
gameId
tableId
playerIds
serverId
event sequence
timestamps
game result
settlement reference
```

For disputes, you should be able to reconstruct what happened.

------------------------------------------------------------------------

# 39. PHASE 32 --- ADMIN PANEL

Admin features:

``` text
Dashboard
Users
KYC status
Games
Live tables
Game history
Transactions
Deposits
Withdrawals
Risk alerts
Fraud cases
Self-exclusion
Support tickets
Audit logs
System health
```

Admin permissions must be role-based.

Example:

``` text
SUPER_ADMIN
SUPPORT
FINANCE
RISK
COMPLIANCE
OPS
```

Do not give every employee database access.

------------------------------------------------------------------------

# 40. PHASE 33 --- OBSERVABILITY

Use:

``` text
Prometheus
Grafana
OpenTelemetry
Centralized logs
```

Monitor:

``` text
CCU
active tables
WebSocket connections
connections/sec
disconnects/sec
reconnects/sec
game commands/sec
game events/sec
p50 latency
p95 latency
p99 latency
API error rate
CPU
RAM
GC
network
Redis latency
MongoDB latency
Kafka lag
MongoDB wallet/ledger latency
payment failures
```

------------------------------------------------------------------------

# 41. PHASE 34 --- LOG FORMAT

Every important game event should include:

``` text
timestamp
gameId
tableId
playerId
eventId
sequence
serverId
eventType
requestId
```

Example:

``` text
2026-09-08T10:20:10Z
game=G123
table=T123
player=P123
event=DISCARD
sequence=1842
server=game-07
request=req-999
```

Never log passwords, payment secrets, full tokens, or unnecessary
sensitive personal data.

------------------------------------------------------------------------

# 42. PHASE 35 --- CI/CD

Pipeline:

``` text
Git push
   ↓
Build
   ↓
Unit tests
   ↓
Static analysis
   ↓
Security scan
   ↓
Integration tests
   ↓
Docker build
   ↓
Container scan
   ↓
Staging
   ↓
Automated tests
   ↓
Load/smoke test
   ↓
Approval
   ↓
Production
```

Use separate:

``` text
dev
staging
production
```

Never use production credentials locally.

------------------------------------------------------------------------

# 43. PHASE 36 --- ENVIRONMENT VARIABLES

Example:

``` text
MONGODB_URI
POSTGRES_URL
REDIS_URL
KAFKA_BOOTSTRAP_SERVERS
JWT_SECRET / KEY REFERENCES
PAYMENT_PROVIDER_CONFIG
KYC_PROVIDER_CONFIG
```

Never commit:

``` text
passwords
API secrets
private keys
production credentials
```

Use a secrets manager.

------------------------------------------------------------------------

# 44. PHASE 37 --- LOAD TESTING

Build a realistic player simulator.

Bot/player lifecycle:

``` text
CONNECT
LOGIN
LOBBY
MATCH
JOIN_TABLE
READY
DRAW
DISCARD
WAIT
DRAW
DISCARD
DECLARE
GAME_FINISH
LEAVE
RECONNECT
```

Run:

``` text
1k
5k
10k
25k
50k
100k
200k
300k
400k
```

Do not jump directly to 400k.

------------------------------------------------------------------------

# 45. PHASE 38 --- RECONNECT STORM TEST

Important test:

``` text
400k connected players
        ↓
network disruption
        ↓
connections drop
        ↓
all reconnect within 30–60 seconds
```

Measure:

``` text
authentication throughput
Redis throughput
WebSocket accept rate
CPU
memory
connection queue
error rate
recovery time
```

A system that survives steady 400k CCU can still fail badly during a
reconnect storm.

------------------------------------------------------------------------

# 46. PHASE 39 --- FAILURE TESTING

Simulate:

``` text
Game server crash
Redis node failure
MongoDB primary failure
Kafka broker failure
MongoDB failover
network packet loss
load balancer failure
pod restart
node failure
region failure
```

Define expected behavior for each.

------------------------------------------------------------------------

# 47. PHASE 40 --- DISASTER RECOVERY

Define:

``` text
RPO
RTO
backup frequency
backup retention
restore procedure
regional recovery
database recovery
secret recovery
```

Test restores.

A backup that has never been restored is not a proven backup.

------------------------------------------------------------------------

# 48. PHASE 41 --- DATABASE BACKUPS

MongoDB:

``` text
scheduled backups
point-in-time recovery where supported
replica set
restore testing
```

MongoDB:

``` text
continuous backup/WAL strategy
point-in-time recovery
replica
restore testing
```

------------------------------------------------------------------------

# 49. PHASE 42 --- PRODUCTION DEPLOYMENT

Recommended:

``` text
CloudFront/Cloudflare
       ↓
WAF
       ↓
Load Balancer
       ↓
API Cluster
       ↓
Game Cluster
       ↓
Redis Cluster
       ↓
Kafka Cluster
       ↓
MongoDB Cluster
       ↓
MongoDB HA for wallet/ledger
```

Use multiple availability zones.

Do not place the entire system in one VM.

------------------------------------------------------------------------

# 50. PHASE 43 --- GAME SERVER DEPLOYMENT / DRAINING

Never kill a game server with active tables casually.

Before deployment:

``` text
stop new table assignment
        ↓
drain server
        ↓
finish/migrate/recover active tables
        ↓
terminate instance
```

A proper game-state recovery strategy must exist before automated
deployments.

------------------------------------------------------------------------

# 51. PHASE 44 --- SECURITY TESTING

Before production:

``` text
dependency scan
SAST
DAST
API security test
WebSocket security test
authentication test
authorization test
rate-limit test
replay attack test
session hijacking test
input fuzzing
container scan
cloud configuration review
```

For real-money systems, use an independent security review/penetration
test.

------------------------------------------------------------------------

# 52. PHASE 45 --- PRODUCTION READINESS CHECKLIST

## Application

-   [ ] Game engine tested
-   [ ] Rules frozen
-   [ ] Bot tested
-   [ ] WebSocket tested
-   [ ] Reconnect tested
-   [ ] Sequence recovery tested
-   [ ] Private information isolation tested

## Data

-   [ ] MongoDB indexes
-   [ ] MongoDB backup
-   [ ] MongoDB wallet/ledger backup
-   [ ] Restore tested
-   [ ] Redis HA
-   [ ] Kafka HA

## Security

-   [ ] TLS/WSS
-   [ ] WAF
-   [ ] Rate limits
-   [ ] Secrets manager
-   [ ] Security scans
-   [ ] Pen test

## Money

-   [ ] Legal approval
-   [ ] KYC
-   [ ] Age verification
-   [ ] Wallet ledger
-   [ ] Payment verification
-   [ ] Withdrawal controls
-   [ ] Audit trail
-   [ ] Fraud controls

## Operations

-   [ ] Monitoring
-   [ ] Alerting
-   [ ] Logs
-   [ ] On-call
-   [ ] Incident runbook
-   [ ] Disaster recovery
-   [ ] Capacity plan

------------------------------------------------------------------------

# 53. PHASE 46 --- STAGING

Staging should resemble production.

Test:

``` text
API
WebSocket
MongoDB
Redis
Kafka
MongoDB
Payment sandbox
KYC sandbox
Admin
Monitoring
```

Run complete end-to-end scenarios.

------------------------------------------------------------------------

# 54. PHASE 47 --- CANARY PRODUCTION

Do not release to 100% immediately.

Flow:

``` text
5%
 ↓
10%
 ↓
25%
 ↓
50%
 ↓
100%
```

Monitor:

``` text
errors
latency
disconnects
game failures
payment failures
wallet mismatches
```

------------------------------------------------------------------------

# 55. PHASE 48 --- POST-LAUNCH

Every day monitor:

``` text
CCU
active tables
games/hour
game completion rate
disconnect rate
reconnect rate
p99 latency
payment success
withdrawal success
fraud alerts
support tickets
server utilization
database utilization
```

Every week:

``` text
capacity review
security review
incident review
cost review
fraud review
```

------------------------------------------------------------------------

# 56. COMPLETE API PLAN

## Auth

``` text
POST /api/v1/auth/register
POST /api/v1/auth/login
POST /api/v1/auth/logout
POST /api/v1/auth/refresh
```

## User

``` text
GET  /api/v1/me
PUT  /api/v1/me
GET  /api/v1/me/sessions
```

## Matchmaking

``` text
POST /api/v1/matchmaking/join
DELETE /api/v1/matchmaking/leave
GET /api/v1/matchmaking/status
```

## Games

``` text
GET /api/v1/games/{gameId}
GET /api/v1/games/history
```

Game actions should primarily use WebSocket.

## Wallet

Only after legal approval:

``` text
GET  /api/v1/wallet
GET  /api/v1/wallet/transactions
POST /api/v1/wallet/deposit
POST /api/v1/wallet/withdraw
```

## Responsible gambling

``` text
GET  /api/v1/responsible-gambling/settings
PUT  /api/v1/responsible-gambling/limits
POST /api/v1/self-exclusion
```

Exact endpoints depend on legal/compliance requirements.

------------------------------------------------------------------------

# 57. WEBSOCKET CONTRACT

Connection:

``` text
wss://api.example.com/ws/game
```

Client → Server:

``` text
AUTH
JOIN_TABLE
READY
DRAW
DISCARD
DECLARE
LEAVE
RESYNC
PING
```

Server → Client:

``` text
CONNECTED
TABLE_JOINED
GAME_STARTED
CARD_DRAWN
CARD_DISCARDED
TURN_CHANGED
PLAYER_JOINED
PLAYER_LEFT
PLAYER_DISCONNECTED
TIMER
GAME_FINISHED
ERROR
RESYNC_RESPONSE
```

------------------------------------------------------------------------

# 58. ERROR MODEL

Use standard error structure:

``` json
{
  "errorCode": "INVALID_TURN",
  "message": "It is not your turn.",
  "requestId": "req-123"
}
```

Do not expose internal stack traces.

------------------------------------------------------------------------

# 59. IDEMPOTENCY

Important for payments and game commands.

Example:

``` text
requestId = REQ-123
```

If the same request is received twice:

``` text
REQ-123
REQ-123
```

server must not execute the operation twice when the operation is
supposed to be idempotent.

This is especially critical for:

``` text
deposit
withdrawal
game entry
refund
payout
```

------------------------------------------------------------------------

# 60. WALLET / GAME SEPARATION

Do not:

``` text
Game Server
   ↓
UPDATE wallet
```

Use:

``` text
Game Service
   ↓
Settlement Event
   ↓
Wallet Service
   ↓
Ledger
```

Example:

``` text
GAME_FINISHED
    ↓
Settlement Service
    ↓
validate result
    ↓
create payout ledger transaction
    ↓
Wallet updated
```

------------------------------------------------------------------------

# 61. GAME SETTLEMENT

A game result should have:

``` text
gameId
tableId
players
scores
winner
rulesetVersion
gameEngineVersion
eventSequence
settlementStatus
```

Settlement state:

``` text
PENDING
VALIDATING
SETTLED
FAILED
REVERSED
```

Never silently change a settled result.

------------------------------------------------------------------------

# 62. VERSION YOUR GAME RULES

If rules change later:

``` text
rulesetVersion = 1
rulesetVersion = 2
```

Store the version with the game.

This lets you reproduce old games using the correct rules.

------------------------------------------------------------------------

# 63. VERSION YOUR GAME ENGINE

Example:

``` text
gameEngineVersion = 1.4.2
```

Store it in game metadata.

For dispute investigation:

``` text
Game G123
Rules = 2
Engine = 1.4.2
```

This is much better than trying to guess which code was running months
later.

------------------------------------------------------------------------

# 64. BOT VERSIONING

Store:

``` text
botType
botDifficulty
botVersion
```

Example:

``` text
botType = AI
botDifficulty = MEDIUM
botVersion = 2.1
```

This is useful for debugging and fairness investigations.

------------------------------------------------------------------------

# 65. PLAYER VIEW MODEL

Never serialize the raw internal `GameState` directly.

Use:

``` text
GameState
   ↓
PlayerViewBuilder
   ↓
Player-specific GameView
   ↓
WebSocket
```

This prevents accidental information leakage.

------------------------------------------------------------------------

# 66. EVENT SOURCING --- PRACTICAL APPROACH

You do not need to implement full event sourcing everywhere.

Use a practical hybrid:

``` text
Hot state
    ↓
Game Server memory

Important events
    ↓
Kafka

Game result/history
    ↓
MongoDB

Financial ledger
    ↓
MongoDB
```

This is simpler than making every system fully event-sourced.

------------------------------------------------------------------------

# 67. WHAT NOT TO DO

## Don't

``` text
MongoDB for every card action
```

## Don't

``` text
One giant Spring Boot instance
```

## Don't

``` text
One database for everything
```

## Don't

``` text
Client decides winner
```

## Don't

``` text
Client controls shuffle
```

## Don't

``` text
Bot gets hidden future cards
```

## Don't

``` text
Wallet balance updated directly by game UI
```

## Don't

``` text
Trust payment success from browser
```

## Don't

``` text
Create one timer thread per table
```

## Don't

``` text
Deploy 400k target without load testing
```

## Don't

``` text
Launch Australian real-money mode before legal approval
```

------------------------------------------------------------------------

# 68. FIRST 30 DEVELOPMENT TASKS

Start exactly here.

### Task 01

Create Git repository.

### Task 02

Create README and architecture docs.

### Task 03

Create React project.

### Task 04

Create Java 21 Spring Boot project.

### Task 05

Create Game Engine module.

### Task 06

Implement Card.

### Task 07

Implement Deck.

### Task 08

Implement shuffle.

### Task 09

Implement PlayerState.

### Task 10

Implement GameState.

### Task 11

Implement RummyRules.

### Task 12

Implement draw.

### Task 13

Implement discard.

### Task 14

Implement turn management.

### Task 15

Implement declare validation.

### Task 16

Implement scoring.

### Task 17

Write unit tests.

### Task 18

Write simulation tests.

### Task 19

Implement BotPlayer.

### Task 20

Implement EasyBot.

### Task 21

Implement MediumBot.

### Task 22

Implement HardBot.

### Task 23

Implement WebSocket server.

### Task 24

Connect React to WebSocket.

### Task 25

Build table UI.

### Task 26

Implement reconnect.

### Task 27

Implement sequence/resync.

### Task 28

Add MongoDB.

### Task 29

Persist game result/history.

### Task 30

Run 1,000 simulated games.

------------------------------------------------------------------------

# 69. NEXT 30 TASKS

### Task 31

Add Redis.

### Task 32

Add matchmaking.

### Task 33

Add table routing.

### Task 34

Run 100 concurrent tables.

### Task 35

Run 1,000 concurrent tables.

### Task 36

Create multiple game-server instances.

### Task 37

Implement server ownership.

### Task 38

Implement graceful draining.

### Task 39

Add Kafka.

### Task 40

Publish game events.

### Task 41

Create history consumer.

### Task 42

Create analytics consumer.

### Task 43

Create leaderboard consumer.

### Task 44

Create notification consumer.

### Task 45

Add authentication.

### Task 46

Add authorization.

### Task 47

Add rate limiting.

### Task 48

Add audit logging.

### Task 49

Add monitoring.

### Task 50

Add centralized logs.

### Task 51

Create load simulator.

### Task 52

Test 10k connections.

### Task 53

Test 50k connections.

### Task 54

Test 100k connections.

### Task 55

Optimize bottlenecks.

### Task 56

Test 200k.

### Task 57

Test 300k.

### Task 58

Test 400k.

### Task 59

Run reconnect storm.

### Task 60

Run failure tests.

------------------------------------------------------------------------

# 70. MONEY PHASE --- ONLY AFTER LEGAL APPROVAL

### Task 61

Create Wallet Service.

### Task 62

Create MongoDB ledger.

### Task 63

Implement deposit intent.

### Task 64

Implement provider webhook.

### Task 65

Verify webhook signature.

### Task 66

Implement idempotency.

### Task 67

Implement game-entry debit.

### Task 68

Implement game settlement.

### Task 69

Implement payout.

### Task 70

Implement withdrawal.

### Task 71

Implement refund.

### Task 72

Implement reconciliation.

### Task 73

Implement KYC integration.

### Task 74

Implement age verification.

### Task 75

Implement self-exclusion.

### Task 76

Implement responsible-gambling controls.

### Task 77

Implement transaction monitoring.

### Task 78

Implement fraud/risk engine.

### Task 79

Implement compliance reports.

### Task 80

Perform legal/compliance review again.

------------------------------------------------------------------------

# 71. FINAL PRODUCTION PHASE

### Task 81

Production cloud environment.

### Task 82

Multi-AZ deployment.

### Task 83

Database HA.

### Task 84

Redis HA.

### Task 85

Kafka HA.

### Task 86

Backups.

### Task 87

Restore test.

### Task 88

Disaster recovery.

### Task 89

Security penetration test.

### Task 90

Performance test.

### Task 91

400k CCU test.

### Task 92

Reconnect storm test.

### Task 93

Payment failure test.

### Task 94

Wallet reconciliation test.

### Task 95

Game dispute reconstruction test.

### Task 96

Admin access audit.

### Task 97

Incident response test.

### Task 98

Canary deployment.

### Task 99

Production readiness sign-off.

### Task 100

Legal/compliance release approval.

### Task 101

Limited production launch.

### Task 102

Monitor.

### Task 103

Increase traffic gradually.

------------------------------------------------------------------------

# 72. DEFINITION OF DONE

The project is NOT done because:

``` text
React UI works
+
API works
+
Game works
```

It is done only when:

``` text
Game engine correct
+
WebSocket stable
+
Reconnect works
+
Multiple game servers work
+
Redis HA works
+
MongoDB HA/backup works
+
Kafka works
+
Wallet ledger correct
+
Payment webhooks secure
+
KYC works
+
Responsible gambling controls work
+
Fraud controls work
+
Audit works
+
Monitoring works
+
400k load test passes target
+
Failure tests pass
+
Security test passes
+
Disaster recovery tested
+
Australian legal/compliance approval obtained
```

------------------------------------------------------------------------

# 73. RECOMMENDED IMPLEMENTATION ORDER FOR OUR FUTURE WORK

When implementing this project step-by-step, use this exact sequence:

``` text
STEP 01
Project setup

STEP 02
Game domain model

STEP 03
Card/Deck

STEP 04
GameState

STEP 05
Rules

STEP 06
GameEngine

STEP 07
Game tests

STEP 08
BotEngine

STEP 09
Bot tests

STEP 10
WebSocket

STEP 11
React game screen

STEP 12
Reconnect/resync

STEP 13
MongoDB

STEP 14
Redis

STEP 15
Matchmaking

STEP 16
Multiple game servers

STEP 17
Kafka

STEP 18
Authentication/security

STEP 19
Admin

STEP 20
Observability

STEP 21
Load testing

STEP 22
Wallet

STEP 23
Payments

STEP 24
KYC/compliance

STEP 25
Fraud/risk

STEP 26
Production infrastructure

STEP 27
Security testing

STEP 28
Disaster recovery

STEP 29
Legal release gate

STEP 30
Production launch
```

**Do not jump from STEP 01 directly to STEP 23.**

------------------------------------------------------------------------

# 74. THE FIRST IMPLEMENTATION SPRINT

The first sprint should produce only this:

``` text
React
   ↓
WebSocket
   ↓
Spring Boot
   ↓
GameEngine
   ↓
GameState
```

with:

``` text
4 players
+
deck
+
deal
+
draw
+
discard
+
turn
+
declare
+
score
+
game finish
```

Then add:

``` text
Bot
```

Then:

``` text
MongoDB
```

Then:

``` text
Redis
```

Then:

``` text
multiple game servers
```

Then:

``` text
Kafka
```

Then:

``` text
wallet/compliance
```

That is the safest engineering order.

------------------------------------------------------------------------

# 75. FINAL REFERENCE ARCHITECTURE

``` text
                               USERS
                                 │
                                 ▼
                     ┌─────────────────────┐
                     │ React + TypeScript  │
                     └──────────┬──────────┘
                                │
                         HTTPS / WSS
                                │
                     ┌──────────▼──────────┐
                     │ CDN + WAF + DDoS    │
                     └──────────┬──────────┘
                                │
              ┌─────────────────┴──────────────────┐
              │                                    │
              ▼                                    ▼
      ┌───────────────┐                    ┌───────────────┐
      │ REST LB       │                    │ WSS LB        │
      └───────┬───────┘                    └───────┬───────┘
              │                                    │
              ▼                                    ▼
      ┌─────────────────┐                ┌────────────────────┐
      │ Spring Boot API │                │ Game Server Cluster │
      └────────┬────────┘                └──────────┬─────────┘
               │                                    │
       ┌───────┼────────┐                   ┌──────┴──────┐
       │       │        │                   │             │
       ▼       ▼        ▼                   ▼             ▼
    MongoDB  Redis    Kafka             Human         AI Bot
       │       │        │                   \             /
       │       │        ├── History          \           /
       │       │        ├── Audit             ▼         ▼
       │       │        ├── Analytics       GAME ENGINE
       │       │        └── Notifications       │
       │       │                                │
       │       └──── table routing ─────────────┘
       │
       ▼
    Backups

                      MONEY SYSTEM
                      ─────────────
                    Payment Provider
                           │
                           ▼
                     Wallet Service
                           │
                           ▼
                    MongoDB Wallet + Ledger
                           │
                           ▼
                      Settlement
```

------------------------------------------------------------------------

# 76. ENGINEERING PRINCIPLES

1.  **Server authoritative.**
2.  **Game state stays hot in game-server memory.**
3.  **MongoDB stores persistent application/game data.**
4.  **MongoDB handles the wallet and immutable financial transaction ledger.**
5.  **Redis handles coordination and short-lived state.**
6.  **Kafka handles asynchronous events.**
7.  **WebSocket handles real-time gameplay.**
8.  **Bots use the same game engine as humans.**
9.  **Bots are clearly identified as AI.**
10. **Never trust the client with game results.**
11. **Never trust the browser for payment confirmation.**
12. **Every important financial operation is idempotent.**
13. **Every important game has an audit trail.**
14. **Every active game has an owner server.**
15. **Every game event has a sequence number.**
16. **Every deployment must support graceful draining/recovery.**
17. **Every capacity number must come from load testing.**
18. **Real-money Australia production is blocked until legal/compliance
    approval.**
19. **Security and compliance are architecture requirements, not
    post-launch tasks.**
20. **Implement one phase completely before moving to the next.**

------------------------------------------------------------------------

# 77. START HERE

## Step 1 --- Create the repository

Create:

``` text
rummy-platform/
├── frontend/
├── backend/
├── shared/
├── infrastructure/
├── load-test/
├── deployment/
└── docs/
```

## Step 2 --- Create Java project

Use:

``` text
Java 21+
Spring Boot
Maven/Gradle
```

## Step 3 --- Create Game Engine module

Implement:

``` text
Card
Deck
PlayerState
GameState
GameStatus
GameCommand
GameEvent
GameEngine
RummyRules
ScoreCalculator
```

## Step 4 --- Write tests

Do not continue until the basic game is deterministic and tested.

## Step 5 --- Add BotEngine

Bot must produce normal `GameCommand` objects.

## Step 6 --- Add WebSocket

Connect:

``` text
React
 ↓
WSS
 ↓
Spring Boot
 ↓
GameEngine
```

## Step 7 --- Add MongoDB

Persist:

``` text
users
games
game_results
game_events
```

## Step 8 --- Add Redis

Implement:

``` text
session
presence
matchmaking
table routing
```

## Step 9 --- Scale game servers

Move from:

``` text
1 server
```

to:

``` text
multiple game servers
```

## Step 10 --- Load test

Start:

``` text
1k
5k
10k
25k
50k
100k
```

Only after optimization continue to:

``` text
200k
300k
400k
```

## Step 11 --- Add Kafka

Only once the core gameplay path is stable.

## Step 12 --- Add money system

Only after legal/compliance approval.

## Step 13 --- Production hardening

Security + monitoring + backups + DR + failure testing.

## Step 14 --- Legal release gate

No Australian real-money launch before written approval.

------------------------------------------------------------------------

# END

This README is intended to be the **master implementation plan**. Each
numbered phase should become a separate development milestone,
pull-request group, and test gate. Do not treat the whole document as
one coding task.
