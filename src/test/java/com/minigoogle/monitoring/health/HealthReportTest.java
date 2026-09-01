package com.minigoogle.monitoring.health;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests for {@link HealthReport}. */
class HealthReportTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void okWhenThereAreNoChecks() {
        HealthReport report = HealthReport.builder("1.0.0").build();
        assertEquals(HealthStatus.Status.HEALTHY, report.status());
        assertEquals(200, report.httpStatus());
    }

    @Test
    void statusIsWorstOfChecks() {
        HealthReport report = HealthReport.builder("1.0.0")
                .check("index", HealthStatus.Status.HEALTHY, Map.of())
                .check("disk", HealthStatus.Status.DEGRADED, Map.of("freeBytes", 100))
                .build();
        assertEquals(HealthStatus.Status.DEGRADED, report.status());
        assertEquals(200, report.httpStatus());
    }

    @Test
    void unhealthyMapsTo503() {
        HealthReport report = HealthReport.builder("1.0.0")
                .check("index", HealthStatus.Status.HEALTHY, Map.of())
                .check("crawler", HealthStatus.Status.UNHEALTHY, Map.of("reason", "down"))
                .build();
        assertEquals(HealthStatus.Status.UNHEALTHY, report.status());
        assertEquals(503, report.httpStatus());
    }

    @Test
    void jsonShapeIsExact() throws Exception {
        HealthReport report = HealthReport.builder("1.0.0")
                .check("index", HealthStatus.Status.HEALTHY, Map.of("documents", 42))
                .build();

        JsonNode root = MAPPER.readTree(report.toJson());
        assertEquals("ok", root.get("status").asText());
        assertEquals("1.0.0", root.get("version").asText());
        assertTrue(root.get("uptimeSeconds").isNumber());
        assertTrue(root.get("checks").isObject());

        JsonNode indexCheck = root.get("checks").get("index");
        assertEquals("ok", indexCheck.get("status").asText());
        assertEquals(42, indexCheck.get("documents").asInt());

        assertEquals(4, root.size());
    }
}
