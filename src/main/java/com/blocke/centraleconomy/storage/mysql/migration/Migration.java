package com.blocke.centraleconomy.storage.mysql.migration;

import java.sql.Connection;

/** An immutable, ordered database schema change. Existing versions must never be edited. */
public interface Migration {
    int version();
    String description();
    void migrate(Connection connection) throws Exception;
}
