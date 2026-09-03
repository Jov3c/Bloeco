# Paper Economy Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a Paper 1.21.11 economy plugin with a SQLite authoritative ledger, a fiscally constrained treasury procurement GUI, and Vault compatibility.

**Architecture:** The domain layer owns integer-cent monetary operations and is independent of Bukkit. A SQLite repository persists every balance mutation and ledger row in one transaction. Paper adapters expose commands, inventory UI and a Vault `Economy` provider without bypassing the domain service.

**Tech Stack:** Java 21, Gradle Kotlin DSL, Paper API 1.21.11, VaultAPI 1.7 compile-only, SQLite JDBC, JUnit 5, MockBukkit.

**Spec:** `docs/superpowers/specs/2026-09-01-paper-economy-design.md`

## Global Constraints

- Target Paper 1.21.11 and Java 21 exactly.
- Use `long` cents for every persisted monetary amount; format only at system boundaries.
- SQLite is the sole source of truth and every money mutation must insert a ledger row in the same SQL transaction.
- `Vault` remains a soft dependency; core functionality must work without it.
- Do not create currency through procurement; only an explicit `ISSUE` transaction may do so.

---

### Task 1: Gradle project and configuration shell

**Files:**
- Create: `settings.gradle.kts`
- Create: `build.gradle.kts`
- Create: `src/main/resources/plugin.yml`
- Create: `src/main/resources/config.yml`
- Create: `src/main/resources/procurement.yml`
- Create: `src/main/java/com/blocke/centraleconomy/CentralEconomyPlugin.java`
- Test: `src/test/java/com/blocke/centraleconomy/PluginBootstrapTest.java`

**Interfaces:**
- Produces `CentralEconomyPlugin extends JavaPlugin` and the `centraleconomy.admin` permission.

- [ ] **Step 1: Write a failing bootstrap test**

```java
@Test
void pluginLoadsWithDefaultResources() {
  var plugin = MockBukkit.load(CentralEconomyPlugin.class);
  assertNotNull(plugin);
  assertTrue(plugin.getDataFolder().toPath().resolve("config.yml").toFile().isFile());
  assertTrue(plugin.getDataFolder().toPath().resolve("procurement.yml").toFile().isFile());
}
```

- [ ] **Step 2: Run the test and confirm it fails because the plugin class does not exist**

Run: `./gradlew test --tests com.blocke.centraleconomy.PluginBootstrapTest`

- [ ] **Step 3: Add the minimal Gradle project, plugin descriptor and resource-saving main class**

```java
public final class CentralEconomyPlugin extends JavaPlugin {
  @Override public void onEnable() {
    saveDefaultConfig();
    saveResource("procurement.yml", false);
  }
}
```

- [ ] **Step 4: Run the bootstrap test**

Run: `./gradlew test --tests com.blocke.centraleconomy.PluginBootstrapTest`
Expected: PASS.

### Task 2: Money values and transactional SQLite ledger

**Files:**
- Create: `src/main/java/com/blocke/centraleconomy/money/Money.java`
- Create: `src/main/java/com/blocke/centraleconomy/ledger/AccountId.java`
- Create: `src/main/java/com/blocke/centraleconomy/ledger/TransactionType.java`
- Create: `src/main/java/com/blocke/centraleconomy/ledger/LedgerRepository.java`
- Create: `src/main/java/com/blocke/centraleconomy/ledger/SqliteLedgerRepository.java`
- Test: `src/test/java/com/blocke/centraleconomy/money/MoneyTest.java`
- Test: `src/test/java/com/blocke/centraleconomy/ledger/SqliteLedgerRepositoryTest.java`

**Interfaces:**
- Produces `Money.ofCents(long)`, `Money.fromVault(double)`, `LedgerRepository.transfer(AccountId, AccountId, Money, TransactionType, String)` and `LedgerRepository.balance(AccountId)`.

- [ ] **Step 1: Write failing money validation tests**

```java
@Test void rejectsNegativeAndNonFiniteVaultAmounts() {
  assertThrows(IllegalArgumentException.class, () -> Money.ofCents(-1));
  assertThrows(IllegalArgumentException.class, () -> Money.fromVault(Double.NaN));
  assertEquals(1234L, Money.fromVault(12.34).cents());
}
```

- [ ] **Step 2: Run the money test**

Run: `./gradlew test --tests com.blocke.centraleconomy.money.MoneyTest`
Expected: FAIL because `Money` does not exist.

- [ ] **Step 3: Implement immutable money and account identifiers**

```java
public record Money(long cents) {
  public Money { if (cents < 0) throw new IllegalArgumentException("cents must be non-negative"); }
  public static Money fromVault(double amount) { /* validate finite, multiply by 100 with HALF_UP */ }
}
```

- [ ] **Step 4: Write a failing SQL transaction test**

```java
@Test void transferPersistsBalancesAndOneLedgerEntry() {
  repository.credit(AccountId.treasury(), Money.ofCents(10_000), TransactionType.ISSUE, "seed");
  repository.transfer(AccountId.treasury(), AccountId.player(player), Money.ofCents(2_500), TransactionType.PROCUREMENT_GROSS, "wheat");
  assertEquals(7_500, repository.balance(AccountId.treasury()).cents());
  assertEquals(2_500, repository.balance(AccountId.player(player)).cents());
  assertEquals(2, repository.entryCount());
}
```

- [ ] **Step 5: Implement schema migration and transactional repository**

Use `BEGIN IMMEDIATE`; create `accounts(account_id TEXT PRIMARY KEY, balance_cents INTEGER NOT NULL CHECK(balance_cents >= 0))` and `ledger_entries(id TEXT PRIMARY KEY, created_at_epoch_ms INTEGER, transaction_type TEXT, debit_account TEXT, credit_account TEXT, amount_cents INTEGER CHECK(amount_cents > 0), memo TEXT)`. Implement `transferBatch(List<Posting>)`: in one transaction verify the aggregate debit of every ordinary account, apply every balance delta, insert every ledger row and commit; rollback for every exception. The special `ISSUANCE` debit does not require a stored balance and its amount is summed from ledger rows for reporting.

- [ ] **Step 6: Run money and repository tests**

Run: `./gradlew test --tests com.blocke.centraleconomy.money.MoneyTest --tests com.blocke.centraleconomy.ledger.SqliteLedgerRepositoryTest`
Expected: PASS.

### Task 3: Economic use cases and procurement settlement

**Files:**
- Create: `src/main/java/com/blocke/centraleconomy/economy/EconomyService.java`
- Create: `src/main/java/com/blocke/centraleconomy/economy/ProcurementItem.java`
- Create: `src/main/java/com/blocke/centraleconomy/economy/ProcurementQuote.java`
- Create: `src/main/java/com/blocke/centraleconomy/economy/ProcurementResult.java`
- Test: `src/test/java/com/blocke/centraleconomy/economy/EconomyServiceTest.java`

**Interfaces:**
- Produces `issueToTreasury(Money, String)`, `burnFromTreasury(Money, String)`, `quoteProcurement(UUID, ProcurementItem, int)` and `settleProcurement(UUID, ProcurementQuote)`.

- [ ] **Step 1: Write failing tax settlement tests**

```java
@Test void procurementMovesGrossThenTaxAndLeavesTreasuryNetExpense() {
  service.issueToTreasury(Money.ofCents(10_000), "opening funds");
  var quote = service.quoteProcurement(player, wheatAt100Cents, 10); // gross 1000, tax 50
  var result = service.settleProcurement(player, quote);
  assertEquals(1_000, result.gross().cents());
  assertEquals(50, result.tax().cents());
  assertEquals(950, result.playerNet().cents());
  assertEquals(9_050, service.treasuryBalance().cents());
}
```

- [ ] **Step 2: Run the economic test**

Run: `./gradlew test --tests com.blocke.centraleconomy.economy.EconomyServiceTest`
Expected: FAIL because `EconomyService` does not exist.

- [ ] **Step 3: Implement settlement using two repository transactions under a service lock**

Create both postings (`TREASURY -> PLAYER` for gross and `PLAYER -> TREASURY` for tax) and submit them to `transferBatch` as one SQLite transaction. Validate item enabled, quantity `1..maxPerSale`, and treasury gross coverage before mutation.

- [ ] **Step 4: Add and run insufficient-treasury and zero-quantity tests**

```java
@Test void unaffordableProcurementDoesNotChangeAnyBalance() { /* treasury 999, gross 1000, assert rejected */ }
@Test void procurementRejectsQuantityOutsideConfiguredLimit() { /* assert exception */ }
```

Run: `./gradlew test --tests com.blocke.centraleconomy.economy.EconomyServiceTest`
Expected: PASS.

### Task 4: Paper commands, GUI and atomic inventory handling

**Files:**
- Create: `src/main/java/com/blocke/centraleconomy/paper/EconomyCommand.java`
- Create: `src/main/java/com/blocke/centraleconomy/paper/ProcurementMenu.java`
- Modify: `src/main/java/com/blocke/centraleconomy/CentralEconomyPlugin.java`
- Modify: `src/main/resources/plugin.yml`
- Test: `src/test/java/com/blocke/centraleconomy/paper/ProcurementMenuTest.java`

**Interfaces:**
- Consumes `EconomyService` and immutable `ProcurementItem` configuration.
- Produces `/economy`, `/economy balance`, `/economy report`, `/economy treasury issue`, `/economy treasury burn`.

- [ ] **Step 1: Write a failing GUI settlement test**

```java
@Test void clickingAProcurementItemRemovesOnlySettledItemsAfterPayment() {
  var player = server.addPlayer();
  player.getInventory().addItem(new ItemStack(Material.WHEAT, 12));
  menu.open(player);
  menu.clickConfiguredItem(player, Material.WHEAT);
  assertEquals(2, player.getInventory().all(Material.WHEAT).values().stream().mapToInt(ItemStack::getAmount).sum());
}
```

- [ ] **Step 2: Run the GUI test**

Run: `./gradlew test --tests com.blocke.centraleconomy.paper.ProcurementMenuTest`
Expected: FAIL because `ProcurementMenu` does not exist.

- [ ] **Step 3: Implement a 27-slot GUI and commands**

Use a custom `InventoryHolder` to identify this plugin's inventory. Cancel all menu click and drag events. On a configured item click: recount the player's inventory, cap by item limit, obtain a quote, remove exactly the quoted number of plain matching material stacks, then settle the full financial batch. If settlement fails, restore the removed plain material stacks immediately and report rejection; do not issue an ad-hoc compensating money transaction.

- [ ] **Step 4: Run command and GUI tests**

Run: `./gradlew test --tests com.blocke.centraleconomy.paper.ProcurementMenuTest --tests com.blocke.centraleconomy.PluginBootstrapTest`
Expected: PASS.

### Task 5: Vault provider, reporting and packaged-server verification

**Files:**
- Create: `src/main/java/com/blocke/centraleconomy/vault/CentralEconomyVaultProvider.java`
- Modify: `src/main/java/com/blocke/centraleconomy/CentralEconomyPlugin.java`
- Modify: `src/main/resources/config.yml`
- Create: `README.md`
- Test: `src/test/java/com/blocke/centraleconomy/vault/CentralEconomyVaultProviderTest.java`

**Interfaces:**
- Produces a registered `net.milkbowl.vault.economy.Economy` service when Vault is installed.

- [ ] **Step 1: Write failing Vault mapping tests**

```java
@Test void withdrawRejectsInsufficientFundsAndDepositUsesSameLedgerBalance() {
  provider.depositPlayer(player, 10.00);
  assertEquals(10.00, provider.getBalance(player), 0.001);
  assertEquals(EconomyResponse.ResponseType.FAILURE, provider.withdrawPlayer(player, 10.01).type);
}
```

- [ ] **Step 2: Run the Vault test**

Run: `./gradlew test --tests com.blocke.centraleconomy.vault.CentralEconomyVaultProviderTest`
Expected: FAIL because the provider does not exist.

- [ ] **Step 3: Implement every required Vault `Economy` method**

Map player deposits and withdrawals to `EXTERNAL_CREDIT` and `EXTERNAL_DEBIT`; support global accounts; return `NOT_IMPLEMENTED` for banks; validate all double inputs through `Money.fromVault`; register with Bukkit `ServicesManager` only if `Vault` is enabled.

- [ ] **Step 4: Build, test and inspect the artifact**

Run: `./gradlew clean test shadowJar`
Expected: all tests pass and `build/libs/CentralEconomy-*.jar` exists.

- [ ] **Step 5: Perform a disposable Paper smoke test**

Download Paper 1.21.11 into a temporary directory, accept `eula.txt`, copy the built JAR to its `plugins` directory and run it with Java 21 using `--nogui`. Confirm the log contains `Enabled CentralEconomy` and no class-loading exception. Delete only the explicitly created temporary test directory afterward.
