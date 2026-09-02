package com.minigoogle.network.http;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Behaviour contract tests for the hardened {@link RestServer}. */
class RestServerTest {

    private RestServer server;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
        }
    }

    private String base() {
        return "http://localhost:" + server.getPort();
    }

    @Test
    void errorResponsesAreUniformJson() throws Exception {
        server = new RestServer(0, ServerOptions.defaults());
        server.get("/ok", body -> "{\"ok\":true}");
        server.start();

        HttpRequest req = HttpRequest.newBuilder(URI.create(base() + "/ok")).POST(HttpRequest.BodyPublishers.noBody()).build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());

        assertEquals(405, resp.statusCode());
        assertTrue(resp.body().contains("\"code\":\"METHOD_NOT_ALLOWED\""));
        assertTrue(resp.body().contains("\"requestId\""));
    }

    @Test
    void handlerThrowingHttpErrorRendersItsStatusAndCode() throws Exception {
        server = new RestServer(0, ServerOptions.defaults());
        server.get("/bad", body -> {
            throw new HttpError(400, "BAD_REQUEST", "nope");
        });
        server.start();

        HttpRequest req = HttpRequest.newBuilder(URI.create(base() + "/bad")).GET().build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());

        assertEquals(400, resp.statusCode());
        assertTrue(resp.body().contains("\"code\":\"BAD_REQUEST\""));
        assertTrue(resp.body().contains("nope"));
    }

    @Test
    void unexpectedExceptionYields500WithoutLeakingMessage() throws Exception {
        server = new RestServer(0, ServerOptions.defaults());
        server.get("/boom", body -> {
            throw new RuntimeException("super secret internal detail");
        });
        server.start();

        HttpRequest req = HttpRequest.newBuilder(URI.create(base() + "/boom")).GET().build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());

        assertEquals(500, resp.statusCode());
        assertTrue(resp.body().contains("\"code\":\"INTERNAL\""));
        assertFalse(resp.body().contains("super secret internal detail"));
    }

    @Test
    void requestIdIsEchoedWhenValidAndGeneratedOtherwise() throws Exception {
        server = new RestServer(0, ServerOptions.defaults());
        server.get("/ok", body -> "{}");
        server.start();

        HttpRequest req = HttpRequest.newBuilder(URI.create(base() + "/ok"))
                .header("X-Request-Id", "abc-123_XYZ")
                .GET().build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals("abc-123_XYZ", resp.headers().firstValue("X-Request-Id").orElse(null));

        HttpRequest req2 = HttpRequest.newBuilder(URI.create(base() + "/ok"))
                .header("X-Request-Id", "not valid!!")
                .GET().build();
        HttpResponse<String> resp2 = client.send(req2, HttpResponse.BodyHandlers.ofString());
        String generated = resp2.headers().firstValue("X-Request-Id").orElse(null);
        assertNotNull(generated);
        assertNotEquals("not valid!!", generated);
    }

    @Test
    void postBodyOverCapIsRejectedWith413() throws Exception {
        ServerOptions opts = new ServerOptions(4, 8, 10_000, 0, 0, "", null, 1000);
        server = new RestServer(0, opts);
        server.post("/echo", body -> body);
        server.start();

        HttpRequest req = HttpRequest.newBuilder(URI.create(base() + "/echo"))
                .POST(HttpRequest.BodyPublishers.ofString("this body is way over the cap"))
                .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());

        assertEquals(413, resp.statusCode());
        assertTrue(resp.body().contains("PAYLOAD_TOO_LARGE"));
    }

    @Test
    void slowHandlerTimesOutWith504() throws Exception {
        ServerOptions opts = new ServerOptions(4, 1_048_576, 200, 0, 0, "", null, 1000);
        server = new RestServer(0, opts);
        server.get("/slow", body -> {
            try {
                Thread.sleep(5000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            return "{}";
        });
        server.start();

        HttpRequest req = HttpRequest.newBuilder(URI.create(base() + "/slow")).GET().build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());

        assertEquals(504, resp.statusCode());
        assertTrue(resp.body().contains("TIMEOUT"));
    }

    @Test
    void rateLimitReturns429WithRetryAfter() throws Exception {
        ServerOptions opts = new ServerOptions(4, 1_048_576, 10_000, 2.0, 1, "", null, 1000);
        server = new RestServer(0, opts);
        server.get("/limited", body -> "{}");
        server.start();

        HttpRequest req = HttpRequest.newBuilder(URI.create(base() + "/limited")).GET().build();
        HttpResponse<String> first = client.send(req, HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> second = client.send(req, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, first.statusCode());
        assertEquals(429, second.statusCode());
        assertTrue(second.headers().firstValue("Retry-After").isPresent());
        assertTrue(second.body().contains("RATE_LIMITED"));
    }

    @Test
    void protectedRouteRequires401WithoutKeyAndAcceptsBearerOrHeader() throws Exception {
        ServerOptions opts = new ServerOptions(4, 1_048_576, 10_000, 0, 0, "", "secret-key", 1000);
        server = new RestServer(0, opts);
        server.getProtected("/admin", "application/json", body -> "{\"ok\":true}");
        server.start();

        HttpRequest noAuth = HttpRequest.newBuilder(URI.create(base() + "/admin")).GET().build();
        HttpResponse<String> respNoAuth = client.send(noAuth, HttpResponse.BodyHandlers.ofString());
        assertEquals(401, respNoAuth.statusCode());
        assertEquals("Bearer", respNoAuth.headers().firstValue("WWW-Authenticate").orElse(null));

        HttpRequest bearer = HttpRequest.newBuilder(URI.create(base() + "/admin"))
                .header("Authorization", "Bearer secret-key").GET().build();
        HttpResponse<String> respBearer = client.send(bearer, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, respBearer.statusCode());

        HttpRequest apiKeyHeader = HttpRequest.newBuilder(URI.create(base() + "/admin"))
                .header("X-API-Key", "secret-key").GET().build();
        HttpResponse<String> respApiKey = client.send(apiKeyHeader, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, respApiKey.statusCode());

        HttpRequest wrongKey = HttpRequest.newBuilder(URI.create(base() + "/admin"))
                .header("X-API-Key", "wrong").GET().build();
        HttpResponse<String> respWrong = client.send(wrongKey, HttpResponse.BodyHandlers.ofString());
        assertEquals(401, respWrong.statusCode());
    }

    @Test
    void blankApiKeyLeavesProtectedRoutesOpen() throws Exception {
        server = new RestServer(0, ServerOptions.defaults());
        server.postProtected("/open-admin", body -> "{\"ok\":true}");
        server.start();

        HttpRequest req = HttpRequest.newBuilder(URI.create(base() + "/open-admin"))
                .POST(HttpRequest.BodyPublishers.ofString("{}")).build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode());
    }

    @Test
    void corsPreflightAndActualResponsesCarryHeaders() throws Exception {
        ServerOptions opts = new ServerOptions(4, 1_048_576, 10_000, 0, 0, "https://allowed.example", null, 1000);
        server = new RestServer(0, opts);
        server.get("/cors", body -> "{}");
        server.start();

        HttpRequest preflight = HttpRequest.newBuilder(URI.create(base() + "/cors"))
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .header("Origin", "https://allowed.example")
                .build();
        HttpResponse<String> preflightResp = client.send(preflight, HttpResponse.BodyHandlers.ofString());
        assertEquals(204, preflightResp.statusCode());
        assertEquals("https://allowed.example", preflightResp.headers().firstValue("Access-Control-Allow-Origin").orElse(null));
        assertEquals("GET, POST, OPTIONS", preflightResp.headers().firstValue("Access-Control-Allow-Methods").orElse(null));

        HttpRequest matchingActual = HttpRequest.newBuilder(URI.create(base() + "/cors"))
                .header("Origin", "https://allowed.example")
                .GET().build();
        HttpResponse<String> matchingResp = client.send(matchingActual, HttpResponse.BodyHandlers.ofString());
        assertEquals("https://allowed.example", matchingResp.headers().firstValue("Access-Control-Allow-Origin").orElse(null));
        assertEquals("Origin", matchingResp.headers().firstValue("Vary").orElse(null));

        HttpRequest actual = HttpRequest.newBuilder(URI.create(base() + "/cors"))
                .header("Origin", "https://other.example")
                .GET().build();
        HttpResponse<String> actualResp = client.send(actual, HttpResponse.BodyHandlers.ofString());
        assertTrue(actualResp.headers().firstValue("Access-Control-Allow-Origin").isEmpty(),
                "a non-matching origin in list mode must get no ACAO header at all");
        assertEquals(200, actualResp.statusCode(), "an unmatched origin still lets the actual request through");
    }

    @Test
    void corsPreflightFromDisallowedOriginIsForbidden() throws Exception {
        ServerOptions opts = new ServerOptions(4, 1_048_576, 10_000, 0, 0, "https://allowed.example", null, 1000);
        server = new RestServer(0, opts);
        server.get("/cors2", body -> "{}");
        server.start();

        HttpRequest preflight = HttpRequest.newBuilder(URI.create(base() + "/cors2"))
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .header("Origin", "https://evil.example")
                .build();
        HttpResponse<String> resp = client.send(preflight, HttpResponse.BodyHandlers.ofString());
        assertEquals(403, resp.statusCode());
        assertTrue(resp.body().contains("FORBIDDEN_ORIGIN"));
        assertTrue(resp.headers().firstValue("Access-Control-Allow-Origin").isEmpty());
    }

    @Test
    void wildcardCorsStaysWildcardRegardlessOfOrigin() throws Exception {
        ServerOptions opts = new ServerOptions(4, 1_048_576, 10_000, 0, 0, "*", null, 1000);
        server = new RestServer(0, opts);
        server.get("/cors3", body -> "{}");
        server.start();

        HttpRequest req = HttpRequest.newBuilder(URI.create(base() + "/cors3"))
                .header("Origin", "https://anything.example")
                .GET().build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals("*", resp.headers().firstValue("Access-Control-Allow-Origin").orElse(null));
        assertTrue(resp.headers().firstValue("Vary").isEmpty());
    }

    @Test
    void noCorsHeadersWhenOriginsBlank() throws Exception {
        server = new RestServer(0, ServerOptions.defaults());
        server.get("/nocors", body -> "{}");
        server.start();

        HttpRequest req = HttpRequest.newBuilder(URI.create(base() + "/nocors")).GET().build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertTrue(resp.headers().firstValue("Access-Control-Allow-Origin").isEmpty());
    }

    @Test
    void requestObserverIsCalledWithRoutePattern() throws Exception {
        server = new RestServer(0, ServerOptions.defaults());
        server.get("/observed", body -> "{}");
        List<String> routes = new ArrayList<>();
        AtomicInteger statuses = new AtomicInteger();
        server.setRequestObserver((route, method, status, durationNanos) -> {
            routes.add(route);
            statuses.set(status);
        });
        server.start();

        HttpRequest req = HttpRequest.newBuilder(URI.create(base() + "/observed?x=1")).GET().build();
        client.send(req, HttpResponse.BodyHandlers.ofString());

        assertEquals(List.of("/observed"), routes);
        assertEquals(200, statuses.get());
    }

    @Test
    void stopWaitsForInFlightHandlerToComplete() throws Exception {
        ServerOptions opts = new ServerOptions(4, 1_048_576, 10_000, 0, 0, "", null, 2000);
        server = new RestServer(0, opts);
        server.get("/slow-graceful", body -> {
            try {
                Thread.sleep(300);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            return "{\"done\":true}";
        });
        server.start();
        int port = server.getPort();

        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/slow-graceful")).GET().build();
        CompletableFuture<HttpResponse<String>> future = client.sendAsync(req, HttpResponse.BodyHandlers.ofString());

        Thread.sleep(50);
        server.stop();

        HttpResponse<String> resp = future.get(5, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals(200, resp.statusCode());
        assertTrue(resp.body().contains("done"));
        server = null;
    }

    @Test
    void threadCountStaysBoundedUnderConcurrentSlowRequests() throws Exception {
        int maxThreads = 8;
        ServerOptions opts = new ServerOptions(maxThreads, 1_048_576, 150, 0, 0, "", null, 500);
        server = new RestServer(0, opts);
        CountDownLatch release = new CountDownLatch(1);
        server.get("/stuck", body -> {
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            return "{}";
        });
        server.start();

        ExecutorService callers = Executors.newFixedThreadPool(50);
        List<CompletableFuture<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            futures.add(CompletableFuture.supplyAsync(() -> sendGet("/stuck"), callers));
        }

        Thread.sleep(400);
        long restServerThreads = Thread.getAllStackTraces().keySet().stream()
                .filter(t -> t.getName().startsWith("rest-server-"))
                .count();
        assertTrue(restServerThreads <= maxThreads + 2,
                "expected at most " + (maxThreads + 2) + " rest-server threads, found " + restServerThreads);

        release.countDown();
        for (CompletableFuture<Integer> f : futures) {
            f.get(10, TimeUnit.SECONDS);
        }
        callers.shutdown();
    }

    @Test
    void requestBeyondCapacityGets503() throws Exception {
        int maxThreads = 2;
        int queueCapacity = maxThreads * 4;
        int capacity = maxThreads + queueCapacity;
        ServerOptions opts = new ServerOptions(maxThreads, 1_048_576, 5_000, 0, 0, "", null, 500);
        server = new RestServer(0, opts);
        CountDownLatch release = new CountDownLatch(1);
        server.get("/stuck3", body -> {
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            return "{}";
        });
        server.start();

        ExecutorService callers = Executors.newFixedThreadPool(capacity);
        List<CompletableFuture<Integer>> fillers = new ArrayList<>();
        for (int i = 0; i < capacity; i++) {
            fillers.add(CompletableFuture.supplyAsync(() -> sendGet("/stuck3"), callers));
        }
        Thread.sleep(500);

        HttpRequest overflow = HttpRequest.newBuilder(URI.create(base() + "/stuck3")).GET().build();
        HttpResponse<String> resp = client.send(overflow, HttpResponse.BodyHandlers.ofString());
        assertEquals(503, resp.statusCode());
        assertTrue(resp.body().contains("SERVICE_BUSY"));
        assertEquals("1", resp.headers().firstValue("Retry-After").orElse(null));

        release.countDown();
        for (CompletableFuture<Integer> f : fillers) {
            f.get(10, TimeUnit.SECONDS);
        }
        callers.shutdown();
    }

    @Test
    void twentyThousandDistinctAddressesDoNotLeaveTwentyThousandBuckets() throws Exception {
        server = new RestServer(0, new ServerOptions(4, 1_048_576, 10_000, 5.0, 5, "", null, 1000));
        server.setRateBucketIdleNanosForTesting(0);

        for (int i = 0; i < 20_000; i++) {
            server.allowRequestForTesting("client-" + i);
        }

        assertTrue(server.rateBucketCountForTesting() < 20_000,
                "expected idle buckets to be swept, found " + server.rateBucketCountForTesting());
    }

    @Test
    void unmatchedPathReturns404AndRootStillServesQueryStrings() throws Exception {
        server = new RestServer(0, ServerOptions.defaults());
        server.getHtml("/", req -> "<html>ok</html>");
        List<String> routes = new ArrayList<>();
        server.setRequestObserver((route, method, status, durationNanos) -> routes.add(route));
        server.start();

        HttpRequest rootWithQuery = HttpRequest.newBuilder(URI.create(base() + "/?q=test")).GET().build();
        HttpResponse<String> rootResp = client.send(rootWithQuery, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, rootResp.statusCode());
        assertTrue(rootResp.body().contains("ok"));

        HttpRequest unmatched = HttpRequest.newBuilder(URI.create(base() + "/does/not/exist")).GET().build();
        HttpResponse<String> unmatchedResp = client.send(unmatched, HttpResponse.BodyHandlers.ofString());
        assertEquals(404, unmatchedResp.statusCode());
        assertTrue(unmatchedResp.body().contains("NOT_FOUND"));

        assertTrue(routes.contains("unmatched"));
    }

    private int sendGet(String path) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(base() + path)).GET().build();
            return client.send(req, HttpResponse.BodyHandlers.ofString()).statusCode();
        } catch (Exception e) {
            return -1;
        }
    }
}
