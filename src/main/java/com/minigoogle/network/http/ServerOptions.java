package com.minigoogle.network.http;

/**
 * Tunables for {@link RestServer}. Use {@link #defaults()} for the previous,
 * fully-open behaviour.
 */
public record ServerOptions(
        int maxThreads,
        long maxBodyBytes,
        long requestTimeoutMs,
        double rateLimitPerSecond,
        int rateLimitBurst,
        String corsAllowedOrigins,
        String apiKey,
        long shutdownGraceMs) {

    public static ServerOptions defaults() {
        return new ServerOptions(64, 1_048_576L, 10_000L, 0, 0, "", null, 10_000L);
    }
}
