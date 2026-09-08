package com.blocke.centraleconomy.economy;

import com.blocke.centraleconomy.ledger.AccountId;
import com.blocke.centraleconomy.ledger.LedgerRepository;
import com.blocke.centraleconomy.ledger.TransactionType;
import com.blocke.centraleconomy.money.Money;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Domain use cases for controlled money supply, Treasury and player transfers. */
public final class EconomyService {
    private final LedgerRepository ledger;
    private final TaxPolicy taxPolicy;

    public EconomyService(LedgerRepository ledger) {
        this(ledger, new TaxPolicy(1, 5));
    }

    public EconomyService(
            LedgerRepository ledger,
            int transferFeeRatePercent,
            int transferIncomeTaxRatePercent) {
        this(ledger, new TaxPolicy(transferFeeRatePercent, transferIncomeTaxRatePercent));
    }

    public EconomyService(LedgerRepository ledger, TaxPolicy taxPolicy) {
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.taxPolicy = Objects.requireNonNull(taxPolicy, "taxPolicy");
    }

    public synchronized void issueToTreasury(Money amount, String memo) {
        ledger.transfer(AccountId.issuance(), AccountId.treasury(), amount, TransactionType.ISSUE, memo);
    }

    public synchronized void burnFromTreasury(Money amount, String memo) {
        ledger.transfer(AccountId.treasury(), AccountId.burn(), amount, TransactionType.BURN, memo);
    }

    /** Transfers player funds while recording the sender fee and recipient income tax. */
    public synchronized PlayerTransferResult transferPlayerFunds(UUID sender, UUID recipient, Money amount, String memo) {
        Objects.requireNonNull(sender, "sender");
        Objects.requireNonNull(recipient, "recipient");
        Objects.requireNonNull(amount, "amount");
        if (sender.equals(recipient)) {
            throw new IllegalArgumentException("cannot transfer money to yourself");
        }
        if (amount.cents() == 0) {
            throw new IllegalArgumentException("transfer amount must be positive");
        }

        Money fee = percentageOf(amount, taxPolicy.rate(TaxType.TRANSFER_FEE), "transfer fee");
        Money incomeTax = percentageOf(amount, taxPolicy.rate(TaxType.TRANSFER_INCOME), "transfer income tax");
        long senderDebit = add(amount.cents(), fee.cents(), "transfer debit");
        if (ledger.balance(AccountId.player(sender)).cents() < senderDebit) {
            throw new IllegalStateException("insufficient funds for transfer amount plus fee");
        }

        List<LedgerRepository.Posting> postings = new ArrayList<>();
        long recipientNet = amount.cents() - incomeTax.cents();
        if (recipientNet > 0) {
            postings.add(new LedgerRepository.Posting(
                    AccountId.player(sender), AccountId.player(recipient), Money.ofCents(recipientNet),
                    TransactionType.PLAYER_TRANSFER, memo));
        }
        if (incomeTax.cents() > 0) {
            postings.add(new LedgerRepository.Posting(
                    AccountId.player(sender), AccountId.treasury(), incomeTax,
                    TransactionType.PERSONAL_INCOME_TAX, memo + " income tax"));
        }
        if (fee.cents() > 0) {
            postings.add(new LedgerRepository.Posting(
                    AccountId.player(sender), AccountId.treasury(), fee,
                    TransactionType.TRANSFER_FEE, memo + " transfer fee"));
        }
        ledger.transferBatch(postings);
        return new PlayerTransferResult(amount, fee, incomeTax, Money.ofCents(recipientNet), Money.ofCents(senderDebit));
    }

    public synchronized Money treasuryBalance() {
        return ledger.balance(AccountId.treasury());
    }

    public synchronized Money playerBalance(UUID playerId) {
        return ledger.balance(AccountId.player(Objects.requireNonNull(playerId, "playerId")));
    }

    private static Money percentageOf(Money amount, int ratePercent, String label) {
        long fullHundreds = amount.cents() / 100;
        long remainder = amount.cents() % 100;
        try {
            long wholeTax = Math.multiplyExact(fullHundreds, ratePercent);
            long remainderTax = (remainder * ratePercent) / 100;
            return Money.ofCents(Math.addExact(wholeTax, remainderTax));
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(label + " exceeds supported range", exception);
        }
    }

    private static long add(long first, long second, String label) {
        try {
            return Math.addExact(first, second);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(label + " exceeds supported range", exception);
        }
    }

}
