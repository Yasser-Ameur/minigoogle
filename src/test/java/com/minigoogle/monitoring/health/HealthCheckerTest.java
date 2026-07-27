package com.minigoogle.monitoring.health;

import com.minigoogle.network.http.RestClient;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for HealthChecker functionality. */
class HealthCheckerTest {

    @Test
    void testCheckUnreachableNodeReturnsUnhealthy() {
        RestClient client = new RestClient();
        HealthChecker checker = new HealthChecker(client, 1000);

        HealthStatus status = checker.check("node-1", "http://localhost:19999");
        assertEquals(HealthStatus.Status.UNHEALTHY, status.status());
        assertEquals("node-1", status.nodeId());
        assertFalse(status.isHealthy());
    }

    @Test
    void testGetLastResultCachesCheck() {
        RestClient client = new RestClient();
        HealthChecker checker = new HealthChecker(client, 1000);

        checker.check("node-1", "http://localhost:19999");
        HealthStatus cached = checker.getLastResult("node-1");
        assertNotNull(cached);
        assertEquals(HealthStatus.Status.UNHEALTHY, cached.status());
    }

    @Test
    void testGetLastResultReturnsNullForUnknownNode() {
        RestClient client = new RestClient();
        HealthChecker checker = new HealthChecker(client);
        assertNull(checker.getLastResult("nonexistent"));
    }

    @Test
    void testGetAllResultsReturnsAllChecked() {
        RestClient client = new RestClient();
        HealthChecker checker = new HealthChecker(client, 1000);

        checker.check("n1", "http://localhost:19998");
        checker.check("n2", "http://localhost:19997");

        Map<String, HealthStatus> results = checker.getAllResults();
        assertEquals(2, results.size());
        assertTrue(results.containsKey("n1"));
        assertTrue(results.containsKey("n2"));
    }
}
