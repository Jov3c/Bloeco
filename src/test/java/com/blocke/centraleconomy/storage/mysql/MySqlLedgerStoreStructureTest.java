package com.blocke.centraleconomy.storage.mysql;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.sql.Connection;

import static org.junit.jupiter.api.Assertions.assertFalse;

class MySqlLedgerStoreStructureTest {
    @Test
    void doesNotHoldJdbcConnectionOrSynchronizeStoreMethods() {
        for (var field : MySqlLedgerStore.class.getDeclaredFields()) {
            assertFalse(Connection.class.isAssignableFrom(field.getType()),
                    () -> "long-lived JDBC connection field: " + field.getName());
        }
        for (var method : MySqlLedgerStore.class.getDeclaredMethods()) {
            assertFalse(Modifier.isSynchronized(method.getModifiers()),
                    () -> "store-level synchronized method: " + method.getName());
        }
    }
}
