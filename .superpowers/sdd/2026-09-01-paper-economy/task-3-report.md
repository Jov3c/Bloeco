# Task 3 report: Economic use cases and procurement settlement

## Delivered

- Added immutable `ProcurementItem`, player-bound `ProcurementQuote`, and `ProcurementResult` domain values.
- Added `EconomyService` issuing `ISSUANCE -> TREASURY` entries and burning `TREASURY -> BURN` entries.
- Procurement validates the enabled status, quantity bounds, 0–100 whole-percent tax configuration, positive item price, and checked gross multiplication.
- Settlement confirms the quote still represents the supplied player and current terms, checks treasury gross coverage before mutation, then persists gross and non-zero tax postings through one `transferBatch` call.
- Added real SQLite tests for gross/tax/net settlement, insufficient treasury no-op behavior, quantity bounds, disabled items, price overflow, tax-rate bounds, and issue/burn account movement.

## TDD evidence

1. `EconomyServiceTest` was written first.
2. The focused Maven harness run failed at test compilation as expected because `EconomyService` and `ProcurementItem` did not yet exist.
3. After the minimal implementation, the focused test and the full non-Paper SQLite suite passed:

   ```powershell
   mvn -q test '-Dtest=com.blocke.centraleconomy.economy.EconomyServiceTest'
   mvn -q test '-Dtest=com.blocke.centraleconomy.money.MoneyTest,com.blocke.centraleconomy.ledger.SqliteLedgerRepositoryTest,com.blocke.centraleconomy.economy.EconomyServiceTest'
   ```

## Gradle note

Gradle remains unable to run in this environment because of the documented loopback-host failure. Verification used the existing isolated Maven harness, which compiles the worktree sources and uses real Xerial SQLite.

## Commit

`feat: add treasury procurement service`

## Concern

At a 0% tax rate, the service sends one gross posting in its single batch rather than manufacturing a prohibited zero-value tax ledger row. `LedgerRepository.Posting` intentionally rejects zero amounts and Task 3 must not change that public repository semantic.
