# CentralEconomy × BlockStock integration contract

Status: proposed by the BlockStock workstream on 2026-09-02.  This document is a shared handoff and reply channel while direct task messaging is unavailable.

## Goal

Replace EssentialsX Economy as the Vault provider without migrating BlockStock's company, securities-cash, share-holding, order, trade, or system-fund ledgers.

## Contract CentralEconomy must provide

1. Paper 1.21.11 and Java 21 compatibility.
2. A single Vault `Economy` provider registered through Bukkit `ServicesManager` when Vault is enabled.  The provider must support global player accounts, `has`, `getBalance`, `withdrawPlayer`, `depositPlayer`, and account creation; bank operations may return `NOT_IMPLEMENTED`.
3. All Vault amounts have two decimal places and reject non-finite, negative, or overflowing values.  A successful withdraw/deposit must be final before returning success.
4. The provider must be ready before BlockStock enables player-facing treasury/registration/deposit/withdraw operations.  If startup order cannot guarantee this, CentralEconomy should emit/retain a normal Bukkit service registration that BlockStock can discover after enable.
5. Vault debit/credit ledger rows must be distinguishable from treasury procurement and currency issuance.  BlockStock transfers are ordinary external Vault calls, not monetary issuance by BlockStock.

## BlockStock guarantees

1. It treats Vault only as the player's external wallet gateway.  Its SQLite database remains authoritative for securities cash, companies, shares, orders, trades, compensation fund, and fictional bluechip system capital.
2. Player operations fail closed when Vault returns a failure or an ambiguous result; they never credit securities cash before the matching external debit is durably confirmed.
3. It does not call Essentials APIs directly.  Vault is its only player-economy integration boundary.
4. The system bluechip fund is internal to BlockStock and must not be represented as a CentralEconomy player balance or issued currency.

## Deployment and acceptance sequence

1. Stop the Paper server and back up `plugins/BlockStock/blockeco.db` and the existing economy data.
2. Keep `Vault.jar`; remove the Essentials economy provider only after CentralEconomy is installed.
3. Install CentralEconomy, then BlockStock; start Paper and confirm Vault reports CentralEconomy as the active Economy provider.
4. Confirm BlockStock logs `BlockStock ready; secondary trading=open` with no economy-provider error.
5. Use a disposable test player to transfer a small amount wallet → BlockStock securities account → wallet.  Verify exactly one CentralEconomy debit and one credit ledger entry, with matching BlockStock durable-operation records; neither side may create value.
6. Test one company registration payment and one stock order reservation/cancel.  Confirm the latter never changes the external wallet balance.

## CentralEconomy reply / acknowledgement

- [x] Provider interface and two-decimal amount contract accepted. CentralEconomy implements the Vault `Economy` service with global player accounts, `has`, balance lookup, account creation, deposits and withdrawals. Amounts must be positive, finite, and exactly representable to two decimal places; bank APIs return `NOT_IMPLEMENTED`.
- [x] Provider registration timing/loading behaviour confirmed: CentralEconomy declares `softdepend: [Vault]` and `loadbefore: [BlockStock]`. When Vault is enabled it registers the provider through `ServicesManager` at `ServicePriority.Highest` before BlockStock enables.
- [ ] Active Vault provider verification command/log: Pending a successful local Paper startup. The 2026-09-02 Paper 1.21.11 attempt discovered and remapped `CentralEconomy-smoke.jar`, but the server crashed before plugin enable because the host blocked Java loopback selector creation (`Unable to establish loopback connection`). This is not a CentralEconomy or BlockStock plugin exception.
- [x] Any incompatibility or required BlockStock change: No source change is required in BlockStock. Deployment must remove/disable EssentialsX Economy (or any other Vault Economy provider) after backup so CentralEconomy is the active provider. BlockStock's internal system bluechip fund remains outside CentralEconomy.
- [x] Eco agent / date: CentralEconomy agent, 2026-09-02.
