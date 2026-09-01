package com.minigoogle.monitoring.metrics;

import com.minigoogle.core.Version;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.RuntimeMXBean;
import java.lang.management.ThreadMXBean;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.DoubleSupplier;

/**
 * Prometheus text-exposition-format (0.0.4) metrics registry.
 *
 * Exposes HTTP request counters/latency histograms, search latency/result
 * metrics, arbitrary live gauges, and a handful of process/JVM metrics.
 * All mutating operations are thread-safe.
 */
public class PrometheusRegistry {

    private static final double[] HTTP_BUCKETS = {
            0.001, 0.0025, 0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10
    };

    private final Map<HttpCounterKey, LongAdder> httpRequestsTotal = new ConcurrentHashMap<>();
    private final Map<RouteMethodKey, Histogram> httpDurationSeconds = new ConcurrentHashMap<>();

    private final Histogram searchDurationSeconds = new Histogram(HTTP_BUCKETS);
    private final LongAdder searchQueriesTotal = new LongAdder();
    private final LongAdder searchZeroResultQueriesTotal = new LongAdder();

    private final Map<String, DoubleSupplier> gauges = new ConcurrentHashMap<>();

    public PrometheusRegistry() {
    }

    public void observeHttp(String route, String method, int status, long durationNanos) {
        httpRequestsTotal
                .computeIfAbsent(new HttpCounterKey(route, method, status), k -> new LongAdder())
                .increment();
        httpDurationSeconds
                .computeIfAbsent(new RouteMethodKey(route, method), k -> new Histogram(HTTP_BUCKETS))
                .observe(durationNanos / 1_000_000_000.0);
    }

    public void observeSearch(long durationNanos, int resultCount) {
        searchDurationSeconds.observe(durationNanos / 1_000_000_000.0);
        searchQueriesTotal.increment();
        if (resultCount == 0) {
            searchZeroResultQueriesTotal.increment();
        }
    }

    /**
     * Registers a gauge whose value is read live at scrape time.
     * Registering the same name again replaces the previous supplier.
     */
    public void gauge(String name, DoubleSupplier supplier) {
        gauges.put(name, supplier);
    }

    public String scrape() {
        StringBuilder sb = new StringBuilder();

        appendBuildAndProcessMetrics(sb);
        appendHttpMetrics(sb);
        appendSearchMetrics(sb);
        appendGauges(sb);

        return sb.toString();
    }

    private void appendBuildAndProcessMetrics(StringBuilder sb) {
        sb.append("# HELP minigoogle_build_info Build information.\n");
        sb.append("# TYPE minigoogle_build_info gauge\n");
        sb.append("minigoogle_build_info{version=\"").append(escape(Version.current())).append("\"} 1\n");

        RuntimeMXBean runtimeMXBean = ManagementFactory.getRuntimeMXBean();
        sb.append("# HELP process_uptime_seconds Time since the process started, in seconds.\n");
        sb.append("# TYPE process_uptime_seconds gauge\n");
        sb.append("process_uptime_seconds ").append(formatValue(runtimeMXBean.getUptime() / 1000.0)).append('\n');

        MemoryMXBean memoryMXBean = ManagementFactory.getMemoryMXBean();
        sb.append("# HELP jvm_memory_used_bytes Used memory, by area.\n");
        sb.append("# TYPE jvm_memory_used_bytes gauge\n");
        sb.append("jvm_memory_used_bytes{area=\"heap\"} ")
                .append(formatValue(memoryMXBean.getHeapMemoryUsage().getUsed())).append('\n');

        ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
        sb.append("# HELP jvm_threads_current Current number of live threads.\n");
        sb.append("# TYPE jvm_threads_current gauge\n");
        sb.append("jvm_threads_current ").append(threadMXBean.getThreadCount()).append('\n');
    }

    private void appendHttpMetrics(StringBuilder sb) {
        sb.append("# HELP minigoogle_http_requests_total Total HTTP requests.\n");
        sb.append("# TYPE minigoogle_http_requests_total counter\n");
        Set<HttpCounterKey> counterKeys = new TreeSet<>(httpRequestsTotal.keySet());
        for (HttpCounterKey key : counterKeys) {
            sb.append("minigoogle_http_requests_total{method=\"").append(escape(key.method()))
                    .append("\",route=\"").append(escape(key.route()))
                    .append("\",status=\"").append(key.status())
                    .append("\"} ").append(httpRequestsTotal.get(key).sum()).append('\n');
        }

        sb.append("# HELP minigoogle_http_request_duration_seconds HTTP request duration in seconds.\n");
        sb.append("# TYPE minigoogle_http_request_duration_seconds histogram\n");
        Set<RouteMethodKey> histogramKeys = new TreeSet<>(httpDurationSeconds.keySet());
        for (RouteMethodKey key : histogramKeys) {
            String labels = "method=\"" + escape(key.method()) + "\",route=\"" + escape(key.route()) + "\"";
            httpDurationSeconds.get(key).appendTo(sb, "minigoogle_http_request_duration_seconds", labels);
        }
    }

    private void appendSearchMetrics(StringBuilder sb) {
        sb.append("# HELP minigoogle_search_duration_seconds Search query duration in seconds.\n");
        sb.append("# TYPE minigoogle_search_duration_seconds histogram\n");
        searchDurationSeconds.appendTo(sb, "minigoogle_search_duration_seconds", null);

        sb.append("# HELP minigoogle_search_queries_total Total search queries executed.\n");
        sb.append("# TYPE minigoogle_search_queries_total counter\n");
        sb.append("minigoogle_search_queries_total ").append(searchQueriesTotal.sum()).append('\n');

        sb.append("# HELP minigoogle_search_zero_result_queries_total Total search queries that returned no results.\n");
        sb.append("# TYPE minigoogle_search_zero_result_queries_total counter\n");
        sb.append("minigoogle_search_zero_result_queries_total ").append(searchZeroResultQueriesTotal.sum()).append('\n');
    }

    private void appendGauges(StringBuilder sb) {
        Set<String> names = new TreeSet<>(gauges.keySet());
        for (String name : names) {
            sb.append("# HELP ").append(name).append(' ').append(name).append(".\n");
            sb.append("# TYPE ").append(name).append(" gauge\n");
            sb.append(name).append(' ').append(formatValue(gauges.get(name).getAsDouble())).append('\n');
        }
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    private static String formatValue(double value) {
        if (value == Math.floor(value) && !Double.isInfinite(value)) {
            return String.valueOf((long) value);
        }
        return String.valueOf(value);
    }

    private record HttpCounterKey(String route, String method, int status) implements Comparable<HttpCounterKey> {
        @Override
        public int compareTo(HttpCounterKey other) {
            int c = route.compareTo(other.route);
            if (c != 0) return c;
            c = method.compareTo(other.method);
            if (c != 0) return c;
            return Integer.compare(status, other.status);
        }
    }

    private record RouteMethodKey(String route, String method) implements Comparable<RouteMethodKey> {
        @Override
        public int compareTo(RouteMethodKey other) {
            int c = route.compareTo(other.route);
            if (c != 0) return c;
            return method.compareTo(other.method);
        }
    }

    /**
     * A fixed-bucket histogram with cumulative bucket semantics on read.
     * Thread-safe: each bucket is a {@link LongAdder}, sum is a {@link DoubleAdder}.
     */
    private static final class Histogram {
        private final double[] bounds;
        private final LongAdder[] bucketCounts;
        private final DoubleAdder sum = new DoubleAdder();
        private final LongAdder count = new LongAdder();

        Histogram(double[] bounds) {
            this.bounds = bounds;
            this.bucketCounts = new LongAdder[bounds.length];
            for (int i = 0; i < bounds.length; i++) {
                bucketCounts[i] = new LongAdder();
            }
        }

        void observe(double value) {
            for (int i = 0; i < bounds.length; i++) {
                if (value <= bounds[i]) {
                    bucketCounts[i].increment();
                    break;
                }
            }
            sum.add(value);
            count.increment();
        }

        void appendTo(StringBuilder sb, String metricName, String labels) {
            long cumulative = 0;
            for (int i = 0; i < bounds.length; i++) {
                cumulative += bucketCounts[i].sum();
                sb.append(metricName).append("_bucket{");
                if (labels != null) {
                    sb.append(labels).append(',');
                }
                sb.append("le=\"").append(formatValue(bounds[i])).append("\"} ").append(cumulative).append('\n');
            }
            long total = count.sum();
            sb.append(metricName).append("_bucket{");
            if (labels != null) {
                sb.append(labels).append(',');
            }
            sb.append("le=\"+Inf\"} ").append(total).append('\n');

            sb.append(metricName).append("_sum");
            if (labels != null) {
                sb.append('{').append(labels).append('}');
            }
            sb.append(' ').append(formatValue(sum.sum())).append('\n');

            sb.append(metricName).append("_count");
            if (labels != null) {
                sb.append('{').append(labels).append('}');
            }
            sb.append(' ').append(total).append('\n');
        }
    }
}
