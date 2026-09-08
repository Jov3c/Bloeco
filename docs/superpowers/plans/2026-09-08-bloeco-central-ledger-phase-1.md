# Bloeco Central Ledger Phase 1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the current Vault-oriented balance store with a standalone, SQLite-backed central-bank ledger in which only approved issuance changes the money supply and every other operation is a balanced, auditable transfer.

**Architecture:** Keep one deployable Paper plugin while separating immutable domain objects, application use cases, SQLite persistence, and Paper UI boundaries. A single dedicated executor owns all SQLite work; Paper commands and inventory events submit asynchronous application requests and marshal display updates back to the server thread.

**Tech Stack:** Java 21, Paper 1.21.11, Gradle Kotlin DSL, SQLite JDBC, JUnit 5, MockBukkit

**Spec:** `docs/superpowers/specs/2026-09-08-bloeco-central-bank-design.md`

## Global Constraints

- Bloeco is the sole authority for balances, money supply, tax, fees, Treasury, and audit history.
- Phase 1 contains no Vault dependency, Vault registration, shop, stock, auction, item, or pricing code.
- SQLite is the default authority at `plugins/Bloeco/economy.db`.
- Every amount is a Java `long` in minor units; no floating-point amount enters the domain or database.
- Every committed journal entry has at least two postings whose signed `amount_minor` sum is exactly zero.
- Only an executed issuance request may increase net money supply, and issuance credits Treasury only.
- Ordinary accounts never have negative balances.
- Existing records are append-only; corrections use reversal entries.
- SQL commit is the success boundary, and no JDBC work runs on the Paper main thread.
- Redis and MySQL are not implemented in Phase 1; the storage boundary must not prevent their Phase 2 implementations.
- Every task is executed inline in the current task; no subagent communication is used.

---

## File Structure

### New production files

- `src/main/java/com/blocke/centraleconomy/domain/money/Money.java` — non-negative monetary amount, parsing, formatting, checked arithmetic, and basis-point rounding.
- `src/main/java/com/blocke/centraleconomy/domain/account/AccountId.java` — stable central-bank account identifiers.
- `src/main/java/com/blocke/centraleconomy/domain/account/AccountClass.java` — L0 through L3 account classification.
- `src/main/java/com/blocke/centraleconomy/domain/account/Account.java` — account ownership, purpose, state, and overdraft policy.
- `src/main/java/com/blocke/centraleconomy/domain/ledger/Posting.java` — one signed journal line.
- `src/main/java/com/blocke/centraleconomy/domain/ledger/JournalEntry.java` — balanced immutable journal aggregate.
- `src/main/java/com/blocke/centraleconomy/domain/ledger/JournalType.java` — controlled business reason values.
- `src/main/java/com/blocke/centraleconomy/domain/ledger/LedgerException.java` — stable domain failure code and safe message.
- `src/main/java/com/blocke/centraleconomy/application/LedgerStore.java` — transaction-oriented persistence port.
- `src/main/java/com/blocke/centraleconomy/application/CentralBankService.java` — Treasury, issuance, retirement, balance adjustment, and reversal use cases.
- `src/main/java/com/blocke/centraleconomy/application/PlayerPaymentService.java` — atomic player transfer, fee, and income-tax settlement.
- `src/main/java/com/blocke/centraleconomy/application/TaxRuleService.java` — versioned tax policy reads and writes.
- `src/main/java/com/blocke/centraleconomy/application/EconomyQueries.java` — balance, journal, supply, and integrity queries.
- `src/main/java/com/blocke/centraleconomy/application/AsyncEconomyFacade.java` — dedicated economy executor and asynchronous result boundary.
- `src/main/java/com/blocke/centraleconomy/application/result/Result.java` — success/failure wrapper with stable error code.
- `src/main/java/com/blocke/centraleconomy/storage/sqlite/SqliteLedgerStore.java` — SQLite implementation and transaction ownership.
- `src/main/java/com/blocke/centraleconomy/storage/sqlite/SqliteSchema.java` — schema creation and version checks.
- `src/main/java/com/blocke/centraleconomy/storage/sqlite/LegacySqliteMigrator.java` — backup, old-schema conversion, and validation.
- `src/main/java/com/blocke/centraleconomy/paper/BloecoRuntime.java` — asynchronous startup, readiness, shutdown, and service wiring.
- `src/main/java/com/blocke/centraleconomy/paper/EconomyCommand.java` — console recovery and administration command boundary.
- `src/main/java/com/blocke/centraleconomy/paper/MessageFormatter.java` — main-thread-safe result messages.

### Replaced or removed production files

- Replace `src/main/java/com/blocke/centraleconomy/money/Money.java` with the domain money type.
- Replace `src/main/java/com/blocke/centraleconomy/ledger/*` with the journal aggregate and SQLite store.
- Replace `src/main/java/com/blocke/centraleconomy/economy/*` with application services.
- Modify `src/main/java/com/blocke/centraleconomy/CentralEconomyPlugin.java` to own `BloecoRuntime` only.
- Modify `src/main/java/com/blocke/centraleconomy/paper/PayCommand.java` and `BloecoMenu.java` to call the async facade.
- Remove `src/main/java/com/blocke/centraleconomy/vault/CentralEconomyVaultProvider.java`.

### Test files

- `src/test/java/com/blocke/centraleconomy/domain/money/MoneyTest.java`
- `src/test/java/com/blocke/centraleconomy/domain/ledger/JournalEntryTest.java`
- `src/test/java/com/blocke/centraleconomy/storage/sqlite/SqliteLedgerStoreTest.java`
- `src/test/java/com/blocke/centraleconomy/storage/sqlite/LegacySqliteMigratorTest.java`
- `src/test/java/com/blocke/centraleconomy/application/CentralBankServiceTest.java`
- `src/test/java/com/blocke/centraleconomy/application/PlayerPaymentServiceTest.java`
- `src/test/java/com/blocke/centraleconomy/application/AsyncEconomyFacadeTest.java`
- `src/test/java/com/blocke/centraleconomy/paper/PluginBootstrapTest.java`

---

### Task 1: Remove Vault and establish the standalone plugin boundary

**Files:**
- Modify: `build.gradle.kts`
- Modify: `src/main/resources/plugin.yml`
- Modify: `src/main/resources/config.yml`
- Modify: `src/test/java/com/blocke/centraleconomy/PluginBootstrapTest.java`
- Delete: `src/test/java/com/blocke/centraleconomy/vault/CentralEconomyVaultProviderTest.java`

**Interfaces:**
- Consumes: current Paper plugin metadata and bootstrap test.
- Produces: a build with no Vault classes or metadata and a configuration rooted at `storage.type=sqlite`.

- [ ] **Step 1: Make the bootstrap test reject all legacy integration metadata**

```java
@Test
void pluginIsStandaloneAndContainsNoCommerceOrVaultCommands() {
    var plugin = MockBukkit.load(CentralEconomyPlugin.class);
    assertNotNull(plugin.getCommand("bloeco"));
    assertNotNull(plugin.getCommand("pay"));
    assertNull(plugin.getCommand("market"));
    assertFalse(plugin.getDescription().getSoftDepend().contains("Vault"));
    assertFalse(plugin.getDescription().getLoadBefore().contains("Bloeco-Stock"));
}
```

- [ ] **Step 2: Run the focused test and verify the legacy metadata fails it**

Run: `./gradlew test --tests com.blocke.centraleconomy.PluginBootstrapTest`

Expected: FAIL because `plugin.yml` still declares Vault and Bloeco-Stock.

- [ ] **Step 3: Remove Vault from dependencies and plugin metadata**

Remove both `VaultAPI` dependency lines, `softdepend`, `loadbefore`, the `vault` package, and the Bloeco-Stock configuration section. Replace the storage configuration with:

```yaml
storage:
  type: sqlite
  sqlite:
    file: economy.db

redis:
  enabled: false
```

- [ ] **Step 4: Run the focused test and dependency report**

Run: `./gradlew test --tests com.blocke.centraleconomy.PluginBootstrapTest dependencies`

Expected: PASS, and the dependency output contains no `VaultAPI`.

- [ ] **Step 5: Commit the standalone boundary**

```text
git add build.gradle.kts src/main/resources src/main/java/com/blocke/centraleconomy/vault src/test
git commit -m "refactor: make Bloeco a standalone economy core"
```

### Task 2: Introduce exact money and balanced journal domain types

**Files:**
- Create: `src/main/java/com/blocke/centraleconomy/domain/money/Money.java`
- Create: `src/main/java/com/blocke/centraleconomy/domain/account/AccountId.java`
- Create: `src/main/java/com/blocke/centraleconomy/domain/account/AccountClass.java`
- Create: `src/main/java/com/blocke/centraleconomy/domain/account/Account.java`
- Create: `src/main/java/com/blocke/centraleconomy/domain/ledger/Posting.java`
- Create: `src/main/java/com/blocke/centraleconomy/domain/ledger/JournalEntry.java`
- Create: `src/main/java/com/blocke/centraleconomy/domain/ledger/JournalType.java`
- Create: `src/main/java/com/blocke/centraleconomy/domain/ledger/LedgerException.java`
- Test: `src/test/java/com/blocke/centraleconomy/domain/money/MoneyTest.java`
- Test: `src/test/java/com/blocke/centraleconomy/domain/ledger/JournalEntryTest.java`

**Interfaces:**
- Produces: `Money.ofMinor(long)`, `Money.parse(String)`, `Money.percentage(int)`, `Posting(AccountId,long)`, and `JournalEntry.create(...)`.

- [ ] **Step 1: Write failing exact-money tests**

```java
@Test void parsesTwoDecimalPlacesWithoutFloatingPoint() {
    assertEquals(12_345L, Money.parse("123.45").minor());
    assertThrows(IllegalArgumentException.class, () -> Money.parse("1.001"));
}

@Test void roundsBasisPointsHalfUp() {
    assertEquals(1L, Money.ofMinor(50).percentage(100).minor());
    assertEquals(5L, Money.ofMinor(999).percentage(50).minor());
}
```

- [ ] **Step 2: Write failing journal invariant tests**

```java
@Test void acceptsAZeroSumJournal() {
    JournalEntry entry = JournalEntry.create(UUID.randomUUID(), JournalType.PLAYER_TRANSFER,
            "pay", null, null, Instant.EPOCH,
            List.of(new Posting(AccountId.player(A), -100), new Posting(AccountId.player(B), 100)));
    assertEquals(0L, entry.postings().stream().mapToLong(Posting::amountMinor).sum());
}

@Test void rejectsUnbalancedAndSingleLineJournals() {
    assertThrows(LedgerException.class, () -> JournalEntry.create(UUID.randomUUID(),
            JournalType.PLAYER_TRANSFER, "bad", null, null, Instant.EPOCH,
            List.of(new Posting(AccountId.player(A), 100))));
}
```

- [ ] **Step 3: Run both tests and verify missing domain types**

Run: `./gradlew test --tests '*MoneyTest' --tests '*JournalEntryTest'`

Expected: compilation FAIL because the new domain package does not exist.

- [ ] **Step 4: Implement the domain types with checked arithmetic**

Use `Math.addExact` and `Math.multiplyExact`. Implement basis points as quotient/remainder arithmetic so multiplication cannot silently overflow, and reject blank IDs, zero posting lines, duplicate entry IDs, memos longer than 256 characters, and journals whose exact checked sum is nonzero.

Core signatures:

```java
public record Money(long minor) {
    public static Money ofMinor(long minor);
    public static Money parse(String decimal);
    public Money plus(Money other);
    public Money minus(Money other);
    public Money percentage(int basisPoints);
}

public record Posting(AccountId accountId, long amountMinor) {}

public record JournalEntry(UUID id, JournalType type, String memo,
        String clientId, String idempotencyKey, UUID reversalOf,
        Instant createdAt, List<Posting> postings) {
    public static JournalEntry create(UUID id, JournalType type, String memo,
            String clientId, String idempotencyKey, Instant createdAt,
            List<Posting> postings);
}
```

- [ ] **Step 5: Run domain tests**

Run: `./gradlew test --tests 'com.blocke.centraleconomy.domain.*'`

Expected: PASS.

- [ ] **Step 6: Commit the domain model**

```text
git add src/main/java/com/blocke/centraleconomy/domain src/test/java/com/blocke/centraleconomy/domain
git commit -m "feat: add balanced central ledger domain"
```

### Task 3: Build the versioned SQLite journal store

**Files:**
- Create: `src/main/java/com/blocke/centraleconomy/application/LedgerStore.java`
- Create: `src/main/java/com/blocke/centraleconomy/storage/sqlite/SqliteSchema.java`
- Create: `src/main/java/com/blocke/centraleconomy/storage/sqlite/SqliteLedgerStore.java`
- Test: `src/test/java/com/blocke/centraleconomy/storage/sqlite/SqliteLedgerStoreTest.java`

**Interfaces:**
- Consumes: `Account`, `AccountId`, `JournalEntry`, `Money`.
- Produces: atomic `commit`, materialized `balance`, journal lookup, supply totals, and integrity verification.

- [ ] **Step 1: Write failing persistence tests**

```java
@Test void commitsJournalAndBalancesAtomically() {
    store.createAccount(Account.treasury());
    store.createAccount(Account.player(PLAYER));
    store.commit(issueToTreasury(10_000));
    store.commit(transferTreasuryToPlayer(PLAYER, 2_500));
    assertEquals(7_500L, store.balance(AccountId.treasury()));
    assertEquals(2_500L, store.balance(AccountId.player(PLAYER)));
    assertEquals(2, store.entryCount());
}

@Test void insufficientFundsRollsBackEveryLine() {
    store.commit(issueToTreasury(1_000));
    assertThrows(LedgerException.class, () -> store.commit(twoAllocationsTotalling(1_100)));
    assertEquals(1_000L, store.balance(AccountId.treasury()));
    assertEquals(1, store.entryCount());
}
```

- [ ] **Step 2: Run the persistence test and verify it fails**

Run: `./gradlew test --tests '*SqliteLedgerStoreTest'`

Expected: compilation FAIL because `LedgerStore` and `SqliteLedgerStore` do not exist.

- [ ] **Step 3: Define the transaction-oriented store port**

```java
public interface LedgerStore extends AutoCloseable {
    void createAccount(Account account);
    JournalEntry commit(JournalEntry entry);
    long balance(AccountId accountId);
    Optional<JournalEntry> entry(UUID entryId);
    Optional<JournalEntry> idempotentResult(String clientId, String idempotencyKey);
    MonetaryTotals monetaryTotals();
    IntegrityReport verifyIntegrity();
    void close();
}
```

- [ ] **Step 4: Implement schema version 2 and SQLite transaction semantics**

Create `schema_history`, `accounts`, `account_balances`, `journal_entries`, `postings`, `idempotency_records`, `tax_rules`, `issuance_requests`, `policy_limits`, `audit_events`, and `daily_monetary_metrics`. Configure `WAL`, foreign keys, 5000 ms busy timeout, and `synchronous=FULL` before schema access.

For `commit`, execute `BEGIN IMMEDIATE`, load every affected account in sorted ID order, validate nonnegative post-balances, insert the journal and lines, update materialized balances, store the idempotent result, then `COMMIT`. Roll back on every exception.

- [ ] **Step 5: Add duplicate-idempotency and integrity tests**

```java
@Test void sameIdempotencyReturnsOriginalEntry() {
    JournalEntry first = store.commit(idempotentTransfer("shop", "order-1"));
    JournalEntry second = store.commit(idempotentTransfer("shop", "order-1"));
    assertEquals(first.id(), second.id());
    assertEquals(1, store.entriesForKey("shop", "order-1"));
}

@Test void integrityRecomputesMaterializedBalances() {
    store.commit(issueToTreasury(10_000));
    assertTrue(store.verifyIntegrity().valid());
}
```

- [ ] **Step 6: Run SQLite tests**

Run: `./gradlew test --tests '*SqliteLedgerStoreTest'`

Expected: PASS.

- [ ] **Step 7: Commit the SQLite authority**

```text
git add src/main/java/com/blocke/centraleconomy/application/LedgerStore.java src/main/java/com/blocke/centraleconomy/storage src/test/java/com/blocke/centraleconomy/storage
git commit -m "feat: persist balanced journals in SQLite"
```

### Task 4: Migrate the legacy SQLite ledger without losing balances

**Files:**
- Create: `src/main/java/com/blocke/centraleconomy/storage/sqlite/LegacySqliteMigrator.java`
- Test: `src/test/java/com/blocke/centraleconomy/storage/sqlite/LegacySqliteMigratorTest.java`

**Interfaces:**
- Consumes: legacy `accounts(account_id,balance_cents)` and `ledger_entries` schema.
- Produces: schema version 2, a timestamped backup, migration report, and byte-for-byte preservation of the original on failure.

- [ ] **Step 1: Build a representative legacy database fixture and failing success test**

```java
@Test void migratesLegacyBalancesIntoBalancedJournals() {
    Path database = fixture.createLegacyDatabase(Map.of("TREASURY", 7_500L, "PLAYER:" + PLAYER, 2_500L));
    MigrationReport report = migrator.migrateIfRequired(database);
    try (SqliteLedgerStore migrated = new SqliteLedgerStore(database)) {
        assertEquals(7_500L, migrated.balance(AccountId.treasury()));
        assertEquals(2_500L, migrated.balance(AccountId.player(PLAYER)));
        assertTrue(migrated.verifyIntegrity().valid());
    }
    assertTrue(Files.isRegularFile(report.backupPath()));
}
```

- [ ] **Step 2: Write the failure-preserves-original test**

```java
@Test void failedValidationLeavesOriginalAndBackupUntouched() {
    Path database = fixture.createInconsistentLegacyDatabase();
    byte[] before = Files.readAllBytes(database);
    assertThrows(MigrationException.class, () -> migrator.migrateIfRequired(database));
    assertArrayEquals(before, Files.readAllBytes(database));
    assertTrue(Files.isRegularFile(migrator.lastBackupPath()));
}
```

- [ ] **Step 3: Run migration tests and verify they fail**

Run: `./gradlew test --tests '*LegacySqliteMigratorTest'`

Expected: compilation FAIL because the migrator is missing.

- [ ] **Step 4: Implement copy-migrate-verify-replace**

Use SQLite `VACUUM INTO` to create the consistent backup. Copy the backup to a sibling staging database, migrate the staging copy, map legacy accounts, convert each old debit/credit row into one two-line journal, label external source/sink history `LEGACY_EXTERNAL`, recompute balances and supply, and atomically replace the active database only after exact equality with legacy materialized balances.

- [ ] **Step 5: Run migration and SQLite store tests together**

Run: `./gradlew test --tests '*LegacySqliteMigratorTest' --tests '*SqliteLedgerStoreTest'`

Expected: PASS.

- [ ] **Step 6: Commit safe migration**

```text
git add src/main/java/com/blocke/centraleconomy/storage/sqlite/LegacySqliteMigrator.java src/test/java/com/blocke/centraleconomy/storage/sqlite/LegacySqliteMigratorTest.java
git commit -m "feat: migrate legacy balances to the central journal"
```

### Task 5: Implement controlled issuance, retirement, Treasury, and reversal

**Files:**
- Create: `src/main/java/com/blocke/centraleconomy/application/CentralBankService.java`
- Create: `src/main/java/com/blocke/centraleconomy/application/command/IssuanceRequest.java`
- Create: `src/main/java/com/blocke/centraleconomy/application/command/TreasuryTransfer.java`
- Create: `src/main/java/com/blocke/centraleconomy/application/result/MonetaryTotals.java`
- Test: `src/test/java/com/blocke/centraleconomy/application/CentralBankServiceTest.java`

**Interfaces:**
- Consumes: `LedgerStore`, `Money`, central accounts, requester identity, policy limits.
- Produces: `requestIssuance`, `approveIssuance`, `executeIssuance`, `allocateFromTreasury`, `retireFromTreasury`, `adjustPlayerBalance`, and `reverse`.

- [ ] **Step 1: Write failing tests proving ordinary administration cannot mint**

```java
@Test void playerGrantFailsWhenTreasuryIsEmpty() {
    assertThrows(LedgerException.class,
            () -> bank.adjustPlayerBalance(PLAYER, Money.ofMinor(1_000), ACTOR, "starter funds", "grant-1"));
    assertEquals(0L, store.monetaryTotals().netSupplyMinor());
}

@Test void issuanceCreditsOnlyTreasury() {
    UUID request = bank.requestIssuance(Money.ofMinor(10_000), ACTOR, "server launch");
    bank.approveIssuance(request, APPROVER);
    bank.executeIssuance(request, ACTOR, "issue-1");
    assertEquals(10_000L, store.balance(AccountId.treasury()));
    assertEquals(10_000L, store.monetaryTotals().netSupplyMinor());
}
```

- [ ] **Step 2: Add issuance limit and retirement tests**

```java
@Test void issuanceAboveDailyLimitIsRejectedWithoutJournal() {
    policy.setDailyIssuanceLimitMinor(5_000);
    UUID request = bank.requestIssuance(Money.ofMinor(5_001), ACTOR, "too much");
    bank.approveIssuance(request, APPROVER);
    assertThrows(LedgerException.class, () -> bank.executeIssuance(request, ACTOR, "issue-limit"));
    assertEquals(0L, store.monetaryTotals().issuedMinor());
}

@Test void retirementReducesSupplyAndCannotBeSpentAgain() {
    issue(10_000);
    bank.retireFromTreasury(Money.ofMinor(2_000), ACTOR, "currency sink", "retire-1");
    assertEquals(8_000L, store.monetaryTotals().netSupplyMinor());
}
```

- [ ] **Step 3: Run service tests and verify they fail**

Run: `./gradlew test --tests '*CentralBankServiceTest'`

Expected: compilation FAIL because the central-bank service is missing.

- [ ] **Step 4: Implement explicit state transitions and central postings**

Use these journal shapes:

```text
ISSUE:      monetary:issuance -amount, fiscal:treasury +amount
RETIRE:     fiscal:treasury -amount, monetary:retired +amount
ALLOCATION: fiscal:treasury -amount, destination +amount
RECLAIM:    source -amount, fiscal:treasury +amount
REVERSAL:   every original posting multiplied by -1
```

Persist `REQUESTED`, `APPROVED`, and `EXECUTED` issuance transitions and reject skipped or repeated transitions. Enforce per-operation, daily, and rolling 30-day supply growth limits before committing `ISSUE`.

- [ ] **Step 5: Run service and store tests**

Run: `./gradlew test --tests '*CentralBankServiceTest' --tests '*SqliteLedgerStoreTest'`

Expected: PASS.

- [ ] **Step 6: Commit central-bank controls**

```text
git add src/main/java/com/blocke/centraleconomy/application src/test/java/com/blocke/centraleconomy/application
git commit -m "feat: enforce controlled currency issuance"
```

### Task 6: Persist versioned tax and fee policy

**Files:**
- Create: `src/main/java/com/blocke/centraleconomy/application/TaxRuleService.java`
- Create: `src/main/java/com/blocke/centraleconomy/domain/tax/TaxRule.java`
- Create: `src/main/java/com/blocke/centraleconomy/domain/tax/TaxCategory.java`
- Modify: `src/main/java/com/blocke/centraleconomy/application/LedgerStore.java`
- Modify: `src/main/java/com/blocke/centraleconomy/storage/sqlite/SqliteLedgerStore.java`
- Test: `src/test/java/com/blocke/centraleconomy/application/TaxRuleServiceTest.java`

**Interfaces:**
- Produces: effective-dated `PLAYER_TRANSFER_FEE` and `PLAYER_TRANSFER_INCOME` rules measured in basis points with an optional fixed minor-unit fee.

- [ ] **Step 1: Write failing rule-version tests**

```java
@Test void changingARuleKeepsHistoricalVersionImmutable() {
    TaxRule first = taxes.change(PLAYER_TRANSFER_FEE, 100, 0, ACTOR, "initial");
    TaxRule second = taxes.change(PLAYER_TRANSFER_FEE, 150, 0, ACTOR, "adjusted");
    assertNotEquals(first.versionId(), second.versionId());
    assertEquals(100, taxes.byVersion(first.versionId()).basisPoints());
    assertEquals(150, taxes.current(PLAYER_TRANSFER_FEE).basisPoints());
}

@Test void rejectsRatesOutsideZeroToTenThousandBasisPoints() {
    assertThrows(IllegalArgumentException.class,
            () -> taxes.change(PLAYER_TRANSFER_FEE, 10_001, 0, ACTOR, "invalid"));
}
```

- [ ] **Step 2: Run the rule tests and verify they fail**

Run: `./gradlew test --tests '*TaxRuleServiceTest'`

Expected: compilation FAIL because the tax domain is missing.

- [ ] **Step 3: Implement immutable rules in SQL**

Each change closes the previous rule with `effective_until`, inserts a new version with `effective_from`, actor, memo, basis points, fixed amount, and destination account, and writes an audit event in one transaction. Seed default 100 basis-point transfer fee and 500 basis-point income tax only when no rules exist.

- [ ] **Step 4: Run tax and SQLite tests**

Run: `./gradlew test --tests '*TaxRuleServiceTest' --tests '*SqliteLedgerStoreTest'`

Expected: PASS.

- [ ] **Step 5: Commit versioned fiscal policy**

```text
git add src/main/java/com/blocke/centraleconomy/domain/tax src/main/java/com/blocke/centraleconomy/application src/main/java/com/blocke/centraleconomy/storage src/test/java/com/blocke/centraleconomy/application/TaxRuleServiceTest.java
git commit -m "feat: persist versioned tax policy"
```

### Task 7: Settle player transfers atomically and idempotently

**Files:**
- Create: `src/main/java/com/blocke/centraleconomy/application/PlayerPaymentService.java`
- Create: `src/main/java/com/blocke/centraleconomy/application/command/PlayerPayment.java`
- Create: `src/main/java/com/blocke/centraleconomy/application/result/TransferReceipt.java`
- Test: `src/test/java/com/blocke/centraleconomy/application/PlayerPaymentServiceTest.java`

**Interfaces:**
- Consumes: sender, recipient, principal, memo, and idempotency key.
- Produces: one journal ID plus principal, sender debit, recipient net, fee, income tax, and rule version IDs.

- [ ] **Step 1: Write the complete settlement test**

```java
@Test void transferProducesOneBalancedFourLineJournal() {
    fundPlayer(SENDER, 20_000);
    TransferReceipt receipt = payments.pay(new PlayerPayment(
            SENDER, RECIPIENT, Money.ofMinor(10_000), "gift", "pay-1"));
    assertEquals(10_100L, receipt.senderDebit().minor());
    assertEquals(9_500L, receipt.recipientNet().minor());
    assertEquals(500L, receipt.incomeTax().minor());
    assertEquals(100L, receipt.fee().minor());
    assertEquals(9_900L, store.balance(AccountId.player(SENDER)));
    assertEquals(9_500L, store.balance(AccountId.player(RECIPIENT)));
    assertEquals(500L, store.balance(AccountId.taxRevenue()));
    assertEquals(100L, store.balance(AccountId.feeRevenue()));
}
```

- [ ] **Step 2: Add rejection and replay tests**

```java
@Test void insufficientFundsWritesNothing() {
    long before = store.entryCount();
    assertThrows(LedgerException.class, () -> payments.pay(paymentFor(10_000, "pay-low")));
    assertEquals(before, store.entryCount());
}

@Test void replayReturnsTheSameReceiptWithoutSecondDebit() {
    fundPlayer(SENDER, 20_000);
    TransferReceipt first = payments.pay(paymentFor(10_000, "pay-replay"));
    TransferReceipt replay = payments.pay(paymentFor(10_000, "pay-replay"));
    assertEquals(first.entryId(), replay.entryId());
    assertEquals(9_900L, store.balance(AccountId.player(SENDER)));
}
```

- [ ] **Step 3: Run payment tests and verify they fail**

Run: `./gradlew test --tests '*PlayerPaymentServiceTest'`

Expected: compilation FAIL because the payment service is missing.

- [ ] **Step 4: Implement one-journal settlement**

For principal `P`, fee `F`, and income tax `T`, create exactly these postings:

```text
sender          -(P + F)
recipient       +(P - T)
fiscal:tax      +T
fiscal:fee      +F
```

Omit zero-value lines while retaining at least two postings. Reject self-payment, zero principal, blank or oversized memo/key, frozen accounts, and insufficient funds before commit. Store rule version IDs in journal metadata.

- [ ] **Step 5: Run payment, tax, and central-bank tests**

Run: `./gradlew test --tests '*PlayerPaymentServiceTest' --tests '*TaxRuleServiceTest' --tests '*CentralBankServiceTest'`

Expected: PASS.

- [ ] **Step 6: Commit atomic player clearing**

```text
git add src/main/java/com/blocke/centraleconomy/application src/test/java/com/blocke/centraleconomy/application/PlayerPaymentServiceTest.java
git commit -m "feat: clear taxed player payments atomically"
```

### Task 8: Put every database operation behind a dedicated executor

**Files:**
- Create: `src/main/java/com/blocke/centraleconomy/application/result/Result.java`
- Create: `src/main/java/com/blocke/centraleconomy/application/AsyncEconomyFacade.java`
- Create: `src/main/java/com/blocke/centraleconomy/paper/BloecoRuntime.java`
- Test: `src/test/java/com/blocke/centraleconomy/application/AsyncEconomyFacadeTest.java`

**Interfaces:**
- Consumes: synchronous application services owned exclusively by one worker thread.
- Produces: `CompletionStage<Result<T>>`, readiness state, graceful draining shutdown, and safe errors.

- [ ] **Step 1: Write a thread-ownership test**

```java
@Test void ledgerWorkNeverRunsOnTheCallingThread() {
    String caller = Thread.currentThread().getName();
    Result<Long> result = facade.balance(AccountId.treasury()).toCompletableFuture().join();
    assertTrue(result.isSuccess());
    assertNotEquals(caller, recordingStore.lastThreadName());
    assertTrue(recordingStore.lastThreadName().startsWith("Bloeco-Economy-"));
}
```

- [ ] **Step 2: Write graceful-shutdown and safe-error tests**

```java
@Test void shutdownRejectsNewWorkAfterDrainingAcceptedWork() {
    CompletionStage<Result<Long>> accepted = facade.balance(AccountId.treasury());
    facade.close();
    assertTrue(accepted.toCompletableFuture().join().isSuccess());
    assertEquals(ErrorCode.STORAGE_UNAVAILABLE,
            facade.balance(AccountId.treasury()).toCompletableFuture().join().errorCode());
}
```

- [ ] **Step 3: Run facade tests and verify they fail**

Run: `./gradlew test --tests '*AsyncEconomyFacadeTest'`

Expected: compilation FAIL because the facade is missing.

- [ ] **Step 4: Implement the executor and result mapping**

Create a single-thread `ExecutorService` named `Bloeco-Economy-1`. Construct and migrate SQLite inside that executor, not in `onEnable`. Convert known `LedgerException` codes to stable results and unexpected failures to `INTERNAL_ERROR` while logging the cause only at the plugin boundary.

- [ ] **Step 5: Run asynchronous and application tests**

Run: `./gradlew test --tests '*AsyncEconomyFacadeTest' --tests 'com.blocke.centraleconomy.application.*'`

Expected: PASS.

- [ ] **Step 6: Commit the asynchronous boundary**

```text
git add src/main/java/com/blocke/centraleconomy/application src/main/java/com/blocke/centraleconomy/paper/BloecoRuntime.java src/test/java/com/blocke/centraleconomy/application
git commit -m "feat: isolate economy storage from the Paper thread"
```

### Task 9: Rewire Paper commands and GUI to the central-bank services

**Files:**
- Modify: `src/main/java/com/blocke/centraleconomy/CentralEconomyPlugin.java`
- Modify: `src/main/java/com/blocke/centraleconomy/paper/PayCommand.java`
- Modify: `src/main/java/com/blocke/centraleconomy/paper/BloecoMenu.java`
- Create: `src/main/java/com/blocke/centraleconomy/paper/EconomyCommand.java`
- Create: `src/main/java/com/blocke/centraleconomy/paper/MessageFormatter.java`
- Modify: `src/main/resources/plugin.yml`
- Test: `src/test/java/com/blocke/centraleconomy/PluginBootstrapTest.java`
- Modify: `src/test/java/com/blocke/centraleconomy/paper/BloecoMenuTest.java`

**Interfaces:**
- Consumes: `BloecoRuntime`, `AsyncEconomyFacade`, Paper scheduler, roles.
- Produces: nonblocking balance, transfer, Treasury, tax, issuance, retirement, journal, and health interactions.

- [ ] **Step 1: Write bootstrap readiness and no-main-thread-storage tests**

```java
@Test void pluginStartsWithoutVaultAndBecomesReadyAsynchronously() {
    CentralEconomyPlugin plugin = MockBukkit.load(CentralEconomyPlugin.class);
    server.getScheduler().performTicks(5);
    assertTrue(plugin.runtime().readyStage().toCompletableFuture().join().isSuccess());
    assertFalse(plugin.getDescription().getSoftDepend().contains("Vault"));
}
```

- [ ] **Step 2: Write command parsing tests using exact decimal strings**

```java
@Test void payRejectsMoreThanTwoDecimalPlacesBeforeSubmitting() {
    player.performCommand("pay Receiver 1.001");
    assertEquals(0, facade.submittedPayments());
    player.assertSaid("金额最多只能有两位小数。");
}
```

- [ ] **Step 3: Run Paper tests and verify old synchronous wiring fails**

Run: `./gradlew test --tests '*PluginBootstrapTest' --tests '*BloecoMenuTest'`

Expected: FAIL because the plugin still constructs SQLite synchronously and uses the old services.

- [ ] **Step 4: Rewire lifecycle and user commands**

`onEnable` creates `BloecoRuntime`, starts asynchronous initialization, and registers command handlers that report “经济中心正在启动” until ready. Command and GUI callbacks use `Bukkit.getScheduler().runTask(plugin, ...)` before touching players or inventories.

Keep `/pay <online-player> <decimal-amount>` and GUI payment. Add console recovery forms under `/bloeco` for `health`, `balance`, `ledger`, `issue request`, `issue approve`, `issue execute`, `retire`, `treasury grant`, and `verify`; each maps to the same application method used by GUI actions.

- [ ] **Step 5: Replace permissions with separated roles**

```yaml
permissions:
  bloeco.role.operator:
    default: op
  bloeco.role.treasurer:
    default: op
  bloeco.role.tax:
    default: op
  bloeco.role.monetary:
    default: op
  bloeco.role.auditor:
    default: op
```

- [ ] **Step 6: Run Paper and full unit tests**

Run: `./gradlew test`

Expected: PASS.

- [ ] **Step 7: Commit Paper integration**

```text
git add src/main/java/com/blocke/centraleconomy src/main/resources src/test
git commit -m "feat: expose the standalone central bank on Paper"
```

### Task 10: Add integrity protection, metrics, documentation, and release verification

**Files:**
- Create: `src/main/java/com/blocke/centraleconomy/application/EconomyQueries.java`
- Modify: `src/main/java/com/blocke/centraleconomy/paper/BloecoRuntime.java`
- Modify: `README.md`
- Replace: `docs/third-party-economy-integration.md`
- Delete: `docs/bloeco-stock-integration.md`
- Test: `src/test/java/com/blocke/centraleconomy/application/EconomyQueriesTest.java`
- Modify: `src/test/java/com/blocke/centraleconomy/PluginBootstrapTest.java`

**Interfaces:**
- Consumes: immutable journals and materialized balances.
- Produces: monetary totals, account-class totals, daily flow statistics, integrity report, read-only protection, and standalone integration documentation.

- [ ] **Step 1: Write monetary invariant tests**

```java
@Test void transfersAndTaxesDoNotChangeNetSupply() {
    issueAndFundSender(100_000);
    long before = queries.monetaryTotals().netSupplyMinor();
    payments.pay(paymentFor(10_000, "supply-test"));
    assertEquals(before, queries.monetaryTotals().netSupplyMinor());
}

@Test void integrityFailureEnablesReadOnlyProtection() {
    fixture.corruptMaterializedBalance(AccountId.treasury(), 1);
    runtime.verifyNow().toCompletableFuture().join();
    assertTrue(runtime.isReadOnly());
    assertEquals(ErrorCode.INTEGRITY_FAILURE,
            runtime.facade().retire(retirement()).toCompletableFuture().join().errorCode());
}
```

- [ ] **Step 2: Run query tests and verify they fail**

Run: `./gradlew test --tests '*EconomyQueriesTest'`

Expected: compilation FAIL because the query service and read-only protection are missing.

- [ ] **Step 3: Implement totals, daily metrics, and protection mode**

Compute cumulative issued, cumulative retired, net supply, player circulation, Treasury, tax, fee, and account-class totals from SQL. Run integrity verification after migration and on a configurable schedule. On mismatch, atomically switch runtime to read-only, preserve queries, reject writes, and log the report ID.

- [ ] **Step 4: Rewrite public documentation**

README must state that Bloeco is standalone, does not use Vault, defaults to SQLite, and forbids direct balance mutation. The integration document must explain the future native API boundary without promising Phase 2 classes as currently available. Remove all deployment instructions that tell plugins to use Vault or mention Bloeco-Stock-specific UUIDs.

- [ ] **Step 5: Run source scans and the complete test suite**

Run:

```text
rg -n "Vault|CentralEconomyVaultProvider|EXTERNAL_CREDIT|EXTERNAL_DEBIT|bloeco-stock" src build.gradle.kts README.md docs/third-party-economy-integration.md
./gradlew clean test shadowJar
```

Expected: the scan returns no active source/config/integration references; all tests pass; `build/libs/Bloeco-1.0.0-SNAPSHOT.jar` exists.

- [ ] **Step 6: Inspect the built plugin metadata**

Run: `jar xf build/libs/Bloeco-1.0.0-SNAPSHOT.jar plugin.yml` in a temporary directory, then inspect the extracted file.

Expected: plugin name `Bloeco`, Paper API `1.21`, commands `bloeco` and `pay`, and no Vault dependency.

- [ ] **Step 7: Commit the Phase 1 release candidate**

```text
git add README.md docs src build.gradle.kts
git commit -m "docs: publish standalone central bank contract"
```

- [ ] **Step 8: Record verification evidence without creating a release**

Record the test count, JAR path, SHA-256, active Git commit, and dirty-worktree status in the completion report. Do not create a Git tag or GitHub Release.

---

## Plan Self-Review Results

- Spec coverage: Phase 1 covers the central journal, exact money, controlled issuance and retirement, Treasury-only administration, versioned transfer taxes, SQLite authority, safe legacy migration, async Paper access, audit integrity, and standalone documentation.
- Deferred by the approved staged design: plugin institution registration and MySQL belong to Phase 2; Redis and advanced governance dashboards belong to Phase 3.
- Type consistency: all monetary domain inputs use `Money`; persistence uses signed `long amountMinor`; external operations use stable idempotency keys.
- Execution method: inline execution in this task, because the user prohibited communication with other agents.
