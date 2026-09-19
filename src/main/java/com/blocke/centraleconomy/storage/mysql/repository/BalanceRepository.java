package com.blocke.centraleconomy.storage.mysql.repository;

import com.blocke.centraleconomy.domain.account.AccountId;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Balance SQL only; transaction ownership belongs to MySqlTransactionManager. */
public final class BalanceRepository {
    public Map<AccountId, StoredAccount> lockInOrder(Connection connection, List<AccountId> accountIds)
            throws SQLException {
        Map<AccountId, StoredAccount> result = new HashMap<>();
        if (accountIds.isEmpty()) return result;
        List<AccountId> ordered = accountIds.stream().distinct().sorted().toList();
        String placeholders = String.join(",", Collections.nCopies(ordered.size(), "?"));
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT a.account_id, a.status, a.permits_negative, b.balance_minor, b.version
                FROM accounts a JOIN account_balances b ON b.account_id = a.account_id
                WHERE a.account_id IN (%s)
                ORDER BY a.account_id
                FOR UPDATE
                """.formatted(placeholders))) {
            for (int index = 0; index < ordered.size(); index++) {
                statement.setString(index + 1, ordered.get(index).value());
            }
            try (ResultSet row = statement.executeQuery()) {
                while (row.next()) {
                    AccountId id = new AccountId(row.getString(1));
                    result.put(id, new StoredAccount(row.getString(2), row.getInt(3) == 1,
                            row.getLong(4), row.getLong(5)));
                }
            }
        }
        return result;
    }

    public void update(Connection connection, Map<AccountId, Long> balances) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE account_balances SET balance_minor=?, version=version+1 WHERE account_id=?")) {
            for (Map.Entry<AccountId, Long> balance : balances.entrySet()) {
                statement.setLong(1, balance.getValue());
                statement.setString(2, balance.getKey().value());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    public record StoredAccount(String status, boolean permitsNegative, long balance, long version) { }
}
