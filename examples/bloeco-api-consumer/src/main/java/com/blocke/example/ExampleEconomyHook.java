package com.blocke.example;

import com.blocke.bloeco.api.BloecoApi;
import com.blocke.bloeco.api.InstitutionCapability;
import com.blocke.bloeco.api.InstitutionClient;
import com.blocke.bloeco.api.InstitutionDescriptor;
import com.blocke.bloeco.api.MoneyAmount;
import com.blocke.bloeco.api.OperationContext;
import com.blocke.bloeco.api.SettlementKind;
import org.bukkit.plugin.Plugin;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/** Minimal consumer adapter. Do not copy or shade Bloeco API classes into the consumer JAR. */
public final class ExampleEconomyHook {
    private final Plugin plugin;
    private volatile InstitutionClient client;

    public ExampleEconomyHook(Plugin plugin) { this.plugin = plugin; }

    public CompletionStage<Boolean> connect() {
        BloecoApi api = plugin.getServer().getServicesManager().load(BloecoApi.class);
        if (api == null || !api.version().supportsMajor(1)) {
            return java.util.concurrent.CompletableFuture.completedFuture(false);
        }
        InstitutionDescriptor descriptor = new InstitutionDescriptor(
                "example.shop", "示例商店",
                Set.of(InstitutionCapability.CHARGE_PLAYER, InstitutionCapability.REFUND,
                        InstitutionCapability.READ_OWN_BALANCE, InstitutionCapability.READ_OWN_JOURNAL),
                Set.of("sales"));
        return api.connect(plugin, descriptor).thenApply(result -> {
            if (!result.isSuccess()) return false;
            client = result.value();
            return true;
        });
    }

    public CompletionStage<Boolean> charge(UUID playerId, long amountMinor, String orderId) {
        InstitutionClient connected = java.util.Objects.requireNonNull(client, "Bloeco is not connected");
        OperationContext context = new OperationContext(
                "shop.purchase", orderId, "在示例商店购买商品", "order:" + orderId + ":payment");
        return connected.chargePlayer(playerId, connected.account("sales").orElseThrow(),
                MoneyAmount.positive(amountMinor), SettlementKind.PURCHASE, context)
                .thenApply(result -> result.isSuccess());
    }
}
