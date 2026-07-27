package com.minigoogle.distributed.query.scheduling;

import org.junit.jupiter.api.Test;

import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for QueryScheduler functionality. */
class QuerySchedulerTest {

    @Test
    void testSubmitSearch() throws Exception {
        QueryScheduler scheduler = new QueryScheduler();
        AtomicInteger counter = new AtomicInteger(0);
        Future<Integer> future = scheduler.submitSearch(counter::incrementAndGet);
        assertEquals(1, future.get(5, TimeUnit.SECONDS));
        scheduler.shutdownNow();
    }

    @Test
    void testSubmitMerge() throws Exception {
        QueryScheduler scheduler = new QueryScheduler();
        Future<String> future = scheduler.submitMerge(() -> "merged");
        assertEquals("merged", future.get(5, TimeUnit.SECONDS));
        scheduler.shutdownNow();
    }

    @Test
    void testSubmitBackground() throws Exception {
        QueryScheduler scheduler = new QueryScheduler();
        Future<Boolean> future = scheduler.submitBackground(() -> {
            Thread.sleep(50);
            return true;
        });
        assertTrue(future.get(5, TimeUnit.SECONDS));
        scheduler.shutdownNow();
    }

    @Test
    void testDefaultConstructor() {
        QueryScheduler scheduler = new QueryScheduler();
        assertNotNull(scheduler);
        scheduler.shutdownNow();
    }

    @Test
    void testShutdownGracefully() {
        QueryScheduler scheduler = new QueryScheduler();
        scheduler.submitBackground(() -> {
            Thread.sleep(10);
            return null;
        });
        scheduler.shutdown(1000);
        // Should not throw
    }
}
