package com.minigoogle.ml.click;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Tracks search impressions and clicks and derives pairwise preferences.
 *
 * <p>The tracker records the ordered document list served for each query (an
 * impression) and the clicks that followed. From these it computes per-query
 * click-through rates and — using the standard "clicked over non-clicked"
 * preference assumption — a set of {@link ClickPreference} pairs that a
 * ranking model can train on.</p>
 */
public class ClickTracker {

    private final Map<String, List<Integer>> impressionsByQuery = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> impressionCounts = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> clickCounts = new ConcurrentHashMap<>();
    private final List<ClickEvent> clicks = new ArrayList<>();
    private final LongAdder totalImpressions = new LongAdder();
    private final LongAdder totalClicks = new LongAdder();

    /**
     * Records the ordered list of document ids served for a query.
     *
     * @param query   The search query.
     * @param docIds  The served document ids, in rank order.
     */
    public void recordImpression(String query, List<Integer> docIds) {
        if (query == null || docIds == null) {
            return;
        }
        String normalized = normalize(query);
        impressionsByQuery.put(normalized, List.copyOf(docIds));
        totalImpressions.add(docIds.size());
        for (int docId : docIds) {
            impressionCounts.computeIfAbsent(key(normalized, docId), k -> new LongAdder()).increment();
        }
    }

    /**
     * Records a click event.
     */
    public void recordClick(ClickEvent event) {
        if (event == null) {
            return;
        }
        synchronized (this) {
            clicks.add(event);
        }
        totalClicks.increment();
        clickCounts.computeIfAbsent(key(normalize(event.query()), event.documentId()), k -> new LongAdder())
                .increment();
    }

    /**
     * Returns the impression count for a query-document pair.
     */
    public long impressions(String query, int documentId) {
        LongAdder count = impressionCounts.get(key(normalize(query), documentId));
        return count != null ? count.sum() : 0L;
    }

    /**
     * Returns the click count for a query-document pair.
     */
    public long clicks(String query, int documentId) {
        LongAdder count = clickCounts.get(key(normalize(query), documentId));
        return count != null ? count.sum() : 0L;
    }

    /**
     * Returns the click-through rate for a query-document pair in [0, 1].
     */
    public double ctr(String query, int documentId) {
        long impressions = impressions(query, documentId);
        if (impressions == 0) {
            return 0.0;
        }
        return (double) clicks(query, documentId) / impressions;
    }

    /**
     * Returns the ordered document ids most recently served for a query.
     */
    public List<Integer> impressionsForQuery(String query) {
        List<Integer> ids = impressionsByQuery.get(normalize(query));
        return ids != null ? new ArrayList<>(ids) : List.of();
    }

    /**
     * The average 1-based click position for a query, or 0.0 if no clicks.
     */
    public double averageClickPosition(String query) {
        List<ClickEvent> queryClicks;
        synchronized (this) {
            queryClicks = clicks.stream()
                    .filter(c -> normalize(c.query()).equals(normalize(query)))
                    .toList();
        }
        if (queryClicks.isEmpty()) {
            return 0.0;
        }
        return queryClicks.stream().mapToInt(ClickEvent::position).average().orElse(0.0);
    }

    /**
     * Returns the most clicked documents, sorted by descending click count.
     */
    public List<Map.Entry<Integer, Long>> getTopClicked(int n) {
        Map<Integer, LongAdder> byDoc = new ConcurrentHashMap<>();
        for (Map.Entry<String, LongAdder> entry : clickCounts.entrySet()) {
            int sep = entry.getKey().indexOf('\u0000');
            int docId = Integer.parseInt(entry.getKey().substring(sep + 1));
            byDoc.computeIfAbsent(docId, k -> new LongAdder()).add(entry.getValue().sum());
        }
        List<Map.Entry<Integer, Long>> sorted = new ArrayList<>();
        for (Map.Entry<Integer, LongAdder> entry : byDoc.entrySet()) {
            sorted.add(Map.entry(entry.getKey(), entry.getValue().sum()));
        }
        sorted.sort((a, b) -> {
            int byCount = Long.compare(b.getValue(), a.getValue());
            return byCount != 0 ? byCount : Integer.compare(a.getKey(), b.getKey());
        });
        return sorted.subList(0, Math.min(n, sorted.size()));
    }

    /**
     * Derives pairwise preferences from the click log using the "clicked beats
     * unclicked-above" assumption. Only preferences for queries with a known
     * impression are produced.
     */
    public List<ClickPreference> buildPreferences() {
        List<ClickEvent> snapshot;
        synchronized (this) {
            snapshot = List.copyOf(clicks);
        }
        List<ClickPreference> preferences = new ArrayList<>();
        for (ClickEvent click : snapshot) {
            String query = normalize(click.query());
            List<Integer> served = impressionsByQuery.get(query);
            if (served == null) {
                continue;
            }
            int clickIndex = served.indexOf(click.documentId());
            if (clickIndex <= 0) {
                continue;
            }
            for (int j = 0; j < clickIndex; j++) {
                int above = served.get(j);
                if (above != click.documentId()) {
                    preferences.add(new ClickPreference(query, click.documentId(), above));
                }
            }
        }
        return preferences;
    }

    /**
     * Total number of impressions recorded (sum over all served results).
     */
    public long impressionCount() {
        return totalImpressions.sum();
    }

    /**
     * Total number of clicks recorded.
     */
    public long clickCount() {
        return totalClicks.sum();
    }

    /**
     * Clears all tracking data.
     */
    public void clear() {
        synchronized (this) {
            clicks.clear();
        }
        impressionsByQuery.clear();
        impressionCounts.clear();
        clickCounts.clear();
        totalImpressions.reset();
        totalClicks.reset();
    }

    private static String normalize(String query) {
        return query == null ? "" : query.toLowerCase().strip();
    }

    private static String key(String query, int documentId) {
        return query + "\u0000" + documentId;
    }
}
