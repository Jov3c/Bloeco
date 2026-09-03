# Task 4 report: Paper commands and procurement menu

## Delivered

- Added the `ProcurementMenu` Paper adapter. It builds a 27-slot custom-holder inventory from enabled `procurement.yml` entries and rejects all inventory clicks and drags in that menu.
- A configured click recounts only vanilla-equivalent player inventory stacks, caps the sale to `max-per-sale`, asks `EconomyService` for a quote, removes exactly that quantity, then settles the existing financial batch. Any settlement rejection restores a cloned pre-removal storage snapshot immediately.
- Custom item metadata/data-component variants do not match the vanilla item template and therefore cannot be procured.
- Added `/economy`, `/economy balance [player]`, `/economy report`, `/economy treasury issue <amount> <memo>`, and `/economy treasury burn <amount> <memo>`. Issue/burn require `centraleconomy.admin`; command amounts are parsed into exact integer cents.
- Plugin startup now opens the SQLite ledger, converts the configured fraction tax rate to the domain's whole-percent rate, loads procurement entries, registers the menu listener and command executor, and closes the ledger when disabled. `plugin.yml` declares the command.

## Procurement configuration

Each entry beneath `items` in `procurement.yml` may use the entry key or an explicit `material`, plus `unit-price-cents`, `max-per-sale`, and `enabled`. Invalid material or economic values are logged and omitted rather than becoming sellable items.

## TDD evidence

1. `ProcurementMenuTest` was added before the menu existed; the focused Maven run failed at test compilation because `ProcurementMenu` was missing.
2. The tests then drove the minimum sale flow, custom-item rejection, and rejected-settlement restoration behavior.
3. The restoration test was strengthened to sell 10 from a 12-item stack. It failed with only two items restored, proving the shallow inventory snapshot bug; cloning snapshot stacks made the focused suite green.

## Verification

The host Gradle loopback issue remains outside Task 4, so verification used the Maven harness with the project SQLite test dependency:

```powershell
mvn -q -f 'C:\Users\Administrator\AppData\Local\Temp\paper-economy-red-pom.xml' test '-Dtest=com.blocke.centraleconomy.paper.ProcurementMenuTest'
mvn -q -f 'C:\Users\Administrator\AppData\Local\Temp\paper-economy-red-pom.xml' test '-Dtest=com.blocke.centraleconomy.paper.ProcurementMenuTest,com.blocke.centraleconomy.PluginBootstrapTest'
mvn -q -f 'C:\Users\Administrator\AppData\Local\Temp\paper-economy-red-pom.xml' test
```

All three fresh Maven commands completed successfully. No Vault integration was added.
