package com.minigoogle.monitoring.tracing;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Represents a single unit of work within a distributed trace.
 *
 * Per ARCHITECTURE.md Ch11:
 *   Every search request creates a trace.
 *   Trace → Coordinator Logs Event → Shard Metrics Updated →
 *   Latency Recorded → Query Completed → Dashboard Updated.
 *
 * A Span has a start time, end time, parent, and metadata tags.
 */
public class Span {

    private final String spanId;
    private final String traceId;
    private final String parentSpanId;
    private final String operationName;
    private final Instant startTime;
    private Instant endTime;
    private final List<SpanEvent> events = new ArrayList<>();
    private int statusCode = 0; // 0 = OK, 1 = ERROR

    public Span(String traceId, String operationName) {
        this(traceId, null, operationName);
    }

    public Span(String traceId, String parentSpanId, String operationName) {
        this.spanId = UUID.randomUUID().toString();
        this.traceId = traceId;
        this.parentSpanId = parentSpanId;
        this.operationName = operationName;
        this.startTime = Instant.now();
    }

    /**
     * Finishes the span, recording the end time.
     */
    public void finish() {
        this.endTime = Instant.now();
    }

    /**
     * Finishes the span with an error status.
     */
    public void finishWithError() {
        this.statusCode = 1;
        this.endTime = Instant.now();
    }

    /**
     * Adds a timestamped event within this span.
     */
    public void addEvent(String eventName) {
        events.add(new SpanEvent(eventName, Instant.now()));
    }

    /**
     * Adds a timestamped event with a message.
     */
    public void addEvent(String eventName, String message) {
        events.add(new SpanEvent(eventName + ": " + message, Instant.now()));
    }

    public String getSpanId() { return spanId; }
    public String getTraceId() { return traceId; }
    public String getParentSpanId() { return parentSpanId; }
    public String getOperationName() { return operationName; }
    public Instant getStartTime() { return startTime; }
    public Instant getEndTime() { return endTime; }
    public List<SpanEvent> getEvents() { return Collections.unmodifiableList(events); }
    public int getStatusCode() { return statusCode; }

    /**
     * @return The duration of this span, or 0 if not yet finished.
     */
    public Duration getDuration() {
        if (endTime == null) return Duration.ZERO;
        return Duration.between(startTime, endTime);
    }

    public boolean isFinished() {
        return endTime != null;
    }

    public record SpanEvent(String name, Instant timestamp) {
    }
}
