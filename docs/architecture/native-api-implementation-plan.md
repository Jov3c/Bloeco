# Bloeco Native Economy API 1.0 Implementation Plan

> **文档状态：** 实施记录（已完成）。本文保留 API 1.0 的目标、验收关注点和代码映射，方便维护者追溯设计；它不是运行时兼容合同，也不是当前待办清单。对外接入以 [`docs/third-party-economy-integration.md`](../third-party-economy-integration.md) 为准。

**Goal:** Embed a stable Vault-like Bloeco economy API in the main plugin so Bloeco-family commerce, land, quest, auction, and securities plugins can register institutions and perform conserved, auditable settlements without using internal classes or databases.

**Architecture:** Public contracts live only in `com.blocke.bloeco.api` inside the existing single JAR. A Paper `ServicesManager` provider binds each consuming `Plugin` to a capability-limited institution client. Internal application and SQL adapters perform institution registration, charge/pay/refund, reservation/capture/release, idempotent recovery, journals, balances, and Outbox writes atomically.

**Tech Stack:** Java 21, Paper 1.21.11 ServicesManager, Gradle 8 Kotlin DSL, JUnit 5, MockBukkit, MySQL 8.4/InnoDB, SQLite JDBC, HikariCP, Redis/Lettuce Outbox notifications.

**设计基线：** [`docs/architecture/native-api-design.md`](native-api-design.md)<br>
**实现状态：** 公共 API、机构注册、结算、退款、预授权、MySQL V007、Paper Provider、示例 Consumer 和兼容性测试均已落地；主插件版本为 `1.5.0-SNAPSHOT`，公开 API 版本为 `1.0.0`。

## Global Constraints

- Ship one `Bloeco.jar`; do not create or publish a separate API module or API JAR.
- Public API types use `com.blocke.bloeco.api` and must not expose `com.blocke.centraleconomy` types.
- Consumer plugins use Bloeco as `compileOnly`, declare `depend: [Bloeco]`, and never shade Bloeco classes.
- Every public write returns `CompletionStage<EconomyResult<T>>` and requires a stable idempotency key.
- All amounts use integer minor units in `long`; no settlement path accepts `double`.
- No API operation may issue money, set arbitrary balances, submit arbitrary postings, or access another institution's accounts.
- MySQL is the production authority; Redis is never a balance or settlement authority.
- All money writes preserve double-entry balance and commit journal, balances, business record, audit metadata, and Outbox atomically.
- Public API 1.0 remains independent from the plugin version, which becomes `1.5.0-SNAPSHOT` during this work.
- Vault, multi-currency, HTTP APIs, and third-party bank operations remain out of scope.

## Review Focus

- A consumer accidentally shades API classes: plugin discovery must fail clearly instead of silently returning an incompatible provider.
- A plugin reconnects with an existing institution ID but a different plugin identity: reject it as `UNAUTHORIZED`, without changing the stored owner.
- A request times out after MySQL commit: replaying the same idempotency key must return the original committed receipt without moving money again.
- Two concurrent captures or refunds race against the same original operation: at most the remaining amount commits; the loser receives a stable failure.
- A reservation expires between GUI/business validation and capture: capture must fail and leave both total and reserved balances consistent.

---

### Task 1: Public API contract and validation

**Files:**
- Create: `src/main/java/com/blocke/bloeco/api/BloecoApi.java`
- Create: `src/main/java/com/blocke/bloeco/api/ApiVersion.java`
- Create: `src/main/java/com/blocke/bloeco/api/EconomyResult.java`
- Create: `src/main/java/com/blocke/bloeco/api/EconomyError.java`
- Create: `src/main/java/com/blocke/bloeco/api/MoneyAmount.java`
- Create: `src/main/java/com/blocke/bloeco/api/InstitutionDescriptor.java`
- Create: `src/main/java/com/blocke/bloeco/api/InstitutionCapability.java`
- Create: `src/main/java/com/blocke/bloeco/api/InstitutionClient.java`
- Create: `src/main/java/com/blocke/bloeco/api/InstitutionAccount.java`
- Create: `src/main/java/com/blocke/bloeco/api/OperationContext.java`
- Create: `src/main/java/com/blocke/bloeco/api/SettlementKind.java`
- Create: `src/main/java/com/blocke/bloeco/api/SettlementReceipt.java`
- Create: `src/main/java/com/blocke/bloeco/api/ReservationReceipt.java`
- Test: `src/test/java/com/blocke/bloeco/api/PublicApiContractTest.java`

**Interfaces:**
- Consumes: Java standard library and Paper `Plugin` only.
- Produces: the following public boundary (supporting records/enums carry only public API types):

```java
interface BloecoApi {
    ApiVersion version();
    CompletionStage<EconomyResult<InstitutionClient>> connect(
            Plugin plugin, InstitutionDescriptor descriptor);
}

interface InstitutionClient {
    CompletionStage<EconomyResult<MoneyAmount>> walletBalance(UUID playerId);
    CompletionStage<EconomyResult<MoneyAmount>> balance(InstitutionAccount account);
    CompletionStage<EconomyResult<SettlementReceipt>> chargePlayer(
            UUID playerId, InstitutionAccount account, MoneyAmount amount,
            SettlementKind kind, OperationContext context);
    CompletionStage<EconomyResult<SettlementReceipt>> payPlayer(
            InstitutionAccount account, UUID playerId, MoneyAmount amount,
            SettlementKind kind, OperationContext context);
    CompletionStage<EconomyResult<SettlementReceipt>> refund(
            UUID originalOperationId, MoneyAmount amount, OperationContext context);
    CompletionStage<EconomyResult<ReservationReceipt>> reservePlayerFunds(
            UUID playerId, MoneyAmount amount, Instant expiresAt, OperationContext context);
    CompletionStage<EconomyResult<SettlementReceipt>> captureReservation(
            UUID reservationId, InstitutionAccount account, OperationContext context);
    CompletionStage<EconomyResult<ReservationReceipt>> releaseReservation(
            UUID reservationId, OperationContext context);
    CompletionStage<EconomyResult<Optional<SettlementReceipt>>> findOperation(String idempotencyKey);
    CompletionStage<EconomyResult<List<SettlementReceipt>>> recentTransactions(
            InstitutionAccount account, int limit);
}
```

- [ ] **Step 1: Write failing public-contract tests**

Add tests proving safe identifier normalization, positive `MoneyAmount`, non-blank Chinese-capable `displayMemo`, 256-character hard limit (documentation recommends 1–80), stable API major version `1`, immutable capability sets, and rejection of blank/unsafe idempotency keys.

- [ ] **Step 2: Run the contract tests and verify RED**

Run: `gradlew.bat test --tests com.blocke.bloeco.api.PublicApiContractTest`
Expected: compilation fails because `com.blocke.bloeco.api` does not exist.

- [ ] **Step 3: Implement the minimal public contracts**

Use records and enums where possible. `BloecoApi.connect` takes the real Paper `Plugin`. `InstitutionClient` exposes handles, never raw internal account IDs. Map all stable failures through `EconomyError`, including `CAPABILITY_DENIED`, `INTEGRITY_READ_ONLY`, and `RESULT_UNKNOWN`.

- [ ] **Step 4: Run the contract tests and full existing suite**

Run: `gradlew.bat test --tests com.blocke.bloeco.api.PublicApiContractTest`
Expected: PASS.

- [ ] **Step 5: Commit**

Commit message: `feat: add embedded Bloeco economy API contract`

### Task 2: Institution registry and capability binding

**Files:**
- Create: `src/main/java/com/blocke/centraleconomy/application/integration/InstitutionRecord.java`
- Create: `src/main/java/com/blocke/centraleconomy/application/integration/EcosystemStore.java`
- Create: `src/main/java/com/blocke/centraleconomy/application/integration/InstitutionService.java`
- Create: `src/main/java/com/blocke/centraleconomy/storage/sqlite/SqliteEcosystemStore.java`
- Modify: `src/main/java/com/blocke/centraleconomy/storage/sqlite/SqliteSchema.java`
- Test: `src/test/java/com/blocke/centraleconomy/application/integration/InstitutionServiceTest.java`
- Test: `src/test/java/com/blocke/centraleconomy/storage/sqlite/SqliteEcosystemStoreTest.java`

**Interfaces:**
- Consumes: `InstitutionDescriptor`, `InstitutionCapability`, `AccountId.plugin(clientId, purpose)`.
- Produces: idempotent `registerOrResume(pluginName, pluginMainClass, descriptor)`, default `operating` account, declared purpose accounts, persisted immutable owner identity, status, capabilities, and API major version.

- [ ] **Step 1: Write failing institution behavior tests**

Cover first registration, same-plugin restart returning the same institution/accounts, different plugin identity rejection, safe account purposes, immutable client ID, capability persistence, and no duplicate balance rows.

- [ ] **Step 2: Run focused tests and verify RED**

Run: `gradlew.bat test --tests '*InstitutionServiceTest' --tests '*SqliteEcosystemStoreTest'`
Expected: compilation fails because the institution application/store types do not exist.

- [ ] **Step 3: Add SQLite institution schema and minimal service/store**

Create `institutions`, `institution_capabilities`, and institution-owned `accounts` records without changing existing account ownership. Registration and account creation execute in one SQLite transaction.

- [ ] **Step 4: Run focused tests and verify GREEN**

Run: `gradlew.bat test --tests '*InstitutionServiceTest' --tests '*SqliteEcosystemStoreTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

Commit message: `feat: add institution registry and account binding`

### Task 3: Conserved charge, payout, lookup, and refund

**Files:**
- Create: `src/main/java/com/blocke/centraleconomy/application/integration/InstitutionSettlementService.java`
- Create: `src/main/java/com/blocke/centraleconomy/application/integration/SettlementRecord.java`
- Modify: `src/main/java/com/blocke/centraleconomy/application/integration/EcosystemStore.java`
- Modify: `src/main/java/com/blocke/centraleconomy/storage/sqlite/SqliteEcosystemStore.java`
- Modify: `src/main/java/com/blocke/centraleconomy/storage/sqlite/SqliteSchema.java`
- Modify: `src/main/java/com/blocke/centraleconomy/domain/tax/TaxCategory.java`
- Modify: `src/main/java/com/blocke/centraleconomy/application/TaxRuleService.java`
- Test: `src/test/java/com/blocke/centraleconomy/application/integration/InstitutionSettlementServiceTest.java`
- Test: `src/test/java/com/blocke/centraleconomy/storage/sqlite/SqliteEcosystemSettlementTest.java`

**Interfaces:**
- Consumes: registered `InstitutionRecord`, account handles, `OperationContext`, existing `TaxRuleService`, immutable journals and postings.
- Produces: atomic `chargePlayer`, `payPlayer`, `refund`, `findOperation`, `recentTransactions`, and permanent `SettlementReceipt` with gross, tax, fee, net, journal ID, operation ID, and committed timestamp.

- [ ] **Step 1: Write failing settlement tests**

Cover player purchase debit plus centrally calculated consumption tax, institution payout, insufficient player/institution funds, foreign account denial, same-key replay, conflicting-key rejection, partial/full refund, over-refund rejection, concurrent duplicate refund, Chinese memo persistence, and full money-supply conservation.

- [ ] **Step 2: Run focused tests and verify RED**

Run: `gradlew.bat test --tests '*InstitutionSettlementServiceTest' --tests '*SqliteEcosystemSettlementTest'`
Expected: compilation fails because settlement behavior is absent.

- [ ] **Step 3: Implement settlement rules and SQLite atomic commits**

Add `PLUGIN_CONSUMPTION` as a versioned central tax category with a default 5% basis-point rule. `PURCHASE` and `SERVICE_FEE` charge tax in addition to principal; `PAYOUT` and `REWARD` move only institution-funded principal. Refunds reference an original charge and return principal plus refundable tax according to its stored snapshot.

- [ ] **Step 4: Run focused tests and verify GREEN**

Run: `gradlew.bat test --tests '*InstitutionSettlementServiceTest' --tests '*SqliteEcosystemSettlementTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

Commit message: `feat: add institution settlement and refunds`

### Task 4: Player fund reservations

**Files:**
- Create: `src/main/java/com/blocke/centraleconomy/application/integration/ReservationRecord.java`
- Create: `src/main/java/com/blocke/centraleconomy/application/integration/FundReservationService.java`
- Modify: `src/main/java/com/blocke/centraleconomy/application/integration/EcosystemStore.java`
- Modify: `src/main/java/com/blocke/centraleconomy/storage/sqlite/SqliteEcosystemStore.java`
- Modify: `src/main/java/com/blocke/centraleconomy/storage/sqlite/SqliteSchema.java`
- Test: `src/test/java/com/blocke/centraleconomy/application/integration/FundReservationServiceTest.java`
- Test: `src/test/java/com/blocke/centraleconomy/storage/sqlite/SqliteReservationTest.java`

**Interfaces:**
- Consumes: institution capability `RESERVE_PLAYER_FUNDS`, player total balance, `reserved_minor`, target institution account, expiration timestamp, and stable operation context.
- Produces: `reserve`, `capture`, `release`, `reservation`, statuses `ACTIVE/CAPTURED/RELEASED/EXPIRED`, and invariant `available = total - reserved`.

- [ ] **Step 1: Write failing reservation tests**

Cover available-balance enforcement, same-key replay, expiration before capture, idempotent release, one-time capture, capture versus release race, two concurrent reservations against one wallet, and reserved-total reconstruction.

- [ ] **Step 2: Run focused tests and verify RED**

Run: `gradlew.bat test --tests '*FundReservationServiceTest' --tests '*SqliteReservationTest'`
Expected: compilation fails because reservation behavior is absent.

- [ ] **Step 3: Implement reservation persistence and transitions**

Add `reserved_minor` to SQLite balances, update debit checks to use available balance, and commit each transition with its operation record. Expired reservations release their amount exactly once before returning the final status.

- [ ] **Step 4: Run focused tests and verify GREEN**

Run: `gradlew.bat test --tests '*FundReservationServiceTest' --tests '*SqliteReservationTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

Commit message: `feat: add idempotent player fund reservations`

### Task 5: MySQL V007 and concurrent production store

**Files:**
- Modify: `src/main/java/com/blocke/centraleconomy/storage/mysql/MySqlMigrations.java`
- Modify: `src/main/java/com/blocke/centraleconomy/storage/mysql/MySqlSchema.java`
- Create: `src/main/java/com/blocke/centraleconomy/storage/mysql/MySqlEcosystemStore.java`
- Create: `src/main/java/com/blocke/centraleconomy/storage/mysql/repository/InstitutionRepository.java`
- Create: `src/main/java/com/blocke/centraleconomy/storage/mysql/repository/SettlementRepository.java`
- Create: `src/main/java/com/blocke/centraleconomy/storage/mysql/repository/ReservationRepository.java`
- Modify: `src/main/java/com/blocke/centraleconomy/storage/mysql/repository/BalanceRepository.java`
- Test: `src/test/java/com/blocke/centraleconomy/storage/mysql/MySqlEcosystemStoreIntegrationTest.java`
- Test: `src/test/java/com/blocke/centraleconomy/storage/mysql/MySqlEcosystemConcurrencyTest.java`
- Test: `src/test/java/com/blocke/centraleconomy/storage/mysql/MigrationRunnerTest.java`

**Interfaces:**
- Consumes: `EcosystemStore`, shared `BloecoDataSource`, `MySqlTransactionManager`, ordered balance locking, ledger and Outbox repositories.
- Produces: MySQL behavior parity with SQLite and schema version `7` without modifying V001–V006.

- [ ] **Step 1: Write failing migration and MySQL contract tests**

Assert V006-to-V007 upgrade, preserved balances/history, `reserved_minor` default zero, institution ownership, one-key-one-result, rollback on injected Outbox failure, ordered locks, concurrent same-wallet debit protection, reservation races, and duplicate refund protection.

- [ ] **Step 2: Run gated MySQL tests and verify RED**

Run with the isolated MySQL test environment: `gradlew.bat test --tests '*MySqlEcosystem*' --tests '*MigrationRunnerTest'`
Expected: schema/version or missing-class failure before implementation.

- [ ] **Step 3: Implement V007 repositories and store**

Use one short transaction per write. Lock all involved accounts in one sorted `FOR UPDATE`, validate available balances after locking, increment balance versions, then insert journals, operation state, audit metadata, and Outbox before commit.

- [ ] **Step 4: Run gated MySQL tests and stress cases**

Run: `gradlew.bat test --tests '*MySqlEcosystem*' --tests '*MigrationRunnerTest'`
Expected: PASS with zero duplicate journals, negative available balances, or stuck reservations.

- [ ] **Step 5: Commit**

Commit message: `feat: implement MySQL ecosystem settlements`

### Task 6: Embedded provider, async boundary, and lifecycle

**Files:**
- Create: `src/main/java/com/blocke/centraleconomy/application/integration/AsyncInstitutionFacade.java`
- Create: `src/main/java/com/blocke/centraleconomy/paper/BloecoApiProvider.java`
- Modify: `src/main/java/com/blocke/centraleconomy/paper/BloecoRuntime.java`
- Modify: `src/main/java/com/blocke/centraleconomy/CentralEconomyPlugin.java`
- Modify: `src/main/resources/config.yml`
- Test: `src/test/java/com/blocke/centraleconomy/application/integration/AsyncInstitutionFacadeTest.java`
- Test: `src/test/java/com/blocke/centraleconomy/paper/BloecoApiProviderTest.java`
- Test: `src/test/java/com/blocke/centraleconomy/PluginBootstrapTest.java`

**Interfaces:**
- Consumes: public `BloecoApi`, `InstitutionClient`, backend `EcosystemStore`, Runtime readiness and read-only state.
- Produces: bounded async API execution, Provider registration only after readiness, Provider unregistration before shutdown, and capability-bound client adapters.

- [ ] **Step 1: Write failing async/provider tests**

Cover API discovery after startup, absence before readiness, clean unregister, closed/full queue returning `STORAGE_UNAVAILABLE`, no JDBC on Paper main thread, API major negotiation, consumer class-loader mismatch diagnostic, read-only write rejection, and callback result preservation.

- [ ] **Step 2: Run focused tests and verify RED**

Run: `gradlew.bat test --tests '*AsyncInstitutionFacadeTest' --tests '*BloecoApiProviderTest' --tests '*PluginBootstrapTest'`
Expected: compilation or discovery failure because the Provider is absent.

- [ ] **Step 3: Implement provider and lifecycle wiring**

Use one worker for SQLite and a configurable bounded worker pool for MySQL. Register `BloecoApi.class` at normal priority only after `runtime.readyStage()` succeeds; unregister immediately on disable before closing facades and the shared data source.

- [ ] **Step 4: Run focused tests and verify GREEN**

Run: `gradlew.bat test --tests '*AsyncInstitutionFacadeTest' --tests '*BloecoApiProviderTest' --tests '*PluginBootstrapTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

Commit message: `feat: register embedded Bloeco API provider`

### Task 7: Consumer compatibility example, documentation, and release candidate verification

**Files:**
- Create: `examples/bloeco-api-consumer/build.gradle.kts`
- Create: `examples/bloeco-api-consumer/src/main/java/com/blocke/example/ExampleEconomyHook.java`
- Create: `examples/bloeco-api-consumer/src/main/resources/plugin.yml`
- Modify: `README.md`
- Modify: `AGENTS.md`
- Modify: `docs/third-party-economy-integration.md`
- Modify: `docs/architecture/central-bank-design.md`
- Modify: `build.gradle.kts`
- Modify: `src/main/resources/plugin.yml`
- Test: `src/test/java/com/blocke/bloeco/api/ConsumerCompatibilityTest.java`

**Interfaces:**
- Consumes: complete API 1.0 and Provider behavior.
- Produces: an independently compiled consumer example, authoritative API usage guide, main plugin version `1.5.0-SNAPSHOT`, and a single shaded plugin JAR containing both public API and implementation.

- [ ] **Step 1: Write failing consumer compatibility test**

Compile the example against the built Bloeco JAR as `compileOnly`, inspect the example artifact to prove it does not contain `com/blocke/bloeco/api`, inspect Bloeco JAR for API classes plus `plugin.yml`, and exercise a MockBukkit consumer charge/replay/refund flow.

- [ ] **Step 2: Run compatibility test and verify RED**

Run: `gradlew.bat test --tests com.blocke.bloeco.api.ConsumerCompatibilityTest`
Expected: FAIL because the example and final documentation/version wiring do not exist.

- [ ] **Step 3: Add example and update all product/API/Agent documentation**

Document provider discovery, `depend: [Bloeco]`, `compileOnly`, no shading, thread rules, institution funding, request fields, tax behavior, delivery-after-receipt, retries with the same key, refunds, reservations, and stable API versioning. Remove all wording that says the native API is only planned.

- [ ] **Step 4: Run complete verification**

Run: `gradlew.bat clean test shadowJar`
Expected: BUILD SUCCESSFUL; all non-gated tests pass, the shaded artifact is `Bloeco-1.5.0-SNAPSHOT.jar`, and no generated/runtime files are tracked.

- [ ] **Step 5: Run gated MySQL integration and concurrency suites**

Run against an isolated MySQL 8.4 database with the repository's test environment variables: `gradlew.bat test --tests '*MySql*IntegrationTest' --tests '*MySql*ConcurrencyTest'`
Expected: PASS with no failures or errors.

- [ ] **Step 6: Commit**

Commit message: `docs: publish Bloeco API 1.0 integration contract`
