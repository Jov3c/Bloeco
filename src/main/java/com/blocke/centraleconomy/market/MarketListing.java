package com.blocke.centraleconomy.market;

import com.blocke.centraleconomy.money.Money;
import org.bukkit.Material;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Immutable snapshot of one seller's material-only market listing. */
public record MarketListing(
        UUID id,
        UUID sellerId,
        Material material,
        int originalQuantity,
        int remainingQuantity,
        Money unitPrice,
        Status status,
        Instant createdAt) {

    public MarketListing {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(sellerId, "sellerId");
        Objects.requireNonNull(material, "material");
        if (originalQuantity < 1) {
            throw new IllegalArgumentException("original quantity must be positive");
        }
        if (remainingQuantity < 0 || remainingQuantity > originalQuantity) {
            throw new IllegalArgumentException("remaining quantity must be within the original quantity");
        }
        Objects.requireNonNull(unitPrice, "unitPrice");
        if (unitPrice.cents() == 0) {
            throw new IllegalArgumentException("unit price must be positive");
        }
        Objects.requireNonNull(status, "status");
        if (status == Status.ACTIVE && remainingQuantity == 0) {
            throw new IllegalArgumentException("an active listing must have remaining quantity");
        }
        if (status == Status.SOLD && remainingQuantity != 0) {
            throw new IllegalArgumentException("a sold listing must have no remaining quantity");
        }
        Objects.requireNonNull(createdAt, "createdAt");
    }

    public enum Status {
        ACTIVE,
        SOLD,
        CANCELLED
    }
}
