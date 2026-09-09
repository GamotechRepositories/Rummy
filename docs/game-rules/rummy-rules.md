# Game Rules Specification — Indian 13-Card & Points Rummy (Phase 1)

## 1. Overview & Identity

```text
Ruleset ID: POINTS_13
Family: Indian Rummy
Players: 2 to 6
Cards per Player: 13
Deck Configuration: 2 standard 52-card decks + 2 printed jokers (106 cards total)
Deals: 1 (Points Rummy)
Primary Objective: Arrange all 13 cards into valid sequences and sets and declare
```

---

## 2. Card Values & Points

| Card Rank | Point Value in Unformed Groups |
|-----------|--------------------------------|
| Ace (A)   | 10                             |
| King (K)  | 10                             |
| Queen (Q) | 10                             |
| Jack (J)  | 10                             |
| 10        | 10                             |
| 2 – 9     | Face Value (2–9 points)        |
| Jokers    | 0                              |

---

## 3. Valid Groupings

### 3.1 Pure Sequence (Mandatory)
- 3 or more consecutive cards of the same suit **without** any joker substitution.
- *Example:* `4♥ 5♥ 6♥` or `10♠ J♠ Q♠ K♠`.
- At least **one pure sequence** is required for any valid declaration.

### 3.2 Impure Sequence
- 3 or more consecutive cards of the same suit with one or more jokers substituting for missing cards.
- *Example:* `4♥ 5♥ [Joker] 7♥`.

### 3.3 Sets
- 3 or 4 cards of the exact same rank from **different suits**.
- *Example:* `8♠ 8♥ 8♦` or `K♠ K♥ K♦ K♣`.
- Duplicate cards of the exact same suit cannot appear in the same set.
- Impure sets may use jokers to substitute for missing suits.

---

## 4. Winning Declaration Requirements

To declare validly:
1. Total cards must equal **13** (plus 1 discarded to finish slot).
2. Minimum of **2 sequences** required.
3. Minimum of **1 pure sequence** required.
4. **All cards** must be grouped into valid sequences or sets.
5. The winning declarer receives **0 penalty points**.

---

## 5. Drop & Timeout Penalties

| Action | Points Penalty |
|--------|----------------|
| First Drop (leaves before first turn completed) | 20 points |
| Middle Drop (leaves after completing at least 1 turn) | 40 points |
| Auto Drop (3 consecutive turn timeouts) | 40 points |
| Invalid / Wrong Declaration | 80 points |
| Maximum Loss Cap | 80 points |

---

## 6. Phase Roadmap for Other Variants

- **Phase 1:** Indian 13-Card & Points Rummy (`POINTS_13`)
- **Phase 2:** Pool 101 & Pool 201 (`POOL_101_13`, `POOL_201_13`)
- **Phase 3:** Deals Rummy (`DEALS_13`)
- **Phase 4:** 21-Card Rummy (`INDIAN_21`)
- **Phase 5:** Gin Rummy (`GIN_RUMMY`)
- **Phase 6:** 500 Rummy (`RUMMY_500`)
- **Phase 7:** Kalooki (`KALOOKI`)
- **Phase 8:** Canasta (`CANASTA_CLASSIC`)
- **Phase 9:** Tournament Layer
