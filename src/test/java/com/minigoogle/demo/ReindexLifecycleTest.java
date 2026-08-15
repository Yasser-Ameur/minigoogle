package com.minigoogle.demo;

import com.minigoogle.core.config.Configuration;
import com.minigoogle.core.concurrent.ConcurrentIndex;
import com.minigoogle.crawler.model.ParsedDocument;
import com.minigoogle.search.SearchEngineBuild;
import com.minigoogle.search.SearchEngineBuilder;
import com.minigoogle.storage.mmap.MemoryMappedIndex;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for the reindex lifecycle fix: each index build lands in a
 * versioned sub-directory and is published through a ref-counted
 * {@link ConcurrentIndex}, so the previous generation's memory-mapped postings
 * file is never rewritten or truncated while readers may still hold it. Old
 * build directories are removed only once the last lease on that generation is
 * released; a failed build leaves the current index untouched.
 */
class ReindexLifecycleTest {

    @TempDir
    Path tempDir;

    private static final Configuration CONFIG = new Configuration(Map.of(
            "semantic.enabled", "false",
            "semantic.hybrid.enabled", "false",
            "semantic.expansion.enabled", "false",
            "semantic.embeddings.enabled", "false"));

    private final AtomicLong buildSeq = new AtomicLong();
    private final ConcurrentIndex<SearchEngineBuild> currentIndex = new ConcurrentIndex<>();

    /** Mirrors MiniGoogleApp.reindex: build into a fresh versioned dir, publish, never rewrite a mapped file. */
    private void reindex(List<ParsedDocument> docs) throws Exception {
        Path buildDir = tempDir.resolve("builds").resolve("build-" + buildSeq.incrementAndGet());
        try {
            Files.createDirectories(buildDir);
            SearchEngineBuild build = SearchEngineBuilder.build(docs, CONFIG, buildDir);
            currentIndex.publish(ConcurrentIndex.Entry.of(build, () -> releaseBuild(buildDir, build)));
        } catch (Exception e) {
            deleteRecursively(buildDir);
            throw e;
        }
    }

    private static void releaseBuild(Path buildDir, SearchEngineBuild build) {
        try {
            if (build.mmapIndex() != null) {
                build.mmapIndex().close();
            }
        } finally {
            deleteRecursively(buildDir);
        }
    }

    private static void deleteRecursively(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }

    private static ParsedDocument doc(String url, String body) {
        return new ParsedDocument(UUID.randomUUID(), URI.create(url), "title", body,
                List.of(), Instant.now());
    }

    @Test
    void concurrentSearchesDuringReindexNeverSeeAClosedIndex() throws Exception {
        reindex(List.of(doc("https://gen-1", "alpha beta gamma"),
                doc("https://gen-1", "alpha delta")));

        int readers = 6;
        int searchesPerReader = 60;
        ExecutorService pool = Executors.newFixedThreadPool(readers);
        List<String> failures = new CopyOnWriteArrayList<>();
        CountDownLatch start = new CountDownLatch(1);

        try {
            for (int r = 0; r < readers; r++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < searchesPerReader; i++) {
                            try (ConcurrentIndex.Lease<SearchEngineBuild> lease = currentIndex.lease()) {
                                SearchEngineBuild build = lease.value();
                                if (build == null) {
                                    failures.add("saw null index");
                                    continue;
                                }
                                var result = build.engine().retrieveCandidates("alpha", 10);
                                List<String> urls = result.ranked().stream()
                                        .map(d -> d.url()).toList();
                                if (urls.isEmpty()) {
                                    failures.add("search returned no results");
                                }
                                for (String url : urls) {
                                    if (!url.contains("gen-")) {
                                        failures.add("torn result: " + url);
                                    }
                                }
                            } catch (Exception e) {
                                failures.add("closed index exception: " + e);
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        failures.add("interrupted");
                    }
                });
            }

            start.countDown();
            for (int gen = 2; gen <= 6; gen++) {
                reindex(List.of(doc("https://gen-" + gen, "alpha beta gamma"),
                        doc("https://gen-" + gen, "alpha delta epsilon")));
            }
        } finally {
            pool.shutdownNow();
        }

        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS));
        assertTrue(failures.isEmpty(), "failures: " + failures);
        assertEquals(6, buildSeq.get(), "six reindex generations published");
    }

    @Test
    void oldBuildDirectoriesRemovedAfterLastLeaseReleased() throws Exception {
        reindex(List.of(doc("https://gen-1", "alpha")));
        Path build1 = tempDir.resolve("builds").resolve("build-1");
        assertTrue(Files.exists(build1), "build-1 present while current");

        reindex(List.of(doc("https://gen-2", "alpha beta")));
        reindex(List.of(doc("https://gen-3", "alpha beta gamma")));

        // build-1 was retired and had no outstanding readers: it must be gone.
        waitUntil(() -> !Files.exists(build1), "build-1 removed after last release");
        Path build2 = tempDir.resolve("builds").resolve("build-2");
        waitUntil(() -> !Files.exists(build2), "build-2 removed after last release");

        // The newest generation stays alive until it too is superseded.
        Path build3 = tempDir.resolve("builds").resolve("build-3");
        assertTrue(Files.exists(build3), "build-3 still current");
    }

    @Test
    void outstandingLeaseKeepsOldBuildAliveUntilReleased() throws Exception {
        reindex(List.of(doc("https://gen-1", "alpha")));
        Path build1 = tempDir.resolve("builds").resolve("build-1");

        ConcurrentIndex.Lease<SearchEngineBuild> lease = currentIndex.lease();
        try {
            reindex(List.of(doc("https://gen-2", "alpha beta")));
            assertTrue(Files.exists(build1), "old build kept alive by outstanding lease");
            try (ConcurrentIndex.Lease<SearchEngineBuild> fresh = currentIndex.lease()) {
                assertEquals(1, fresh.value().docUrls().size());
                assertFalse(fresh.value().docUrls().get(1).contains("gen-1"));
            }
        } finally {
            lease.close();
        }
        waitUntil(() -> !Files.exists(build1), "old build removed after lease released");
    }

    @Test
    void failedReindexLeavesCurrentIndexUntouched() throws Exception {
        reindex(List.of(doc("https://gen-1", "alpha")));

        // Force a build failure: reserve the next build directory as a file so
        // Files.createDirectories inside SearchEngineBuilder throws.
        Path blocker = tempDir.resolve("builds").resolve("build-2");
        Files.writeString(blocker, "not a directory");
        try {
            reindex(List.of(doc("https://gen-2", "alpha beta")));
            org.junit.jupiter.api.Assertions.fail("expected reindex to fail");
        } catch (Exception expected) {
            // expected
        }

        assertFalse(Files.exists(blocker), "failed build directory cleaned up");

        // The active index still serves the original generation.
        try (ConcurrentIndex.Lease<SearchEngineBuild> lease = currentIndex.lease()) {
            SearchEngineBuild build = lease.value();
            assertEquals(1, build.docUrls().size());
            assertTrue(build.docUrls().get(1).contains("gen-1"));
            var result = build.engine().retrieveCandidates("alpha", 10);
            assertFalse(result.ranked().isEmpty());
        }
        // The failed build consumed a sequence number but never got published.
        assertEquals(2, buildSeq.get(), "failed build consumed a sequence slot");
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition, String message) throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(50);
        }
        org.junit.jupiter.api.Assertions.fail("timed out waiting for: " + message);
    }
}
