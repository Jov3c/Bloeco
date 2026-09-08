package com.blocke.centraleconomy;

import org.mockbukkit.mockbukkit.MockBukkit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginBootstrapTest {

    @BeforeEach
    void setUpMockServer() {
        MockBukkit.mock();
    }

    @AfterEach
    void tearDownMockServer() {
        MockBukkit.unmock();
    }

    @Test
    void pluginLoadsWithDefaultResources() {
        var plugin = MockBukkit.load(CentralEconomyPlugin.class);

        assertNotNull(plugin);
        assertTrue(plugin.getDataFolder().toPath().resolve("config.yml").toFile().isFile());
        assertNotNull(plugin.getCommand("bloeco"));
        assertNull(plugin.getCommand("market"));
        assertFalse(plugin.getDataFolder().toPath().resolve("procurement.yml").toFile().exists());
        assertFalse(plugin.getDataFolder().toPath().resolve("market.yml").toFile().exists());
    }
}
