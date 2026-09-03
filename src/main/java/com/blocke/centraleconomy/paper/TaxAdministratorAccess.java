package com.blocke.centraleconomy.paper;

import org.bukkit.entity.Player;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Configurable tax-administrator check: permission nodes and explicitly named UUIDs are both supported. */
public final class TaxAdministratorAccess {
    private final String permission;
    private final Set<UUID> administratorIds;

    public TaxAdministratorAccess(String permission, Set<UUID> administratorIds) {
        this.permission = Objects.requireNonNull(permission, "permission").trim();
        if (this.permission.isEmpty()) {
            throw new IllegalArgumentException("tax administrator permission must not be blank");
        }
        this.administratorIds = Set.copyOf(Objects.requireNonNull(administratorIds, "administratorIds"));
    }

    public boolean allows(Player player) {
        Objects.requireNonNull(player, "player");
        return player.hasPermission(permission) || administratorIds.contains(player.getUniqueId());
    }

    public String permission() {
        return permission;
    }
}
