package com.blocke.centraleconomy.application;

import com.blocke.centraleconomy.application.command.PlayerPayment;
import com.blocke.centraleconomy.application.result.TransferReceipt;
import com.blocke.centraleconomy.domain.account.AccountId;
import com.blocke.centraleconomy.domain.ledger.LedgerException;
import com.blocke.centraleconomy.domain.money.Money;
import com.blocke.centraleconomy.domain.tax.TaxCategory;
import com.blocke.centraleconomy.storage.sqlite.SqliteLedgerStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PlayerPaymentServiceTest {
    private static final UUID SENDER = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID RECIPIENT = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-08T12:00:00Z"), ZoneOffset.UTC);

    @TempDir Path temporaryDirectory;
    private SqliteLedgerStore store;
    private CentralBankService bank;
    private TaxRuleService taxes;
    private PlayerPaymentService payments;

    @BeforeEach
    void setUp() {
        store = new SqliteLedgerStore(temporaryDirectory.resolve("economy.db"));
        bank = new CentralBankService(store, CLOCK);
        bank.initializeCentralAccounts();
        taxes = new TaxRuleService(store, CLOCK);
        taxes.initializeDefaults();
        payments = new PlayerPaymentService(store, taxes, CLOCK);
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    @Test
    void transferProducesOneBalancedFourLineJournal() {
        fundSender(20_000);

        TransferReceipt receipt = payments.pay(payment(10_000, "pay-1"));

        assertEquals(10_100L, receipt.senderDebit().minor());
        assertEquals(9_500L, receipt.recipientNet().minor());
        assertEquals(500L, receipt.incomeTax().minor());
        assertEquals(100L, receipt.fee().minor());
        assertEquals(9_900L, store.balance(AccountId.player(SENDER)));
        assertEquals(9_500L, store.balance(AccountId.player(RECIPIENT)));
        assertEquals(500L, store.balance(AccountId.taxRevenue()));
        assertEquals(100L, store.balance(AccountId.feeRevenue()));
        assertEquals(4, store.entry(receipt.entryId()).orElseThrow().postings().size());
    }

    @Test
    void insufficientFundsWritesNothing() {
        int before = store.entryCount();

        LedgerException failure = assertThrows(LedgerException.class,
                () -> payments.pay(payment(10_000, "pay-low")));

        assertEquals(LedgerException.Code.INSUFFICIENT_FUNDS, failure.code());
        assertEquals(before, store.entryCount());
    }

    @Test
    void replayAfterTaxChangeReturnsOriginalReceiptWithoutSecondDebit() {
        fundSender(20_000);
        TransferReceipt first = payments.pay(payment(10_000, "pay-replay"));
        taxes.change(TaxCategory.PLAYER_TRANSFER_FEE, 1_000, 0, "admin:tax", "raise fee");

        TransferReceipt replay = payments.pay(payment(10_000, "pay-replay"));

        assertEquals(first, replay);
        assertEquals(9_900L, store.balance(AccountId.player(SENDER)));
        assertEquals(1, store.entriesForKey("bloeco.player", "pay-replay"));
    }

    @Test
    void changedCommandCannotReuseAnIdempotencyKey() {
        fundSender(20_000);
        payments.pay(payment(10_000, "pay-conflict"));

        LedgerException failure = assertThrows(LedgerException.class,
                () -> payments.pay(payment(9_999, "pay-conflict")));

        assertEquals(LedgerException.Code.IDEMPOTENCY_CONFLICT, failure.code());
    }

    @Test
    void playerCannotPaySelf() {
        fundSender(20_000);
        assertThrows(IllegalArgumentException.class, () -> payments.pay(
                new PlayerPayment(SENDER, SENDER, Money.ofMinor(100), "self", "pay-self")));
    }

    private PlayerPayment payment(long principal, String key) {
        return new PlayerPayment(SENDER, RECIPIENT, Money.ofMinor(principal), "player gift", key);
    }

    private void fundSender(long amount) {
        UUID request = bank.requestIssuance(Money.ofMinor(amount), "admin:requester", "test funding");
        bank.approveIssuance(request, "admin:approver");
        bank.executeIssuance(request, "admin:requester", "issue-sender-" + amount);
        bank.adjustPlayerBalance(SENDER, Money.ofMinor(amount), "admin:treasurer", "fund sender",
                "fund-sender-" + amount);
    }
}
