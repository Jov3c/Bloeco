package com.blocke.centraleconomy.paper;

import com.blocke.bloeco.api.ApiVersion;
import com.blocke.bloeco.api.BloecoApi;
import com.blocke.centraleconomy.CentralEconomyPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class BloecoApiProviderTest {
    @AfterEach void close(){if(MockBukkit.isMocked())MockBukkit.unmock();}

    @Test
    void serviceAppearsAfterReadinessAndUnregistersOnDisable(){
        var server=MockBukkit.mock();
        CentralEconomyPlugin plugin=MockBukkit.load(CentralEconomyPlugin.class);
        plugin.runtime().readyStage().toCompletableFuture().join();
        BloecoApi api=server.getServicesManager().load(BloecoApi.class);
        assertNotNull(api);assertEquals(ApiVersion.CURRENT,api.version());
        server.getPluginManager().disablePlugin(plugin);
        assertNull(server.getServicesManager().load(BloecoApi.class));
    }
}
