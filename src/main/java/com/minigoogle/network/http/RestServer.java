package com.minigoogle.network.http;

import com.minigoogle.network.util.RequestIdGenerator;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.zip.GZIPOutputStream;

public class RestServer {

    private static final Logger log = LoggerFactory.getLogger(RestServer.class);
    private static final Pattern REQUEST_ID_PATTERN = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final long DEFAULT_RATE_BUCKET_IDLE_NANOS = TimeUnit.SECONDS.toNanos(60);
    private static final int RATE_BUCKET_EVICT_THRESHOLD = 10_000;
    private static final long RATE_BUCKET_SWEEP_INTERVAL_SECONDS = 30;

    private final HttpServer server;
    private final ServerOptions options;

    /**
     * Fixed-size pool that runs every handler end to end (parsing, the
     * registered {@code Function<String,String>}, response writing). This is
     * the ONLY thread pool RestServer owns for request work, so total
     * concurrency is capped at {@code maxThreads} no matter how much traffic
     * arrives; overflow is turned away in {@link #dispatch} before it ever
     * reaches a pool thread.
     */
    private final ThreadPoolExecutor pool;

    /**
     * Single shared thread used for two cheap, infrequent jobs: firing the
     * per-request timeout watchdog and sweeping idle rate-limit buckets. It
     * never runs handler code itself.
     */
    private final ScheduledExecutorService scheduler;

    /** Total requests currently admitted (running in {@link #pool} or queued in it). */
    private final AtomicInteger admitted = new AtomicInteger();
    private final int capacity;

    /**
     * Marks the calling thread as being inside the synchronous "reject, don't
     * queue" path taken when {@link #admitted} is already at {@link #capacity}.
     * Set by {@link #dispatch} immediately around running the (otherwise
     * opaque) HttpServer-supplied task, so {@link #handleRequest} - invoked
     * transitively from that same call - can tell it must answer 503 without
     * doing any real work or touching the pool.
     */
    private final ThreadLocal<Boolean> overloaded = new ThreadLocal<>();

    private final ConcurrentHashMap<String, TokenBucket> rateBuckets = new ConcurrentHashMap<>();
    private volatile long rateBucketIdleNanos = DEFAULT_RATE_BUCKET_IDLE_NANOS;
    private final AtomicBoolean rateBucketSweeping = new AtomicBoolean();

    private volatile RequestObserver observer;

    public RestServer(int port) {
        this(port, ServerOptions.defaults());
    }

    public RestServer(int port, ServerOptions options) {
        this.options = options;
        try {
            this.server = HttpServer.create(new InetSocketAddress(port), 0);
        } catch (IOException e) {
            throw new RuntimeException("Failed to start RestServer on port " + port, e);
        }
        int maxThreads = Math.max(1, options.maxThreads());
        int queueCapacity = maxThreads * 4;
        this.capacity = maxThreads + queueCapacity;
        AtomicInteger workerSeq = new AtomicInteger();
        ThreadFactory workerFactory = r -> {
            Thread t = new Thread(r, "rest-server-worker-" + workerSeq.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
        this.pool = new ThreadPoolExecutor(maxThreads, maxThreads, 60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(queueCapacity), workerFactory);
        this.pool.allowCoreThreadTimeOut(true);
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "rest-server-watchdog");
            t.setDaemon(true);
            return t;
        });
        this.scheduler.scheduleAtFixedRate(this::evictIdleRateBuckets,
                RATE_BUCKET_SWEEP_INTERVAL_SECONDS, RATE_BUCKET_SWEEP_INTERVAL_SECONDS, TimeUnit.SECONDS);
        this.server.setExecutor(this::dispatch);
    }

    /**
     * Entry point HttpServer calls once a request's headers are parsed.
     * Admits up to {@code capacity} (maxThreads + queue) requests into
     * {@link #pool}; anything beyond that is answered 503 synchronously, on
     * the calling (HttpServer dispatch) thread, without ever occupying a pool
     * thread - this is the backpressure mechanism, in place of an unbounded
     * queue or a raw {@code RejectedExecutionException} (which would just
     * reset the client's connection with no response).
     */
    private void dispatch(Runnable command) {
        if (admitted.incrementAndGet() > capacity) {
            admitted.decrementAndGet();
            overloaded.set(Boolean.TRUE);
            try {
                command.run();
            } finally {
                overloaded.remove();
            }
            return;
        }
        pool.execute(() -> {
            try {
                command.run();
            } finally {
                admitted.decrementAndGet();
            }
        });
    }

    public void setRequestObserver(RequestObserver o) {
        this.observer = o;
    }

    public void post(String path, Function<String, String> handler) {
        registerRoute(path, "POST", false, "application/json", handler);
    }

    public void get(String path, Function<String, String> handler) {
        registerRoute(path, "GET", false, "application/json", handler);
    }

    public void getHtml(String path, Function<String, String> handler) {
        getWithContentType(path, "text/html; charset=utf-8", handler);
    }

    public void getWithContentType(String path, String contentType, Function<String, String> handler) {
        registerRoute(path, "GET", false, contentType, handler);
    }

    public void postProtected(String path, Function<String, String> handler) {
        registerRoute(path, "POST", true, "application/json", handler);
    }

    public void getProtected(String path, String contentType, Function<String, String> handler) {
        registerRoute(path, "GET", true, contentType, handler);
    }

    private void registerRoute(String path, String expectedMethod, boolean protectedRoute, String contentType, Function<String, String> handler) {
        server.createContext(path, exchange -> handleRequest(exchange, path, expectedMethod, protectedRoute, contentType, handler));
    }

    private void handleRequest(HttpExchange exchange, String route, String expectedMethod, boolean protectedRoute,
                                String contentType, Function<String, String> handler) {
        long startNanos = System.nanoTime();
        String method = exchange.getRequestMethod();
        String requestId = resolveRequestId(exchange);
        exchange.getResponseHeaders().set("X-Request-Id", requestId);
        String observedRoute = route;

        if (Boolean.TRUE.equals(overloaded.get())) {
            int status = 503;
            exchange.getResponseHeaders().set("Retry-After", "1");
            writeError(exchange, requestId, 503, "SERVICE_BUSY", "Server is busy, try again shortly");
            long durationNanos = System.nanoTime() - startNanos;
            log.info("{} {} -> {} ({} ms) requestId={}", method, route, status, durationNanos / 1_000_000, requestId);
            RequestObserver o = observer;
            if (o != null) {
                o.onRequest(route, method, status, durationNanos);
            }
            return;
        }

        // The "/" context also catches every unmatched path (HttpServer routes
        // by longest matching prefix), so only the exact "/" path may reach the
        // registered handler; anything else is an explicit 404.
        if ("/".equals(route) && !"/".equals(exchange.getRequestURI().getPath())) {
            observedRoute = "unmatched";
        }

        String allowedOrigin = null;
        boolean corsIsWildcard = false;
        boolean corsEnabled = options.corsAllowedOrigins() != null && !options.corsAllowedOrigins().isBlank();
        if (corsEnabled) {
            String origins = options.corsAllowedOrigins().trim();
            if ("*".equals(origins)) {
                allowedOrigin = "*";
                corsIsWildcard = true;
            } else {
                allowedOrigin = matchOrigin(origins, exchange.getRequestHeaders().getFirst("Origin"));
            }
            if (allowedOrigin != null) {
                exchange.getResponseHeaders().set("Access-Control-Allow-Origin", allowedOrigin);
                if (!corsIsWildcard) {
                    exchange.getResponseHeaders().add("Vary", "Origin");
                }
            }
        }

        int status = 500;
        try {
            if ("unmatched".equals(observedRoute)) {
                status = 404;
                writeError(exchange, requestId, 404, "NOT_FOUND", "No such route");
                return;
            }
            if (corsEnabled && "OPTIONS".equals(method)) {
                if (allowedOrigin == null) {
                    status = 403;
                    writeError(exchange, requestId, 403, "FORBIDDEN_ORIGIN", "Origin not allowed");
                    return;
                }
                exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
                exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Authorization, X-API-Key, X-Request-Id");
                exchange.sendResponseHeaders(204, -1);
                status = 204;
                return;
            }
            if (!expectedMethod.equals(method)) {
                status = 405;
                writeError(exchange, requestId, 405, "METHOD_NOT_ALLOWED", "Method not allowed");
                return;
            }
            if (options.rateLimitPerSecond() > 0 && !allowRequest(clientKey(exchange))) {
                status = 429;
                long retryAfterSeconds = Math.max(1, (long) Math.ceil(1.0 / options.rateLimitPerSecond()));
                exchange.getResponseHeaders().set("Retry-After", String.valueOf(retryAfterSeconds));
                writeError(exchange, requestId, 429, "RATE_LIMITED", "Rate limit exceeded");
                return;
            }
            if (protectedRoute && !authorized(exchange)) {
                status = 401;
                exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer");
                writeError(exchange, requestId, 401, "UNAUTHORIZED", "Missing or invalid API key");
                return;
            }

            String input = "POST".equals(expectedMethod) ? readBodyWithCap(exchange) : exchange.getRequestURI().toString();
            status = runWithWatchdog(exchange, requestId, handler, input, contentType);
        } catch (HttpError he) {
            status = he.status();
            writeError(exchange, requestId, he.status(), he.code(), he.getMessage());
        } catch (Exception e) {
            status = 500;
            log.error("Unhandled error handling {} {} requestId={}", method, route, requestId, e);
            writeError(exchange, requestId, 500, "INTERNAL", "Internal server error");
        } finally {
            long durationNanos = System.nanoTime() - startNanos;
            log.info("{} {} -> {} ({} ms) requestId={}", method, route, status, durationNanos / 1_000_000, requestId);
            RequestObserver o = observer;
            if (o != null) {
                o.onRequest(observedRoute, method, status, durationNanos);
            }
        }
    }

    /**
     * Runs {@code handler} on the current (pool) thread and races it against a
     * single scheduled watchdog task. If the handler overruns
     * {@code requestTimeoutMs}, the watchdog writes the 504 response itself,
     * flips {@code answered} so the eventual handler result is discarded, and
     * interrupts this worker thread. A handler that ignores the interrupt
     * simply keeps this pool thread occupied until it returns on its own -
     * that thread is still "busy" from the pool's point of view, which is the
     * mechanism (not a separate accounting step) that keeps a stuck handler
     * from ever exceeding the pool's thread budget.
     *
     * @return the HTTP status actually written to the exchange, for logging.
     */
    private int runWithWatchdog(HttpExchange exchange, String requestId, Function<String, String> handler,
                                 String input, String contentType) throws IOException {
        long timeoutMs = options.requestTimeoutMs();
        if (timeoutMs <= 0) {
            byte[] bytes = handler.apply(input).getBytes(StandardCharsets.UTF_8);
            writeSuccess(exchange, contentType, bytes);
            return 200;
        }

        AtomicBoolean answered = new AtomicBoolean(false);
        Thread worker = Thread.currentThread();
        ScheduledFuture<?> watchdogFuture = scheduler.schedule(() -> {
            if (answered.compareAndSet(false, true)) {
                writeError(exchange, requestId, 504, "TIMEOUT", "Handler timed out");
                worker.interrupt();
            }
        }, timeoutMs, TimeUnit.MILLISECONDS);

        String responseBody;
        try {
            responseBody = handler.apply(input);
        } finally {
            watchdogFuture.cancel(false);
        }

        if (!answered.compareAndSet(false, true)) {
            // The watchdog already answered (and interrupted us) while the
            // handler kept running; its response was already sent, discard ours.
            return 504;
        }
        byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
        writeSuccess(exchange, contentType, bytes);
        return 200;
    }

    /** Bodies below this size are sent as-is; gzip framing would only add bytes. */
    private static final int GZIP_MIN_BYTES = 1024;

    private void writeSuccess(HttpExchange exchange, String contentType, byte[] bytes) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        if (bytes.length >= GZIP_MIN_BYTES) {
            exchange.getResponseHeaders().add("Vary", "Accept-Encoding");
        }
        if (bytes.length >= GZIP_MIN_BYTES && acceptsGzip(exchange)) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream(bytes.length / 4);
            try (GZIPOutputStream gzip = new GZIPOutputStream(buffer)) {
                gzip.write(bytes);
            }
            bytes = buffer.toByteArray();
            exchange.getResponseHeaders().set("Content-Encoding", "gzip");
        }
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static boolean acceptsGzip(HttpExchange exchange) {
        String accept = exchange.getRequestHeaders().getFirst("Accept-Encoding");
        return accept != null && accept.toLowerCase(Locale.ROOT).contains("gzip");
    }

    /**
     * Reads the request body up to {@code maxBodyBytes}, aborting as soon as the cap
     * is exceeded rather than draining the rest of the stream.
     */
    private String readBodyWithCap(HttpExchange exchange) throws IOException {
        long cap = options.maxBodyBytes();
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        long total = 0;
        try (InputStream is = exchange.getRequestBody()) {
            int n;
            while ((n = is.read(chunk)) != -1) {
                total += n;
                if (cap > 0 && total > cap) {
                    throw new HttpError(413, "PAYLOAD_TOO_LARGE", "Request body exceeds maximum allowed size");
                }
                buffer.write(chunk, 0, n);
            }
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }

    private String resolveRequestId(HttpExchange exchange) {
        String header = exchange.getRequestHeaders().getFirst("X-Request-Id");
        if (header != null && REQUEST_ID_PATTERN.matcher(header).matches()) {
            return header;
        }
        return RequestIdGenerator.generate();
    }

    /** Returns the matching allowed origin, or {@code null} if none of the comma-separated entries match. */
    private static String matchOrigin(String commaSeparatedOrigins, String origin) {
        if (origin == null) {
            return null;
        }
        for (String allowed : commaSeparatedOrigins.split(",")) {
            if (allowed.trim().equals(origin)) {
                return origin;
            }
        }
        return null;
    }

    private boolean authorized(HttpExchange exchange) {
        String apiKey = options.apiKey();
        if (apiKey == null || apiKey.isBlank()) {
            return true;
        }
        String provided = null;
        String authHeader = exchange.getRequestHeaders().getFirst("Authorization");
        if (authHeader != null && authHeader.regionMatches(true, 0, "Bearer ", 0, 7)) {
            provided = authHeader.substring(7).trim();
        }
        if (provided == null) {
            provided = exchange.getRequestHeaders().getFirst("X-API-Key");
        }
        if (provided == null) {
            return false;
        }
        return MessageDigest.isEqual(provided.getBytes(StandardCharsets.UTF_8), apiKey.getBytes(StandardCharsets.UTF_8));
    }

    private String clientKey(HttpExchange exchange) {
        InetSocketAddress addr = exchange.getRemoteAddress();
        return addr != null && addr.getAddress() != null ? addr.getAddress().getHostAddress() : "unknown";
    }

    private boolean allowRequest(String key) {
        double rate = options.rateLimitPerSecond();
        int computedBurst = options.rateLimitBurst() > 0 ? options.rateLimitBurst() : (int) Math.ceil(rate);
        final int burst = Math.max(1, computedBurst);
        TokenBucket bucket = rateBuckets.computeIfAbsent(key, k -> new TokenBucket(burst));
        boolean allowed = bucket.tryConsume(rate, burst);
        if (rateBuckets.size() > RATE_BUCKET_EVICT_THRESHOLD) {
            evictIdleRateBuckets();
        }
        return allowed;
    }

    /** Removes buckets not touched in the last {@link #rateBucketIdleNanos}, so distinct-client volume never grows the map without bound. */
    private void evictIdleRateBuckets() {
        if (!rateBucketSweeping.compareAndSet(false, true)) {
            return;
        }
        try {
            long now = System.nanoTime();
            long idleNanos = rateBucketIdleNanos;
            rateBuckets.entrySet().removeIf(e -> e.getValue().isIdle(now, idleNanos));
        } finally {
            rateBucketSweeping.set(false);
        }
    }

    /** Test-only seam: lets a test shrink the idle window instead of waiting 60s for real. */
    void setRateBucketIdleNanosForTesting(long nanos) {
        this.rateBucketIdleNanos = nanos;
    }

    /** Test-only seam: exercises the rate limiter for an arbitrary client key without a real socket. */
    boolean allowRequestForTesting(String key) {
        return allowRequest(key);
    }

    /** Test-only seam: current rate-bucket count. */
    int rateBucketCountForTesting() {
        return rateBuckets.size();
    }

    private void writeError(HttpExchange exchange, String requestId, int status, String code, String message) {
        try {
            String json = "{\"error\":{\"code\":\"" + escapeJson(code) + "\",\"message\":\"" + escapeJson(message)
                    + "\"},\"requestId\":\"" + escapeJson(requestId) + "\"}";
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        } catch (IOException ignored) {
            // Client already gone; nothing more we can do.
        }
    }

    private static String escapeJson(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    public void start() {
        server.start();
        log.info("RestServer started on port {}", server.getAddress().getPort());
    }

    /**
     * Returns the port the server is bound to (useful when created with port 0).
     */
    public int getPort() {
        return server.getAddress().getPort();
    }

    /**
     * Stops accepting new connections, waits up to {@code shutdownGraceMs} for
     * in-flight handlers to finish, then shuts the executors down.
     */
    public void stop() {
        int delaySeconds = (int) Math.max(0, Math.ceil(options.shutdownGraceMs() / 1000.0));
        server.stop(delaySeconds);
        pool.shutdown();
        try {
            pool.awaitTermination(Math.max(0, options.shutdownGraceMs()), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        pool.shutdownNow();
        scheduler.shutdownNow();
    }

    private static final class TokenBucket {
        private double tokens;
        private long lastRefillNanos;

        TokenBucket(int initialTokens) {
            this.tokens = initialTokens;
            this.lastRefillNanos = System.nanoTime();
        }

        synchronized boolean tryConsume(double ratePerSecond, int capacity) {
            long now = System.nanoTime();
            double elapsedSeconds = (now - lastRefillNanos) / 1_000_000_000.0;
            lastRefillNanos = now;
            tokens = Math.min(capacity, tokens + elapsedSeconds * ratePerSecond);
            if (tokens >= 1.0) {
                tokens -= 1.0;
                return true;
            }
            return false;
        }

        synchronized boolean isIdle(long nowNanos, long idleNanos) {
            return nowNanos - lastRefillNanos > idleNanos;
        }
    }
}
