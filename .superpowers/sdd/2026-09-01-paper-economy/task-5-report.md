# Task 5 report — Vault adapter and packaging

## Delivered

- Added `CentralEconomyVaultProvider`, an optional Vault `Economy` adapter backed solely by `LedgerRepository`.
- Deposits are persisted from the ledger's `EXTERNAL_CREDIT` source and withdrawals are persisted to the `EXTERNAL_DEBIT` sink; insufficient withdrawals leave the player balance unchanged.
- Vault amounts now reject values that cannot be represented as an exact number of cents (for example, `10.001`).
- Bank operations return `NOT_IMPLEMENTED`; all player/world overloads use global balances and offline player accounts are accepted without a second account store.
- Registered the provider at `ServicePriority.Highest` only when an enabled `Vault` plugin is present, and added `softdepend: [Vault]`.
- Added the Vault API and Shadow packaging configuration, plus Vault usage/build documentation.

## Verification

The known Gradle loopback IPC error still prevents Gradle execution in this environment. The shared Maven harness, extended with the same JitPack VaultAPI dependency, passed:

```powershell
mvn -q -f 'C:\Users\Administrator\AppData\Local\Temp\paper-economy-red-pom.xml' test '-Dtest=com.blocke.centraleconomy.money.MoneyTest,com.blocke.centraleconomy.vault.CentralEconomyVaultProviderTest'
```

The Vault test was first observed failing because `CentralEconomyVaultProvider` did not exist. The exact-cent validation test was then observed failing because `10.001` was incorrectly rounded and accepted; after switching to exact cent validation, both focused suites passed.

## Not completed under the timebox

- A disposable Paper server smoke test was not attempted.
- Gradle `shadowJar` could not be run due to the host's documented loopback-IPC failure, so the Gradle artifact itself was not inspected here.
