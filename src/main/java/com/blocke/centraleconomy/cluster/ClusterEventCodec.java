package com.blocke.centraleconomy.cluster;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ClusterEventCodec {
    private ClusterEventCodec() { }

    public static String encode(PlayerNotification value) {
        return "{\"notification_id\":\"" + value.notificationId()
                + "\",\"entry_id\":\"" + value.entryId()
                + "\",\"player_id\":\"" + value.playerId()
                + "\",\"amount_minor\":" + value.amountMinor()
                + ",\"created_at_epoch_ms\":" + value.createdAt().toEpochMilli() + "}";
    }

    public static Optional<PlayerNotification> decodePlayerNotification(String json) {
        if (json == null) return Optional.empty();
        try {
            UUID notification = UUID.fromString(string(json, "notification_id"));
            UUID entry = UUID.fromString(string(json, "entry_id"));
            UUID player = UUID.fromString(string(json, "player_id"));
            long amount = number(json, "amount_minor");
            long createdAt = number(json, "created_at_epoch_ms");
            return Optional.of(new PlayerNotification(notification, entry, player, amount,
                    Instant.ofEpochMilli(createdAt)));
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
    }

    private static String string(String json, String key) {
        Matcher matcher = Pattern.compile("\\\"" + key + "\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").matcher(json);
        if (!matcher.find()) throw new IllegalArgumentException("missing " + key);
        return matcher.group(1);
    }

    private static long number(String json, String key) {
        Matcher matcher = Pattern.compile("\\\"" + key + "\\\"\\s*:\\s*(-?\\d+)").matcher(json);
        if (!matcher.find()) throw new IllegalArgumentException("missing " + key);
        return Long.parseLong(matcher.group(1));
    }
}
