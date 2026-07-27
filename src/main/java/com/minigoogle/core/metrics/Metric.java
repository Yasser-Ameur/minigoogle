package com.minigoogle.core.metrics;

import java.time.Instant;
import java.util.Objects;

public record Metric(String name, double value, Instant timestamp) {
    public Metric {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(timestamp, "timestamp");
    }

    public static Metric of(String name, double value) {
        return new Metric(name, value, Instant.now());
    }

    public static Metric of(String name, double value, Instant timestamp) {
        return new Metric(name, value, timestamp);
    }
}
