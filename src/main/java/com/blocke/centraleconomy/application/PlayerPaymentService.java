package com.blocke.centraleconomy.application;

import com.blocke.centraleconomy.application.command.PlayerPayment;
import com.blocke.centraleconomy.application.result.TransferReceipt;
import com.blocke.centraleconomy.domain.account.Account;
import com.blocke.centraleconomy.domain.account.AccountId;
import com.blocke.centraleconomy.domain.ledger.JournalEntry;
import com.blocke.centraleconomy.domain.ledger.JournalType;
import com.blocke.centraleconomy.domain.ledger.LedgerException;
import com.blocke.centraleconomy.domain.ledger.Posting;
import com.blocke.centraleconomy.domain.money.Money;
import com.blocke.centraleconomy.domain.tax.TaxCategory;
import com.blocke.centraleconomy.domain.tax.TaxRule;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Clears principal, fee, and income tax in one immutable journal. */
public final class PlayerPaymentService {
    public static final String CLIENT_ID = "bloeco.player";

    private final LedgerStore store;
    private final TaxRuleService taxes;
    private final Clock clock;

    public PlayerPaymentService(LedgerStore store, TaxRuleService taxes, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.taxes = Objects.requireNonNull(taxes, "taxes");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public TransferReceipt pay(PlayerPayment command) {
        Objects.requireNonNull(command, "command");
        var replay = store.playerSettlement(CLIENT_ID, command.idempotencyKey());
        if (replay.isPresent()) {
            if (!replay.get().sameCommand(command)) {
                throw new LedgerException(LedgerException.Code.IDEMPOTENCY_CONFLICT,
                        "idempotency key was already used for a different payment");
            }
            return replay.get().receipt();
        }

        store.createAccount(Account.player(command.senderId()));
        store.createAccount(Account.player(command.recipientId()));
        TaxRule feeRule = taxes.current(TaxCategory.PLAYER_TRANSFER_FEE);
        TaxRule incomeRule = taxes.current(TaxCategory.PLAYER_TRANSFER_INCOME);
        Money fee = feeRule.charge(command.principal());
        Money incomeTax = incomeRule.charge(command.principal());
        if (incomeTax.minor() > command.principal().minor()) {
            throw new LedgerException(LedgerException.Code.POLICY_REJECTED,
                    "income tax exceeds the payment principal");
        }
        Money senderDebit = command.principal().plus(fee);
        if (store.balance(AccountId.player(command.senderId())) < senderDebit.minor()) {
            throw new LedgerException(LedgerException.Code.INSUFFICIENT_FUNDS,
                    "sender has insufficient funds for principal and fee");
        }
        Money recipientNet = command.principal().minus(incomeTax);

        List<Posting> postings = new ArrayList<>();
        postings.add(new Posting(AccountId.player(command.senderId()), -senderDebit.minor()));
        addIfPositive(postings, AccountId.player(command.recipientId()), recipientNet);
        addIfPositive(postings, feeRule.destinationAccountId(), fee);
        addIfPositive(postings, incomeRule.destinationAccountId(), incomeTax);
        UUID entryId = UUID.randomUUID();
        JournalEntry entry = JournalEntry.create(entryId, JournalType.PLAYER_TRANSFER, command.memo(),
                CLIENT_ID, command.idempotencyKey(), clock.instant(), postings);
        PlayerSettlement settlement = new PlayerSettlement(entryId, command.senderId(), command.recipientId(),
                command.principal(), senderDebit, recipientNet, fee, incomeTax,
                feeRule.versionId(), incomeRule.versionId(), command.memo());
        return store.commitPlayerSettlement(entry, settlement).receipt();
    }

    private static void addIfPositive(List<Posting> postings, AccountId accountId, Money amount) {
        if (amount.minor() > 0) postings.add(new Posting(accountId, amount.minor()));
    }
}
