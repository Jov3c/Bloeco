# CentralEconomy

CentralEconomy is a Paper 1.21.11 economy plugin backed by one SQLite ledger.

## Vault

Vault is optional. When its plugin is enabled, CentralEconomy registers an `Economy` provider at highest priority. Player balances remain global (world parameters are ignored), deposits create `EXTERNAL_CREDIT` ledger entries, and withdrawals create `EXTERNAL_DEBIT` entries. Bank accounts are not supported.

## Build

Run `./gradlew shadowJar` with Java 21. The distributable JAR is written to `build/libs/CentralEconomy-<version>.jar`; the SQLite driver is bundled while Paper and Vault remain server-provided dependencies.
