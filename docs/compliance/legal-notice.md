# Platform Integrity & Operational Notice

## 1. Engineering Project Scope

This platform and its codebase constitute a **software engineering and simulation implementation** of a distributed, real-time multiplayer card gaming engine.

---

## 2. Fair-Play & Server-Authoritative Architecture

- All game table state transitions, card distributions, and declarations are validated server-side by the `TableActor` model.
- Hand state is projected to clients using zero-knowledge filtering, ensuring no player client can access opponents' concealed cards or undealt deck cards.
- Multi-accounting, chip dumping, and IP collusion are monitored by automated real-time fraud detection pipelines.

---

## 3. Virtual Free-Play Mode

Development and testing are conducted strictly in **Free-Play (Virtual Points) Mode**:
1. Balances operate with non-monetary virtual tokens (`TOKENS`).
2. Complimentary daily faucet allocations are available for testing.
3. No real-world currency is accepted, wagered, or paid out.

---

## 4. Responsible Gaming

The platform provides integrated player protection controls, including session time limits, token loss limits, automated reality checks, cool-off periods, and account self-exclusion.
