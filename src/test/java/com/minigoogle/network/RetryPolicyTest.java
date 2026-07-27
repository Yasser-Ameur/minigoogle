package com.minigoogle.network;

import com.minigoogle.network.retry.RetryPolicy;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for RetryPolicy functionality. */
class RetryPolicyTest {

    @Test
    void testSuccessOnFirstAttempt() throws Exception {
        RetryPolicy policy = new RetryPolicy(3, 10);
        String result = policy.execute(() -> "OK");
        assertEquals("OK", result);
    }

    @Test
    void testSuccessAfterRetries() throws Exception {
        AtomicInteger attempts = new AtomicInteger(0);
        RetryPolicy policy = new RetryPolicy(3, 10); // 10ms base delay to keep test fast

        String result = policy.execute(() -> {
            if (attempts.incrementAndGet() < 3) {
                throw new RuntimeException("Transient failure");
            }
            return "OK";
        });

        assertEquals("OK", result);
        assertEquals(3, attempts.get());
    }

    @Test
    void testFailsAfterMaxRetries() {
        RetryPolicy policy = new RetryPolicy(2, 10);

        Exception thrown = assertThrows(Exception.class, () ->
                policy.execute(() -> {
                    throw new RuntimeException("Permanent failure");
                })
        );

        assertTrue(thrown.getMessage().contains("failed after 2 retries"));
    }
}
