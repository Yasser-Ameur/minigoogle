package com.minigoogle.network.http;

/** Notified once per request handled by {@link RestServer}. */
public interface RequestObserver {

    /**
     * @param route          the registered route pattern (never the raw request URI)
     * @param method         the HTTP method
     * @param status         the HTTP status code returned to the client
     * @param durationNanos  wall-clock time spent handling the request, in nanoseconds
     */
    void onRequest(String route, String method, int status, long durationNanos);
}
