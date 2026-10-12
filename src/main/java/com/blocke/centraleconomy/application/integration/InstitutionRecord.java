package com.blocke.centraleconomy.application.integration;

import com.blocke.bloeco.api.InstitutionCapability;
import com.blocke.centraleconomy.domain.account.AccountId;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Persisted institution ownership, capabilities, and Bloeco-owned account identities. */
public record InstitutionRecord(
        String clientId,
        String displayName,
        String pluginName,
        String pluginMainClass,
        String pluginVersion,
        int apiMajor,
        Status status,
        Set<InstitutionCapability> capabilities,
        Map<String, AccountId> accounts,
        Instant createdAt,
        Instant updatedAt) {

    public InstitutionRecord {
        Objects.requireNonNull(clientId, "clientId");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(pluginName, "pluginName");
        Objects.requireNonNull(pluginMainClass, "pluginMainClass");
        Objects.requireNonNull(pluginVersion, "pluginVersion");
        Objects.requireNonNull(status, "status");
        capabilities = Set.copyOf(Objects.requireNonNull(capabilities, "capabilities"));
        accounts = Map.copyOf(Objects.requireNonNull(accounts, "accounts"));
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    public enum Status { ACTIVE, FROZEN, CLOSED }
}
