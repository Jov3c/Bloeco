package com.blocke.bloeco.api;

import org.bukkit.plugin.Plugin;

import java.util.concurrent.CompletionStage;

/** Entry point registered by Bloeco through Paper's ServicesManager. */
public interface BloecoApi {
    ApiVersion version();
    CompletionStage<EconomyResult<InstitutionClient>> connect(
            Plugin plugin, InstitutionDescriptor descriptor);
}
