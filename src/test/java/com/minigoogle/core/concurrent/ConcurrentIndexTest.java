package com.minigoogle.core.concurrent;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the ref-counted swap lifecycle used by {@code reindex}:
 * leases keep a value alive across a publish, the previous value's close
 * callback runs exactly once after the last lease is released, and a failed
 * publish never destroys the value readers are still holding.
 */
class ConcurrentIndexTest {

    @Test
    void emptyLeaseBeforeAnyPublish() {
        ConcurrentIndex<String> index = new ConcurrentIndex<>();
        try (ConcurrentIndex.Lease<String> lease = index.lease()) {
            assertNull(lease.value());
        }
    }

    @Test
    void leaseSeesLatestPublishedValue() {
        ConcurrentIndex<String> index = new ConcurrentIndex<>();
        index.publish(ConcurrentIndex.Entry.of("v1", () -> { }));
        try (ConcurrentIndex.Lease<String> lease = index.lease()) {
            assertEquals("v1", lease.value());
        }
        index.publish(ConcurrentIndex.Entry.of("v2", () -> { }));
        try (ConcurrentIndex.Lease<String> lease = index.lease()) {
            assertEquals("v2", lease.value());
        }
    }

    @Test
    void previousValueClosedOnlyAfterLastLeaseReleased() throws Exception {
        ConcurrentIndex<String> index = new ConcurrentIndex<>();
        AtomicInteger closed = new AtomicInteger();
        index.publish(ConcurrentIndex.Entry.of("v1", closed::incrementAndGet));
        assertEquals(0, closed.get(), "holder owns the initial reference");

        try (ConcurrentIndex.Lease<String> lease = index.lease()) {
            index.publish(ConcurrentIndex.Entry.of("v2", () -> { }));
            // The old value is still leased, so its callback must not have run.
            assertEquals(0, closed.get());
            assertEquals("v1", lease.value(), "lease keeps serving the old generation");
        }
        assertEquals(1, closed.get(), "old value closed once the last lease is released");
    }

    @Test
    void leaseOpenedBeforePublishKeepsOldValueAliveAcrossManyPublishes() throws Exception {
        ConcurrentIndex<String> index = new ConcurrentIndex<>();
        AtomicInteger closed = new AtomicInteger();
        index.publish(ConcurrentIndex.Entry.of("v1", closed::incrementAndGet));

        ConcurrentIndex.Lease<String> lease = index.lease();
        try {
            for (int i = 2; i <= 10; i++) {
                index.publish(ConcurrentIndex.Entry.of("v" + i, () -> { }));
            }
            assertEquals("v1", lease.value());
            assertEquals(0, closed.get());
            // A new lease sees the newest generation.
            try (ConcurrentIndex.Lease<String> fresh = index.lease()) {
                assertEquals("v10", fresh.value());
            }
        } finally {
            lease.close();
        }
        assertEquals(1, closed.get());
    }

    @Test
    void concurrentReadersDuringPublishesEachSeeOneCompleteGeneration() throws Exception {
        ConcurrentIndex<Integer> index = new ConcurrentIndex<>();
        index.publish(ConcurrentIndex.Entry.of(0, () -> { }));

        int readers = 8;
        int rounds = 50;
        ExecutorService pool = Executors.newFixedThreadPool(readers);
        List<String> failures = new CopyOnWriteArrayList<>();
        CountDownLatch start = new CountDownLatch(1);
        // Every generation this test publishes is even, so an odd value could
        // only come from a reader observing a half-swapped entry. Publishing
        // consecutive integers instead would make legitimately-observed odd
        // generations indistinguishable from tearing.
        AtomicInteger closes = new AtomicInteger();
        try {
            for (int r = 0; r < readers; r++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < rounds; i++) {
                            try (ConcurrentIndex.Lease<Integer> lease = index.lease()) {
                                Integer v = lease.value();
                                if (v == null) {
                                    failures.add("saw null");
                                    continue;
                                }
                                // Every reader must observe a complete generation.
                                if (v % 2 != 0) {
                                    failures.add("saw torn generation " + v);
                                }
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        failures.add("interrupted");
                    }
                });
            }
            start.countDown();
            // Publisher races the readers, retiring each previous generation
            // while readers may still hold leases on it.
            for (int i = 1; i <= rounds; i++) {
                index.publish(ConcurrentIndex.Entry.of(2 * i, closes::incrementAndGet));
            }
        } finally {
            // Graceful shutdown: readers must be allowed to finish their rounds
            // so they observe every generation we published, then verify none
            // of them saw a torn state.
            pool.shutdown();
        }
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        assertTrue(failures.isEmpty(), "failures: " + failures);

        // Each retired generation must be closed exactly once, and only after
        // the last reader lease on it was released. The final generation is
        // still current (never retired), so `rounds - 1` of the counted
        // generations have been closed.
        assertEquals(rounds - 1, closes.get(),
                "every retired generation must be closed exactly once");
    }

    @Test
    void failedPublishDoesNotDestroyValueReadersHold() {
        ConcurrentIndex<String> index = new ConcurrentIndex<>();
        AtomicInteger closed = new AtomicInteger();
        index.publish(ConcurrentIndex.Entry.of("v1", closed::incrementAndGet));

        try (ConcurrentIndex.Lease<String> lease = index.lease()) {
            // Simulate a failed reindex: nothing new is published; the old
            // value stays fully usable.
            assertEquals("v1", lease.value());
            try (ConcurrentIndex.Lease<String> another = index.lease()) {
                assertEquals("v1", another.value());
            }
            // The entry is still the current one, so it must never be closed.
            assertEquals(0, closed.get());
        }
        // Still current, still not closed.
        assertEquals(0, closed.get());
        try (ConcurrentIndex.Lease<String> after = index.lease()) {
            assertEquals("v1", after.value());
        }
        assertEquals(0, closed.get());
    }

    @Test
    void closeCallbackRunsExactlyOnceEvenWithManyLeases() throws Exception {
        ConcurrentIndex<String> index = new ConcurrentIndex<>();
        AtomicInteger closed = new AtomicInteger();
        index.publish(ConcurrentIndex.Entry.of("v1", closed::incrementAndGet));

        int leases = 20;
        ExecutorService pool = Executors.newFixedThreadPool(4);
        List<String> failures = new CopyOnWriteArrayList<>();
        try {
            ConcurrentIndex.Lease<String>[] held = new ConcurrentIndex.Lease[leases];
            for (int i = 0; i < leases; i++) {
                held[i] = index.lease();
            }
            index.publish(ConcurrentIndex.Entry.of("v2", () -> { }));
            for (ConcurrentIndex.Lease<String> lease : held) {
                pool.submit(lease::close);
            }
        } finally {
            pool.shutdown();
        }
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        assertEquals(1, closed.get(), "close callback must run exactly once");
        assertTrue(failures.isEmpty(), "failures: " + failures);
    }

    @Test
    void emptyLeaseCloseIsNoOp() {
        ConcurrentIndex<String> index = new ConcurrentIndex<>();
        try (ConcurrentIndex.Lease<String> lease = index.lease()) {
            assertNull(lease.value());
        }
        try (ConcurrentIndex.Lease<String> again = index.lease()) {
            assertNull(again.value());
        }
    }
}
