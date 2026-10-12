package com.blocke.bloeco.api;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Permanent institution identity and the capabilities/accounts requested by a consumer plugin. */
public record InstitutionDescriptor(
        String clientId,
        String displayName,
        Set<InstitutionCapability> capabilities,
        Set<String> accountPurposes) {

    public InstitutionDescriptor {
        clientId = safeIdentifier(clientId, "clientId");
        displayName = Objects.requireNonNull(displayName, "displayName").trim();
        if (displayName.isEmpty() || displayName.length() > 128) {
            throw new IllegalArgumentException("displayName must contain 1 through 128 characters");
        }
        capabilities = Set.copyOf(Objects.requireNonNull(capabilities, "capabilities"));
        LinkedHashSet<String> purposes = new LinkedHashSet<>();
        purposes.add("operating");
        Objects.requireNonNull(accountPurposes, "accountPurposes")
                .forEach(purpose -> purposes.add(safeIdentifier(purpose, "account purpose")));
        accountPurposes = Set.copyOf(purposes);
    }

    public static InstitutionDescriptor of(String clientId, String displayName) {
        return new InstitutionDescriptor(clientId, displayName,
                Set.of(InstitutionCapability.READ_OWN_BALANCE,
                        InstitutionCapability.READ_OWN_JOURNAL), Set.of("operating"));
    }

    static String safeIdentifier(String value, String label) {
        String normalized = Objects.requireNonNull(value, label).trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches("[a-z0-9][a-z0-9._-]{0,63}")) {
            throw new IllegalArgumentException(label + " must be a safe identifier");
        }
        return normalized;
    }
}
