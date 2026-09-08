package com.blocke.centraleconomy.application.banking;

import com.blocke.centraleconomy.domain.banking.BankSnapshot;
import com.blocke.centraleconomy.domain.banking.BankingPlayerSnapshot;
import com.blocke.centraleconomy.domain.banking.BankingPolicy;
import com.blocke.centraleconomy.domain.banking.BankingReceipt;
import com.blocke.centraleconomy.domain.banking.LoanReceipt;
import com.blocke.centraleconomy.domain.money.Money;
import java.util.UUID;

public interface BankingStore extends AutoCloseable {
    void initialize(Money initialCapital, BankingPolicy defaults);
    BankingReceipt deposit(UUID playerId, Money amount, String idempotencyKey);
    BankingReceipt withdraw(UUID playerId, Money amount, String idempotencyKey);
    LoanReceipt borrow(UUID playerId, Money principal, String idempotencyKey);
    BankingReceipt repay(UUID playerId, UUID loanId, Money amount, String idempotencyKey);
    BankingPlayerSnapshot playerSnapshot(UUID playerId);
    BankSnapshot bankSnapshot();
    BankingPolicy updatePolicy(BankingPolicy policy, String actorId);
    @Override void close();
}
