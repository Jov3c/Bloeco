# Central Market Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a first-party, GUI-driven player commodity market that settles exclusively through CentralEconomy's SQLite ledger.

**Architecture:** A market repository persists material-only seller listings and immutable trades. The market service performs escrow state and monetary batch postings atomically. Paper commands and a custom-holder inventory present the market without allowing players to move GUI items.

**Tech Stack:** Java 21, Paper 1.21.11, SQLite JDBC, JUnit 5, MockBukkit.

**Spec:** `docs/superpowers/specs/2026-09-02-central-market-design.md`

## Global Constraints

- All money uses nonnegative `long` cents and the existing `LedgerRepository.transferBatch`.
- Market supports only plain original `Material` stacks: no item meta, enchantments or custom data.
- Buyer payment, seller net receipt and treasury fee are one monetary batch; market quantities and trade rows use the same SQLite transaction.
- A seller cannot buy their own listing; failures change neither money nor market quantity.

---

### Task 1: Market persistence and immutable domain types

**Files:**
- Create: `market/MarketListing.java`, `MarketTrade.java`, `MarketRepository.java`, `SqliteMarketRepository.java`
- Modify: `ledger/SqliteLedgerRepository.java`
- Test: `market/SqliteMarketRepositoryTest.java`

- [ ] Write a real SQLite test that creates a `WHEAT` listing with 12 units, reserves a purchase of 5, records one trade, and asserts 7 remaining units and one immutable trade row.
- [ ] Run the test and observe the missing repository/type compilation failure.
- [ ] Create `market_listings` and `market_trades` schema tables; implement an atomic `settlePurchase(listingId, buyerId, quantity, feeRate)` operation that locks the active listing, rejects seller self-purchase and insufficient remaining quantity, decrements it, and inserts the trade data.
- [ ] Run the repository test green, then add rejection tests for self-purchase and stale/empty listing; commit.

### Task 2: Market service and money settlement

**Files:**
- Create: `market/MarketService.java`, `market/MarketPurchase.java`, `market/MarketResult.java`
- Test: `market/MarketServiceTest.java`

- [ ] Write a failing test that funds a buyer with 10,000 cents, sells 10 wheat at 100 cents, buys 5 at a 2% fee, and asserts: buyer 9,500; seller 490; treasury 10; listing quantity 5.
- [ ] Run it red because `MarketService` is missing.
- [ ] Implement `createListing`, `buy`, `cancel`, and `browse`; validate positive price/quantity, checked multiplication and 0–20% fee. Use two batch postings: buyer-to-seller net and buyer-to-treasury fee; omit the fee posting only when fee is zero.
- [ ] Run all service tests green, including insufficient buyer funds and self-buy no-change assertions; commit.

### Task 3: Paper command, GUI and inventory escrow

**Files:**
- Create: `paper/MarketCommand.java`, `paper/MarketMenu.java`
- Modify: `CentralEconomyPlugin.java`, `plugin.yml`, `config.yml`
- Create: `market.yml`
- Test: `paper/MarketMenuTest.java`

- [ ] Write a failing MockBukkit test that opens `/market`, verifies the custom market holder, and uses a 12-wheat plain stack to list 10 units without accepting a named wheat stack.
- [ ] Run the test red because `MarketMenu` is missing.
- [ ] Implement `/market`, `/market sell <price>`, `/market mine`; remove listed materials before persistence and restore a cloned inventory snapshot if creation fails. On purchase, preflight inventory capacity, invoke service settlement, then give only the purchased plain Material. Implement a confirm screen and cancel all menu click/drag movement.
- [ ] Run focused GUI tests and the complete test suite green; update README with market commands; commit.

## Self-review

- Task 1 covers listing/trade persistence and stale-listing rejection.
- Task 2 covers financial conservation and all money failures.
- Task 3 covers physical item escrow, GUI containment and configuration.
- No later task bypasses `LedgerRepository`; all type names and method responsibilities are consistent with the design.
