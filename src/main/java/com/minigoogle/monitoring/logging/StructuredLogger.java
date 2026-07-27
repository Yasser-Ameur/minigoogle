package com.minigoogle.monitoring.logging;

import java.io.PrintStream;

/**
 * Structured logger that produces machine-parseable log entries.
 *
 * Per ARCHITECTURE.md Ch07 §19:
 *   Every request generates structured logs with:
 *   Request ID → Node → Endpoint → Latency → Status
 *
 * Per ARCHITECTURE.md Ch15 §17:
 *   Every log includes Timestamp, Node, Shard, Request ID, Severity.
 */
public class StructuredLogger {

    private final String nodeId;
    private final PrintStream outputStream;

    public StructuredLogger(String nodeId, PrintStream outputStream) {
        this.nodeId = nodeId;
        this.outputStream = outputStream;
    }

    public StructuredLogger(String nodeId) {
        this(nodeId, System.out);
    }

    public void info(String requestId, String message) {
        log("INFO", requestId, message);
    }

    public void warn(String requestId, String message) {
        log("WARN", requestId, message);
    }

    public void error(String requestId, String message) {
        log("ERROR", requestId, message);
    }

    public void debug(String requestId, String message) {
        log("DEBUG", requestId, message);
    }

    /**
     * Logs with shard context.
     */
    public void info(String requestId, int shardId, String message) {
        log("INFO", requestId, "Shard-" + shardId + " " + message);
    }

    /**
     * Logs an HTTP request/response event.
     */
    public void logRequest(String requestId, String endpoint, long latencyMs, int statusCode) {
        String statusText = switch (statusCode) {
            case 200 -> "200 OK";
            case 400 -> "400 Bad Request";
            case 404 -> "404 Not Found";
            case 500 -> "500 Internal Error";
            default -> String.valueOf(statusCode);
        };
        log("INFO", requestId, String.format("%s %dms %s", endpoint, latencyMs, statusText));
    }

    private void log(String severity, String requestId, String message) {
        String formatted = LogFormatter.format(severity, nodeId, requestId, message);
        outputStream.println(formatted);
    }
}
