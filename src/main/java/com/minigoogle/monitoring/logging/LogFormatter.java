package com.minigoogle.monitoring.logging;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Formats log entries into structured, machine-readable strings.
 *
 * Per ARCHITECTURE.md Ch15 §17:
 *   Every log includes: Timestamp, Node, Shard, Request ID, Severity.
 *   Example: INFO  Node-14  Request 7af1...  Latency 18 ms
 */
public class LogFormatter {

    private static final DateTimeFormatter TIMESTAMP_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

    /**
     * Formats a structured log entry.
     *
     * @param severity  Log level (INFO, WARN, ERROR, DEBUG).
     * @param nodeId    The node that produced this log.
     * @param requestId The request ID for correlation.
     * @param message   The log message.
     * @return A formatted log string.
     */
    public static String format(String severity, String nodeId, String requestId, String message) {
        return String.format("%s %s %s %s %s",
                TIMESTAMP_FMT.format(Instant.now()),
                severity,
                nodeId,
                requestId != null ? "Request " + requestId.substring(0, Math.min(8, requestId.length())) : "-",
                message);
    }

    /**
     * Formats a log entry with additional context.
     */
    public static String format(String severity, String nodeId, String requestId,
                                 String shardInfo, String message) {
        return String.format("%s %s %s %s %s %s",
                TIMESTAMP_FMT.format(Instant.now()),
                severity,
                nodeId,
                shardInfo != null ? "Shard-" + shardInfo : "-",
                requestId != null ? "Request " + requestId.substring(0, Math.min(8, requestId.length())) : "-",
                message);
    }
}
