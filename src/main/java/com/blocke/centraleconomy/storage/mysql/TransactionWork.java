package com.blocke.centraleconomy.storage.mysql;

import java.sql.Connection;

@FunctionalInterface
public interface TransactionWork<T> {
    T execute(Connection connection) throws Exception;
}
