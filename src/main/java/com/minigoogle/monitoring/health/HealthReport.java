package com.minigoogle.monitoring.health;

import com.minigoogle.network.serialization.JsonSerializer;

import java.lang.management.ManagementFactory;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Aggregate application health report, built from a set of named checks.
 *
 * The overall status is the worst of the individual checks (or OK when there
 * are none). Serialized to the JSON shape self-hosters and uptime probes
 * expect:
 *
 * <pre>
 * {"status":"ok","version":"1.0.0","uptimeSeconds":42,
 *  "checks":{"index":{"status":"ok"}}}
 * </pre>
 */
public final class HealthReport {

    private final String version;
    private final long uptimeSeconds;
    private final Map<String, CheckResult> checks;

    private HealthReport(String version, Map<String, CheckResult> checks) {
        this.version = version;
        this.uptimeSeconds = ManagementFactory.getRuntimeMXBean().getUptime() / 1000;
        this.checks = checks;
    }

    public static Builder builder(String version) {
        return new Builder(version);
    }

    public HealthStatus.Status status() {
        HealthStatus.Status worst = HealthStatus.Status.HEALTHY;
        for (CheckResult check : checks.values()) {
            if (rank(check.status) > rank(worst)) {
                worst = check.status;
            }
        }
        return worst;
    }

    public int httpStatus() {
        return status() == HealthStatus.Status.UNHEALTHY ? 503 : 200;
    }

    public String toJson() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("status", statusText(status()));
        root.put("version", version);
        root.put("uptimeSeconds", uptimeSeconds);

        Map<String, Object> checksJson = new LinkedHashMap<>();
        for (Map.Entry<String, CheckResult> entry : checks.entrySet()) {
            CheckResult check = entry.getValue();
            Map<String, Object> checkJson = new LinkedHashMap<>();
            checkJson.put("status", statusText(check.status));
            checkJson.putAll(check.details);
            checksJson.put(entry.getKey(), checkJson);
        }
        root.put("checks", checksJson);

        return JsonSerializer.toJson(root);
    }

    private static int rank(HealthStatus.Status status) {
        return switch (status) {
            case HEALTHY -> 0;
            case DEGRADED -> 1;
            case UNHEALTHY -> 2;
        };
    }

    private static String statusText(HealthStatus.Status status) {
        return switch (status) {
            case HEALTHY -> "ok";
            case DEGRADED -> "degraded";
            case UNHEALTHY -> "unhealthy";
        };
    }

    private record CheckResult(HealthStatus.Status status, Map<String, Object> details) {
    }

    public static final class Builder {
        private final String version;
        private final Map<String, CheckResult> checks = new LinkedHashMap<>();

        private Builder(String version) {
            this.version = version;
        }

        public Builder check(String name, HealthStatus.Status status, Map<String, Object> details) {
            checks.put(name, new CheckResult(status, details == null ? Map.of() : details));
            return this;
        }

        public HealthReport build() {
            return new HealthReport(version, checks);
        }
    }
}
