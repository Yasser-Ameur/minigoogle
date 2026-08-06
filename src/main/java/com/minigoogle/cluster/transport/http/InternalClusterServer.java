package com.minigoogle.cluster.transport.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minigoogle.cluster.ClusterSecurity;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * Dedicated internal RPC server for cluster traffic.
 *
 * <p>Uses a bounded worker pool so a burst of cluster RPCs cannot exhaust
 * threads on the node. The pool is created up front and shut down in
 * {@link #stop()}. {@link #start()} is guarded against double-start.
 */
public class InternalClusterServer {
    private static final Logger logger = Logger.getLogger(InternalClusterServer.class.getName());

    private static final int DEFAULT_MAX_THREADS = Math.max(2, Runtime.getRuntime().availableProcessors());

    private final int port;
    private final ObjectMapper mapper;
    private final ExecutorService executor;
    private final AtomicBoolean started = new AtomicBoolean(false);
    private HttpServer server;

    public InternalClusterServer(int port, ObjectMapper mapper) throws IOException {
        this(port, mapper, DEFAULT_MAX_THREADS);
    }

    public InternalClusterServer(int port, ObjectMapper mapper, int maxThreads) throws IOException {
        this.port = port;
        this.mapper = mapper;
        this.executor = Executors.newFixedThreadPool(maxThreads, r -> {
            Thread t = new Thread(r, "cluster-rpc");
            t.setDaemon(true);
            return t;
        });
        // Create eagerly so handlers can be registered before start()
        this.server = HttpServer.create(new InetSocketAddress(port), 0);
    }

    public void start() {
        if (!started.compareAndSet(false, true)) {
            throw new IllegalStateException("Internal cluster server on port " + port + " is already started");
        }
        server.setExecutor(executor);
        server.start();
        logger.info("Internal cluster server started on port " + port);
    }

    public void stop() {
        if (server != null) {
            server.stop(1);
            executor.shutdownNow();
            logger.info("Internal cluster server stopped on port " + port);
        }
    }

    public HttpServer getServer() {
        return server;
    }

    /**
     * Registers an internal RPC endpoint behind the bearer-token
     * {@link AuthFilter}. Every internal endpoint must be registered this way
     * so that unauthenticated requests are rejected with 401 before the handler
     * is invoked.
     *
     * @param path     The context path, e.g. {@code /cluster/v1/raft/request-vote}.
     * @param handler  The endpoint handler.
     * @param security The cluster security manager holding the shared secret.
     */
    public void registerProtectedContext(String path, HttpHandler handler, ClusterSecurity security) {
        HttpContext context = server.createContext(path, handler);
        context.getFilters().add(new AuthFilter(security));
    }
}
