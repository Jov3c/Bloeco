package com.blocke.centraleconomy.cluster;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ClusterSettingsTest {
    @Test
    void enabledClusterRequiresSafeUniqueIdentifiersAndARealLeaseWindow() {
        assertThrows(IllegalArgumentException.class, () -> new ClusterSettings(
                true, "production network", "survival-01", Duration.ofSeconds(10),
                Duration.ofSeconds(30), "bloeco:v2:cluster:"));
        assertThrows(IllegalArgumentException.class, () -> new ClusterSettings(
                true, "production", "Survival 01", Duration.ofSeconds(10),
                Duration.ofSeconds(30), "bloeco:v2:cluster:"));
        assertThrows(IllegalArgumentException.class, () -> new ClusterSettings(
                true, "production", "survival-01", Duration.ofSeconds(10),
                Duration.ofSeconds(20), "bloeco:v2:cluster:"));

        ClusterSettings settings = new ClusterSettings(
                true, "production", "survival-01", Duration.ofSeconds(10),
                Duration.ofSeconds(30), "bloeco:v2:cluster:");

        assertEquals("bloeco:v2:cluster:production:survival-01", settings.consumerGroup());
        assertEquals("survival-01", settings.consumerName());
    }
}
