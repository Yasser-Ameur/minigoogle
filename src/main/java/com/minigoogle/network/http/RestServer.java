package com.minigoogle.network.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.function.Function;

public class RestServer {

    private final HttpServer server;

    public RestServer(int port) {
        try {
            this.server = HttpServer.create(new InetSocketAddress(port), 0);
            this.server.setExecutor(Executors.newCachedThreadPool());
        } catch (IOException e) {
            throw new RuntimeException("Failed to start RestServer on port " + port, e);
        }
    }

    public void post(String path, Function<String, String> handler) {
        server.createContext(path, exchange -> {
            if (!"POST".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            try (InputStream is = exchange.getRequestBody()) {
                String requestBody = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                handleExchange(exchange, handler, requestBody);
            } catch (Exception e) {
                sendError(exchange, e);
            }
        });
    }

    public void get(String path, Function<String, String> handler) {
        server.createContext(path, exchange -> {
            if (!"GET".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            handleExchange(exchange, handler, exchange.getRequestURI().toString());
        });
    }

    /**
     * Handles a request by invoking the handler with the given input.
     *
     * <p>GET handlers receive the full request URI (including any {@code ?key=value}
     * query string), since GET requests carry no body. POST handlers receive the
     * request body as a string.</p>
     */
    private void handleExchange(HttpExchange exchange, Function<String, String> handler, String requestInput) {
        try {
            String responseBody = handler.apply(requestInput);

            byte[] responseBytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, responseBytes.length);

            try (OutputStream os = exchange.getResponseBody()) {
                os.write(responseBytes);
            }
        } catch (Exception e) {
            sendError(exchange, e);
        }
    }

    private void sendError(HttpExchange exchange, Exception e) {
        try {
            e.printStackTrace();
            String error = "{\"error\":\"" + e.getMessage() + "\"}";
            byte[] responseBytes = error.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(500, responseBytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(responseBytes);
            }
        } catch (IOException ignored) {
            // Client already gone; nothing more we can do.
        }
    }

    public void getHtml(String path, Function<String, String> handler) {
        getWithContentType(path, "text/html; charset=utf-8", handler);
    }

    public void getWithContentType(String path, String contentType, Function<String, String> handler) {
        server.createContext(path, exchange -> {
            if (!"GET".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            try {
                String responseBody = handler.apply(exchange.getRequestURI().toString());

                byte[] responseBytes = responseBody.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", contentType);
                exchange.sendResponseHeaders(200, responseBytes.length);

                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(responseBytes);
                }
            } catch (Exception e) {
                sendError(exchange, e);
            }
        });
    }

    public void start() {
        server.start();
        System.out.println("RestServer started on port " + server.getAddress().getPort());
    }

    /**
     * Returns the port the server is bound to (useful when created with port 0).
     */
    public int getPort() {
        return server.getAddress().getPort();
    }

    public void stop() {
        server.stop(0);
    }
}
