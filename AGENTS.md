# Bloeco Agent Guide

This file is the first technical reference for coding agents working in this repository. Read it together with `README.md`, `docs/architecture/central-bank-design.md`, and `docs/third-party-economy-integration.md` before changing money flows.

## Product boundary

Bloeco is the mandatory foundation of the Bloeco economy-plugin family and the single authoritative central-bank ledger for Paper 1.21.11. Every later Bloeco commerce, securities, auction, quest-reward, land, or minigame plugin must integrate with this project for accounts and settlement. Bloeco owns currency supply, Treasury funds, player balances, taxes, fees, journal entries, integrity checks, and institution accounts.

Bloeco does not implement shops, auctions, securities, quests, item delivery, product pricing, or inventory management. It currently does not depend on or register Vault. A family plugin keeps only its own orders, products, positions, or other business state, while Bloeco remains the sole owner of spendable balances and monetary journals. Integration uses the versioned native API when that API is released.

## Technology stack

- Java 21.
- Paper API `1.21.11-R0.1-SNAPSHOT`.
- Gradle 8 with Kotlin build scripts and the Shadow plugin.
- SQLite JDBC `3.47.1.0`; SQLite is the only implemented authoritative backend.
- JUnit 5 and MockBukkit for automated tests.
- MySQL is a planned authoritative backend behind the same `LedgerStore` boundary.
- Redis is planned only for rebuildable cache invalidation and cross-server notifications. Redis must never be a balance or journal source of truth.

## Source layout

- `domain/`: immutable money, account, tax, posting, and journal rules. It must not depend on Paper, JDBC, or GUI classes.
- `application/`: central-bank, payment, tax, query, result, and asynchronous use cases. `LedgerStore` is the persistence port.
- `storage/sqlite/`: SQLite schema, atomic commits, integrity verification, and legacy migration.
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

## Concurrency and Paper rules

`AsyncEconomyFacade` owns one serial economy worker. JDBC and ledger writes stay off the Paper main thread. Bukkit inventory, player, and message operations must return to the Paper scheduler before touching Bukkit state. Do not block the main thread waiting on database futures.

## Commands and GUI

The public commands are exactly:

- `/eco`: opens the GUI economy centre.
- `/pay <online-player> <positive-integer>`: quick player transfer with completion.

Do not add public administration subcommands. Add administration flows to the permission-gated GUI and keep a Back and Main Menu button on every child screen. Permissions are the `bloeco.role.*` nodes declared in `plugin.yml`.

## Third-party integration

The public native API is not released in Phase 1. Until it exists, another plugin must not read Bloeco SQLite, invoke internal classes by reflection, or use Vault as a substitute.

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
