package com.blocke.centraleconomy.paper;

import com.blocke.bloeco.api.ApiVersion;
import com.blocke.bloeco.api.BloecoApi;
import com.blocke.bloeco.api.EconomyResult;
import com.blocke.bloeco.api.InstitutionClient;
import com.blocke.bloeco.api.InstitutionDescriptor;
import com.blocke.centraleconomy.application.integration.AsyncInstitutionFacade;
import org.bukkit.plugin.Plugin;

import java.util.Objects;
import java.util.concurrent.CompletionStage;

/** Paper service provider that binds the caller's real plugin identity to one institution. */
public final class BloecoApiProvider implements BloecoApi {
    private final AsyncInstitutionFacade facade;
    public BloecoApiProvider(AsyncInstitutionFacade facade){this.facade=Objects.requireNonNull(facade,"facade");}
    @Override public ApiVersion version(){return ApiVersion.CURRENT;}
    @Override public CompletionStage<EconomyResult<InstitutionClient>> connect(
            Plugin plugin, InstitutionDescriptor descriptor){
        Objects.requireNonNull(plugin,"plugin");Objects.requireNonNull(descriptor,"descriptor");
        return facade.connect(plugin.getName(),plugin.getDescription().getMain(),plugin.getPluginMeta().getVersion(),descriptor);
    }
}
