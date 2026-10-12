package com.blocke.centraleconomy.domain.account;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginAccountIdTest {
    @Test
    void longPublicIdentifiersProduceStableMysqlSafeOpaqueAccountId() {
        String client="a".repeat(64), purpose="b".repeat(64);
        AccountId first=AccountId.plugin(client,purpose);
        assertEquals(first,AccountId.plugin(client,purpose));
        assertTrue(first.value().length()<=96);
    }
}
