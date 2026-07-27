package com.minigoogle.monitoring.logging;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for StructuredLogger functionality. */
class StructuredLoggerTest {

    private ByteArrayOutputStream output;
    private StructuredLogger logger;

    @BeforeEach
    void setUp() {
        output = new ByteArrayOutputStream();
        logger = new StructuredLogger("node-1", new PrintStream(output));
    }

    @Test
    void testInfoLog() {
        logger.info("req-1", "search completed");
        String logged = output.toString();
        assertTrue(logged.contains("INFO"));
        assertTrue(logged.contains("node-1"));
        assertTrue(logged.contains("search completed"));
    }

    @Test
    void testErrorLog() {
        logger.error("req-2", "timeout occurred");
        String logged = output.toString();
        assertTrue(logged.contains("ERROR"));
        assertTrue(logged.contains("timeout occurred"));
    }

    @Test
    void testWarnLog() {
        logger.warn("req-3", "slow query");
        assertTrue(output.toString().contains("WARN"));
    }

    @Test
    void testDebugLog() {
        logger.debug("req-4", "trace detail");
        assertTrue(output.toString().contains("DEBUG"));
    }

    @Test
    void testLogWithShard() {
        logger.info("req-5", 7, "segment loaded");
        String logged = output.toString();
        assertTrue(logged.contains("Shard-7"));
        assertTrue(logged.contains("segment loaded"));
    }

    @Test
    void testLogRequest() {
        logger.logRequest("req-6", "/api/v1/search", 42, 200);
        String logged = output.toString();
        assertTrue(logged.contains("/api/v1/search"));
        assertTrue(logged.contains("42ms"));
        assertTrue(logged.contains("200 OK"));
    }

    @Test
    void testLogRequestErrorStatus() {
        logger.logRequest("req-7", "/api/v1/index", 5, 500);
        assertTrue(output.toString().contains("500 Internal Error"));
    }
}
