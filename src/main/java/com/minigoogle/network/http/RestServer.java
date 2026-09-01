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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.regex.Pattern;

public class RestServer {

    private static final Logger log = LoggerFactory.getLogger(RestServer.class);
    private static final Pattern REQUEST_ID_PATTERN = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private final HttpServer server;
    private final ServerOptions options;
    private final ThreadPoolExecutor executor;
    private final ExecutorService handlerExecutor;
    private final ConcurrentHashMap<String, TokenBucket> rateBuckets = new ConcurrentHashMap<>();
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
        this.executor = new ThreadPoolExecutor(maxThreads, maxThreads, 60L, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
        this.executor.allowCoreThreadTimeOut(true);
        this.server.setExecutor(executor);
        this.handlerExecutor = Executors.newCachedThreadPool();
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

        boolean corsEnabled = options.corsAllowedOrigins() != null && !options.corsAllowedOrigins().isBlank();
        if (corsEnabled) {
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", resolveAllowedOrigin(exchange));
        }

        int status = 500;
        try {
            if (corsEnabled && "OPTIONS".equals(method)) {
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
            String responseBody = runWithTimeout(handler, input);

            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
            status = 200;
        } catch (HttpError he) {
            status = he.status();
            writeError(exchange, requestId, he.status(), he.code(), he.getMessage());
        } catch (TimeoutException te) {
            status = 504;
            writeError(exchange, requestId, 504, "TIMEOUT", "Handler timed out");
        } catch (Exception e) {
            status = 500;
            log.error("Unhandled error handling {} {} requestId={}", method, route, requestId, e);
            writeError(exchange, requestId, 500, "INTERNAL", "Internal server error");
        } finally {
            long durationNanos = System.nanoTime() - startNanos;
            log.info("{} {} -> {} ({} ms) requestId={}", method, route, status, durationNanos / 1_000_000, requestId);
            RequestObserver o = observer;
            if (o != null) {
                o.onRequest(route, method, status, durationNanos);
            }
        }
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

    private String runWithTimeout(Function<String, String> handler, String input) throws TimeoutException {
        long timeoutMs = options.requestTimeoutMs();
        if (timeoutMs <= 0) {
            return handler.apply(input);
        }
        Future<String> future = handlerExecutor.submit(() -> handler.apply(input));
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            future.cancel(true);
            throw te;
        } catch (ExecutionException ee) {
            Throwable cause = ee.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException(cause);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(ie);
        }
    }

    private String resolveRequestId(HttpExchange exchange) {
        String header = exchange.getRequestHeaders().getFirst("X-Request-Id");
        if (header != null && REQUEST_ID_PATTERN.matcher(header).matches()) {
            return header;
        }
        return RequestIdGenerator.generate();
    }

    private String resolveAllowedOrigin(HttpExchange exchange) {
        String origins = options.corsAllowedOrigins().trim();
        if ("*".equals(origins)) {
            return "*";
        }
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        if (origin != null) {
            for (String allowed : origins.split(",")) {
                if (allowed.trim().equals(origin)) {
                    return origin;
                }
            }
        }
        return "*";
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
        return bucket.tryConsume(rate, burst);
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
        executor.shutdown();
        try {
            executor.awaitTermination(Math.max(0, options.shutdownGraceMs()), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        executor.shutdownNow();
        handlerExecutor.shutdownNow();
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
    }
}
