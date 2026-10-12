package com.blocke.centraleconomy.application.integration;

import com.blocke.bloeco.api.ApiVersion;
import com.blocke.bloeco.api.InstitutionDescriptor;

import java.time.Clock;
import java.util.Objects;

/** Registers or restores one plugin-owned institution without exposing persistence details. */
public final class InstitutionService {
    private final EcosystemStore store;
    private final Clock clock;

    public InstitutionService(EcosystemStore store, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public InstitutionRecord registerOrResume(
            String pluginName, String pluginMainClass, String pluginVersion,
            InstitutionDescriptor descriptor) {
        return store.registerOrResume(new EcosystemStore.InstitutionRegistration(
                descriptor.clientId(), descriptor.displayName(),
                required(pluginName, "pluginName", 128),
                required(pluginMainClass, "pluginMainClass", 255),
                required(pluginVersion, "pluginVersion", 64),
                ApiVersion.CURRENT.major(), descriptor.capabilities(),
                descriptor.accountPurposes(), clock.instant()));
    }

    private static String required(String value, String label, int maximum) {
        String normalized = Objects.requireNonNull(value, label).trim();
        if (normalized.isEmpty() || normalized.length() > maximum) {
            throw new IllegalArgumentException(label + " must contain 1 through " + maximum + " characters");
        }
        return normalized;
    }
}
