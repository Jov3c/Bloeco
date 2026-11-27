# Bloeco Repository Standards

## Product and platform boundaries

- Bloeco is the authoritative ledger for the Bloeco plugin family. It owns currency supply, balances, taxes, fees, banking, and settlement. Other plugins own their shops, auctions, securities, quests, and item delivery.
- Keep domain and application logic platform-independent. Platform adapters handle plugin lifecycle, commands, interfaces, scheduling, and service discovery. New adapters reuse the core ledger and settlement rules.
- Record supported server platforms, Minecraft versions, runtime requirements, and installation instructions in `README.md` and release notes. Verify startup, commands, permissions, and settlement before declaring support for a new target.
- External plugins discover `com.blocke.bloeco.api` through the platform's documented service registry. Keep API 1.x asynchronous and binary compatible. Integrations use the public API for accounts and settlement. See `docs/third-party-economy-integration.md`.

## Code and maintenance

- Use the least code needed to implement the full behavior. Prefer existing domain types, services, and repositories. Add an abstraction when it removes a duplicated rule or simplifies a concrete dependency.
- Use domain names and clear control flow. Make validation, authorization, transaction ownership, thread boundaries, and resource lifetimes easy to follow.
- Use comments to explain monetary invariants, compatibility constraints, and surprising decisions. Remove comments that restate the code or describe obsolete behavior.
- Keep changes focused. Avoid unrelated renaming, formatting, and reordering. Preserve enough context to diagnose failures while protecting credentials and private business data.

## Ledger, storage, and concurrency

- Store amounts in integer minor units using `long`. Every committed journal must balance to zero. Correct committed entries by appending a reversal.
- Only authorized issuance and retirement may change the money supply. Issue to Treasury first. Fund player grants from existing Treasury funds and loans from existing bank cash.
- Use stable client IDs and idempotency keys for retryable writes. Return the original result for the same request and reject conflicting reuse. Commit balances, journal entries, and related state atomically.
- Check balances, permissions, and reserves in the authoritative write path. A failed integrity check puts the ledger into read-only mode.
- SQLite is the default for new single-server installations. MySQL is authoritative for cross-server deployments. Preserve the configured backend during upgrades and verify migrated data before switching. Redis provides rebuildable caches and event transport.
- Use bounded background queues for JDBC and ledger operations. Follow the target platform's scheduling rules when accessing players, interfaces, and messages. Return a defined unavailable result when a queue is full or closed.

## Testing and contributions

- Add a regression test that reproduces a behavior defect before fixing it. Cover normal entry points, existing configurations, older data, retries, and concurrency where affected.
- Run `./gradlew clean test shadowJar` or use `gradlew.bat` on Windows. For MySQL changes, also run `mysqlTest` against an isolated database. Keep the regular test task independent of live MySQL.
- Use a working branch and open a pull request targeting `main`. Write concise English commit subjects in the imperative mood. Keep build artifacts, runtime data, databases, credentials, and local review notes out of Git.

## Code Review Rules

- Trace PR changes through normal entry points. Prioritize ledger continuity, monetary invariants, authorization, idempotency, transaction boundaries, and compatibility with existing data.
- Check that platform adapters reuse the core rules and that SQLite and MySQL honor the same settlement contract. Flag duplication or complexity with a concrete consequence for correctness or maintenance.
- Provide review feedback only. For each finding, identify the affected code, the trigger, the impact, and the smallest practical correction.
