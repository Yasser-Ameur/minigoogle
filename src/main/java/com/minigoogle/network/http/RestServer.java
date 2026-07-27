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
            handleExchange(exchange, handler);
        });
    }

    public void get(String path, Function<String, String> handler) {
        server.createContext(path, exchange -> {
            if (!"GET".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            handleExchange(exchange, handler);
        });
    }

    private void handleExchange(HttpExchange exchange, Function<String, String> handler) throws IOException {
        try (InputStream is = exchange.getRequestBody()) {
            String requestBody = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            String responseBody = handler.apply(requestBody);

            byte[] responseBytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, responseBytes.length);

            try (OutputStream os = exchange.getResponseBody()) {
                os.write(responseBytes);
            }
        } catch (Exception e) {
            e.printStackTrace();
            String error = "{\"error\":\"" + e.getMessage() + "\"}";
            byte[] responseBytes = error.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(500, responseBytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(responseBytes);
            }
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
            try (InputStream is = exchange.getRequestBody()) {
                String requestBody = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                String responseBody = handler.apply(requestBody);

                byte[] responseBytes = responseBody.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", contentType);
                exchange.sendResponseHeaders(200, responseBytes.length);

                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(responseBytes);
                }
            } catch (Exception e) {
                e.printStackTrace();
                String error = "{\"error\":\"" + e.getMessage() + "\"}";
                byte[] responseBytes = error.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(500, responseBytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(responseBytes);
                }
            }
        });
    }

    public void start() {
        server.start();
        System.out.println("RestServer started on port " + server.getAddress().getPort());
    }

    public void stop() {
        server.stop(0);
    }
}
