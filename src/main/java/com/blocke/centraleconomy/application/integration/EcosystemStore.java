package com.blocke.centraleconomy.application.integration;

import com.blocke.bloeco.api.InstitutionCapability;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

/** Persistence boundary for Bloeco-compatible institution settlement features. */
public interface EcosystemStore extends AutoCloseable {
    InstitutionRecord registerOrResume(InstitutionRegistration registration);
    Optional<InstitutionRecord> institution(String clientId);
    @Override void close();

    record InstitutionRegistration(
            String clientId,
            String displayName,
            String pluginName,
            String pluginMainClass,
            String pluginVersion,
            int apiMajor,
            Set<InstitutionCapability> capabilities,
            Set<String> accountPurposes,
            Instant registeredAt) {
        public InstitutionRegistration {
            capabilities = Set.copyOf(capabilities);
            accountPurposes = Set.copyOf(accountPurposes);
        }
    }
}
