package com.blocke.centraleconomy.storage.mysql.repository;

import com.blocke.centraleconomy.storage.sqlite.SqliteBankingStore;

import java.sql.Connection;
import java.time.Clock;
import java.util.Objects;

/** Creates a banking repository session on the caller-owned MySQL transaction connection. */
public final class BankRepository {
    private final Clock clock;
    public BankRepository(Clock clock) { this.clock = Objects.requireNonNull(clock, "clock"); }
    public SqliteBankingStore session(Connection connection) {
        return new SqliteBankingStore(connection, clock, true, false, false);
    }
}
