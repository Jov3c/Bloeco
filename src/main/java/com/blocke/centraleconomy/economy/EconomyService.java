package com.blocke.centraleconomy.economy;

import com.blocke.centraleconomy.ledger.AccountId;
import com.blocke.centraleconomy.ledger.LedgerRepository;
import com.blocke.centraleconomy.ledger.TransactionType;
import com.blocke.centraleconomy.money.Money;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Domain use cases for issuing, burning, and treasury procurement. */
public final class EconomyService {
    private final LedgerRepository ledger;
    private final int procurementIncomeTaxRatePercent;

    public EconomyService(LedgerRepository ledger, int procurementIncomeTaxRatePercent) {
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        if (procurementIncomeTaxRatePercent < 0 || procurementIncomeTaxRatePercent > 100) {
            throw new IllegalArgumentException("procurement income tax rate must be between 0 and 100");
        }
        this.procurementIncomeTaxRatePercent = procurementIncomeTaxRatePercent;
    }

    public synchronized void issueToTreasury(Money amount, String memo) {
        ledger.transfer(AccountId.issuance(), AccountId.treasury(), amount, TransactionType.ISSUE, memo);
    }

    public synchronized void burnFromTreasury(Money amount, String memo) {
        ledger.transfer(AccountId.treasury(), AccountId.burn(), amount, TransactionType.BURN, memo);
    }

    public synchronized ProcurementQuote quoteProcurement(UUID playerId, ProcurementItem item, int quantity) {
        Objects.requireNonNull(playerId, "playerId");
        validateItemAndQuantity(item, quantity);
        Money gross = grossFor(item, quantity);
        return new ProcurementQuote(playerId, item, quantity, gross, taxFor(gross));
    }

    public synchronized ProcurementResult settleProcurement(UUID playerId, ProcurementQuote quote) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(quote, "quote");
        if (!playerId.equals(quote.playerId())) {
            throw new IllegalArgumentException("procurement quote belongs to a different player");
        }

        validateItemAndQuantity(quote.item(), quote.quantity());
        Money expectedGross = grossFor(quote.item(), quote.quantity());
        Money expectedTax = taxFor(expectedGross);
        if (!expectedGross.equals(quote.gross()) || !expectedTax.equals(quote.tax())) {
            throw new IllegalArgumentException("procurement quote does not match current terms");
        }
        if (ledger.balance(AccountId.treasury()).cents() < expectedGross.cents()) {
            throw new IllegalStateException("treasury has insufficient funds for procurement");
        }

        List<LedgerRepository.Posting> postings = new ArrayList<>();
        postings.add(new LedgerRepository.Posting(
                AccountId.treasury(), AccountId.player(playerId), expectedGross,
                TransactionType.PROCUREMENT_GROSS, quote.item().materialKey() + " gross"));
        if (expectedTax.cents() > 0) {
            postings.add(new LedgerRepository.Posting(
                    AccountId.player(playerId), AccountId.treasury(), expectedTax,
                    TransactionType.PROCUREMENT_TAX, quote.item().materialKey() + " tax"));
        }
        ledger.transferBatch(postings);

        return new ProcurementResult(
                expectedGross,
                expectedTax,
                Money.ofCents(expectedGross.cents() - expectedTax.cents()));
    }

    public synchronized Money treasuryBalance() {
        return ledger.balance(AccountId.treasury());
    }

    public synchronized Money playerBalance(UUID playerId) {
        return ledger.balance(AccountId.player(Objects.requireNonNull(playerId, "playerId")));
    }

    private void validateItemAndQuantity(ProcurementItem item, int quantity) {
        Objects.requireNonNull(item, "item");
        if (!item.enabled()) {
            throw new IllegalArgumentException("procurement item is disabled");
        }
        if (quantity < 1 || quantity > item.maxPerSale()) {
            throw new IllegalArgumentException("quantity must be between 1 and the item's maximum sale quantity");
        }
    }

    private static Money grossFor(ProcurementItem item, int quantity) {
        try {
            return Money.ofCents(Math.multiplyExact(item.unitPrice().cents(), (long) quantity));
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("procurement gross exceeds supported range", exception);
        }
    }

    private Money taxFor(Money gross) {
        long fullHundreds = gross.cents() / 100;
        long remainder = gross.cents() % 100;
        try {
            long wholeTax = Math.multiplyExact(fullHundreds, procurementIncomeTaxRatePercent);
            long remainderTax = (remainder * procurementIncomeTaxRatePercent) / 100;
            return Money.ofCents(Math.addExact(wholeTax, remainderTax));
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("procurement tax exceeds supported range", exception);
        }
    }
}
