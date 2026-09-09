# Bloeco Agent Guide

This file is the first technical reference for coding agents working in this repository. Read it together with `README.md`, `docs/architecture/central-bank-design.md`, and `docs/third-party-economy-integration.md` before changing money flows.

## Product boundary

Bloeco is the mandatory foundation of the Bloeco economy-plugin family and the single authoritative central-bank ledger for Paper 1.21.11. Every later Bloeco commerce, securities, auction, quest-reward, land, or minigame plugin must integrate with this project for accounts and settlement. Bloeco owns currency supply, Treasury funds, player balances, taxes, fees, journal entries, integrity checks, institution accounts, and the state-owned bank.

Bloeco does not implement shops, auctions, securities, quests, item delivery, product pricing, or inventory management. It currently does not depend on or register Vault. A family plugin keeps only its own orders, products, positions, or other business state, while Bloeco remains the sole owner of spendable balances and monetary journals. Integration uses the versioned native API when that API is released.

## Technology stack

- Java 21.
- Paper API `1.21.11-R0.1-SNAPSHOT`.
- Gradle 8 with Kotlin build scripts and the Shadow plugin.
- MySQL Connector/J `9.1.0` with HikariCP `6.2.1`; MySQL 8.4/InnoDB is the production authority.
- SQLite JDBC `3.47.1.0` remains an explicit development/test/import backend, never an automatic failover.
- JUnit 5 and MockBukkit for automated tests.
- Lettuce `6.5.0.RELEASE` connects to Redis 7 for rebuildable cache and Redis Stream notifications. Redis must never be a balance or journal source of truth.

## Source layout

- `domain/`: immutable money, account, tax, posting, and journal rules. It must not depend on Paper, JDBC, or GUI classes.
- `domain/banking/`: bank policy and immutable customer/balance-sheet snapshots.
- `application/banking/`: the asynchronous banking boundary and persistence port.
- `application/`: central-bank, payment, tax, query, result, and asynchronous use cases. `LedgerStore` is the persistence port.
- `storage/mysql/`: MySQL/InnoDB schema, pooled JDBC commits, integrity verification, and the outbox table.
- `storage/sqlite/`: SQLite schema, atomic commits, integrity verification, and legacy migration for local/import use.
- `storage/redis/`: optional cache and Stream bridge; connection failure is a non-authoritative degradation.
- `paper/`: plugin lifecycle adapters, `/eco`, `/pay`, permissions, GUI screens, and player-facing formatting.
- `src/main/resources/`: `plugin.yml` and default configuration.
- `src/test/`: domain, storage, application, command, and GUI regression tests.
- `docs/architecture/central-bank-design.md`: authoritative design and supply model.
- `docs/third-party-economy-integration.md`: external integration contract and failure semantics.

## Monetary invariants

These constraints are mandatory:

1. Store every amount as integer minor units in `long`. Never use `double` for settlement.
2. Every committed journal must balance to zero. Money movement uses double-entry postings.
3. Only an approved `ISSUE` journal may increase net supply, and issued money enters Treasury first.
4. Only a `RETIRE` journal may reduce net supply.
5. Player grants and positive balance adjustments debit existing Treasury funds. They must fail when Treasury is insufficient.
6. Taxes and fees move existing funds to fiscal accounts; they do not destroy currency by themselves.
7. Never edit or delete committed journals. Correct mistakes by appending a reversal.
8. Every retryable write uses a stable `clientId + idempotencyKey`. Same key and same request returns the original result; conflicting content fails closed.
9. The configured genesis Treasury issue runs only against an empty journal. Player starter funds come from Treasury and are permanently limited to one allocation per player UUID.
10. A failed integrity check makes the application read-only. Do not introduce a fallback balance store.
11. Bank capital is a real Treasury-to-`bank:cash` transfer, deposits only move wallet cash, and loans only disburse existing `bank:cash`. Never implement a bank action by calling issuance.
12. Bank writes require stable idempotency keys. Reserve-ratio and cash checks are commit-time rules, not GUI estimates.

## Concurrency and Paper rules

`AsyncEconomyFacade` owns one serial economy worker. JDBC and ledger writes stay off the Paper main thread. Bukkit inventory, player, and message operations must return to the Paper scheduler before touching Bukkit state. Do not block the main thread waiting on database futures.

## Commands and GUI

The public commands are exactly:

- `/eco`: opens the GUI economy centre.
- `/pay <online-player> <positive-integer>`: quick player transfer with completion.

Do not add public administration subcommands. Add administration flows to the permission-gated GUI and keep a Back and Main Menu button on every child screen. Permissions are the `bloeco.role.*` nodes declared in `plugin.yml`.

Player banking is under `/eco -> 国有银行`; bank policy is under `/eco -> 中央银行管理 -> 银行管理`. The banker permission is `bloeco.role.banker` and does not imply monetary or tax authority. Banking tables are owned exclusively by Bloeco. External plugins must not create deposits or loans by writing those tables.

The bank home screen must keep `我的银行账户` clickable and show the current annual loan rate before a player borrows. Credit grade and the A-D criteria belong only on the account-details screen, not on the bank home screen. Transfer, deposit, withdrawal, and loan amount screens provide the `100.00`, `1000.00`, and `10000.00` presets plus a custom-amount button. Deposit and withdrawal also expose `全部存入` and `全部取出`; resolve those amounts inside the committed bank transaction from the current wallet or deposit balance, never from a stale GUI snapshot. Custom amounts are entered through a cancelled `AsyncChatEvent`, accept at most two decimal places, support `取消`/`cancel`, and expire after 60 seconds. Never expose internal phrases such as “中央总账”, “中央清算”, or “由 Bloeco 结算” in ordinary player status messages; those terms are reserved for permission-gated audit views and technical documentation.

The default loan contract is a 3.2% annual fixed rate (`320` basis points) for 7 days. Interest is `principal * annualBasisPoints * termDays / 3,650,000` in minor units, rounded down with a minimum of one minor unit for a positive interest-bearing loan. Persist the contracted rate on each loan so later policy changes do not rewrite existing debt.

## Third-party integration

The public native API is versioned separately from the internal store. Until a compatible API artifact is published, another plugin must not read Bloeco MySQL/SQLite tables, invoke internal classes by reflection, or use Redis as a substitute. See `docs/architecture/storage-v2-mysql-redis.md` and `docs/third-party-economy-integration.md`.

When implementing the native API, keep it asynchronous and versioned. A plugin registers a stable institution `clientId`; Bloeco creates and owns its institution accounts. Settlement requests carry a stable idempotency key and return a permanent journal ID. A shop marks an order paid and delivers items only after Bloeco returns a committed receipt. Timeouts remain result-unknown and must be queried or retried with the same key. See `docs/third-party-economy-integration.md` for the planned request lifecycle, refund rules, and error contract.

Every external settlement must separate these values:

- `businessType`: stable ASCII machine identifier such as `shop.purchase`, `stock.buy`, or `quest.reward`.
- `businessReference`: private order/trade/reward reference used for audit and compensation.
- `displayMemo`: required UTF-8 plain Chinese player-facing sentence, preferably 1-80 characters and never over the journal limit of 256 characters.
- `idempotencyKey`: stable retry identity; never show it as the bill explanation.

Bloeco stores `displayMemo` as an immutable snapshot and does not translate arbitrary third-party text. Preserve proper names when needed, but write the action in Chinese: `在 Bloeco 商店购买 16 个钻石`, `卖出 10 股矿业指数`, or `完成“初来乍到”任务奖励`. Never place UUIDs, stack traces, secrets, formatting control codes, or raw JSON in `displayMemo`. Built-in Bloeco journal memos belong in `application/JournalMemos`; add Chinese copy and regression tests there instead of embedding English sentences in command or GUI adapters.

## Development and release checks

Run:

```text
./gradlew clean test shadowJar
```

On Windows use `gradlew.bat`. Add a failing regression test before production behavior changes, then run the focused test and the full suite. Inspect the shaded JAR's `plugin.yml` and `config.yml` before release.

Keep GitHub source clean: commit source, tests, Gradle files, README, and product/technical documentation only. Never commit build output, Paper runtime data, worlds, logs, databases, caches, IDE state, local plans, credentials, or generated QA artifacts. Release JARs belong in GitHub Releases, not in the source tree.
