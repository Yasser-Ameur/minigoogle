package com.minigoogle.monitoring.metrics;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests for {@link PrometheusRegistry}. */
class PrometheusRegistryTest {

    private static final Pattern LINE_PATTERN =
            Pattern.compile("^([a-zA-Z_:][a-zA-Z0-9_:]*)(\\{[^}]*})?\\s+(\\S+)$");

    @Test
    void scrapeOutputParsesEveryNonCommentLine() {
        PrometheusRegistry registry = new PrometheusRegistry();
        registry.observeHttp("/api/v1/search", "GET", 200, 5_000_000L);
        registry.observeSearch(3_000_000L, 4);
        registry.gauge("minigoogle_index_documents", () -> 10);

        String scrape = registry.scrape();
        for (String line : scrape.split("\n")) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            Matcher matcher = LINE_PATTERN.matcher(line);
            assertTrue(matcher.matches(), "line did not parse: " + line);
        }
    }

    @Test
    void histogramBucketsAreCumulativeAndMonotone() {
        PrometheusRegistry registry = new PrometheusRegistry();
        // 0.5ms, 20ms, 3s -> falls into buckets 0.001, 0.025 (0.02s->bucket 0.025), 5 respectively
        registry.observeHttp("/x", "GET", 200, 500_000L);       // 0.0005s -> le=0.001
        registry.observeHttp("/x", "GET", 200, 20_000_000L);    // 0.02s   -> le=0.025
        registry.observeHttp("/x", "GET", 200, 3_000_000_000L); // 3s      -> le=5

        List<Long> bucketCounts = new ArrayList<>();
        Long totalCount = null;
        for (String line : registry.scrape().split("\n")) {
            if (!line.startsWith("minigoogle_http_request_duration_seconds_bucket{")) {
                continue;
            }
            String value = line.substring(line.lastIndexOf(' ') + 1);
            bucketCounts.add(Long.parseLong(value));
            if (line.contains("le=\"+Inf\"")) {
                totalCount = Long.parseLong(value);
            }
        }

        assertEquals(3, totalCount);
        long previous = 0;
        for (long count : bucketCounts) {
            assertTrue(count >= previous, "buckets must be monotonically non-decreasing");
            previous = count;
        }
        assertEquals(3, previous); // last bucket (+Inf) equals total observations
    }

    @Test
    void countersIncrementAcrossObservations() {
        PrometheusRegistry registry = new PrometheusRegistry();
        registry.observeHttp("/y", "GET", 200, 1_000_000L);
        registry.observeHttp("/y", "GET", 200, 1_000_000L);
        registry.observeHttp("/y", "GET", 500, 1_000_000L);

        String scrape = registry.scrape();
        assertTrue(scrape.contains(
                "minigoogle_http_requests_total{method=\"GET\",route=\"/y\",status=\"200\"} 2"));
        assertTrue(scrape.contains(
                "minigoogle_http_requests_total{method=\"GET\",route=\"/y\",status=\"500\"} 1"));
    }

    @Test
    void gaugeIsReadLiveAtScrapeTime() {
        PrometheusRegistry registry = new PrometheusRegistry();
        AtomicReference<Double> value = new AtomicReference<>(1.0);
        registry.gauge("minigoogle_index_documents", value::get);

        assertTrue(registry.scrape().contains("minigoogle_index_documents 1"));
        value.set(42.0);
        assertTrue(registry.scrape().contains("minigoogle_index_documents 42"));
    }

    @Test
    void labelValuesWithQuotesAndBackslashesAreEscaped() {
        PrometheusRegistry registry = new PrometheusRegistry();
        registry.observeHttp("/a\"b\\c", "GET", 200, 1_000_000L);

        String scrape = registry.scrape();
        assertTrue(scrape.contains("route=\"/a\\\"b\\\\c\""),
                "expected escaped label in: " + scrape);
    }
}
