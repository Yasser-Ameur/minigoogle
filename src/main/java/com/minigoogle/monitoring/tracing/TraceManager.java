package com.minigoogle.monitoring.tracing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages distributed traces across the cluster.
 *
 * Per ARCHITECTURE.md Ch11 §19 (Complete Monitoring Pipeline):
 *   Search Request → Trace Created → Coordinator Logs Event →
 *   Shard Metrics Updated → Latency Recorded → Query Completed →
 *   Metrics Aggregated → Dashboard Updated → Historical Snapshot Stored
 *
 * Each trace is identified by a UUID and contains one or more spans.
 */
public class TraceManager {

    private final Map<String, List<Span>> traces = new ConcurrentHashMap<>();

    /**
     * Starts a new trace and returns its ID.
     */
    public String startTrace(String operationName) {
        String traceId = UUID.randomUUID().toString();
        Span rootSpan = new Span(traceId, operationName);
        traces.put(traceId, Collections.synchronizedList(new ArrayList<>(List.of(rootSpan))));
        return traceId;
    }

    /**
     * Creates a child span within an existing trace.
     */
    public Span createSpan(String traceId, String parentSpanId, String operationName) {
        Span span = new Span(traceId, parentSpanId, operationName);
        List<Span> traceSpans = traces.get(traceId);
        if (traceSpans != null) {
            traceSpans.add(span);
        }
        return span;
    }

    /**
     * Creates a child span with auto-detected parent.
     */
    public Span createSpan(String traceId, String operationName) {
        List<Span> traceSpans = traces.get(traceId);
        String parentId = null;
        if (traceSpans != null && !traceSpans.isEmpty()) {
            parentId = traceSpans.get(traceSpans.size() - 1).getSpanId();
        }
        return createSpan(traceId, parentId, operationName);
    }

    /**
     * Returns all spans for a given trace.
     */
    public List<Span> getTrace(String traceId) {
        List<Span> spans = traces.get(traceId);
        return spans != null ? new ArrayList<>(spans) : Collections.emptyList();
    }

    /**
     * Returns the total number of active traces.
     */
    public int activeTraceCount() {
        return traces.size();
    }

    /**
     * Finishes a trace by finishing all its spans.
     */
    public void finishTrace(String traceId) {
        List<Span> spans = traces.get(traceId);
        if (spans != null) {
            for (Span span : spans) {
                if (!span.isFinished()) {
                    span.finish();
                }
            }
        }
    }

    /**
     * Removes a completed trace from the store.
     */
    public void removeTrace(String traceId) {
        traces.remove(traceId);
    }

    /**
     * Clears all traces.
     */
    public void clear() {
        traces.clear();
    }
}
