package com.minigoogle.crawler.scheduler;

import com.minigoogle.crawler.model.CrawlTask;
import com.minigoogle.crawler.model.UrlState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for DomainQueue scheduling functionality. */
class DomainQueueTest {

    private DomainQueue queue;

    @BeforeEach
    void setUp() {
        queue = new DomainQueue("example.com", 1000);
    }

    @Test
    void testEnqueueAndPoll() {
        CrawlTask task = createTask("https://example.com/page1");
        queue.enqueue(task);

        assertEquals(1, queue.size());
        assertFalse(queue.isEmpty());

        CrawlTask polled = queue.pollEligible();
        assertNotNull(polled);
        assertEquals(0, queue.size());
        assertTrue(queue.isEmpty());
    }

    @Test
    void testPollRespectsCrawlDelay() {
        CrawlTask task1 = createTask("https://example.com/page1");
        CrawlTask task2 = createTask("https://example.com/page2");

        queue.enqueue(task1);
        queue.enqueue(task2);

        CrawlTask first = queue.pollEligible();
        assertNotNull(first);

        CrawlTask second = queue.pollEligible();
        assertNull(second, "Second poll should return null due to crawl delay");
    }

    @Test
    void testPollAfterDelay() throws InterruptedException {
        DomainQueue shortDelayQueue = new DomainQueue("example.com", 100);

        CrawlTask task1 = createTask("https://example.com/page1");
        CrawlTask task2 = createTask("https://example.com/page2");

        shortDelayQueue.enqueue(task1);
        shortDelayQueue.enqueue(task2);

        shortDelayQueue.pollEligible();
        Thread.sleep(150);

        CrawlTask second = shortDelayQueue.pollEligible();
        assertNotNull(second, "Second poll should succeed after delay");
    }

    @Test
    void testEmptyQueue() {
        CrawlTask polled = queue.pollEligible();
        assertNull(polled);
    }

    @Test
    void testGetDomain() {
        assertEquals("example.com", queue.getDomain());
    }

    @Test
    void testGetRemainingDelayInitially() {
        long remaining = queue.getRemainingDelayMillis();
        assertEquals(0, remaining, "Initially should have no delay");
    }

    @Test
    void testGetRemainingDelayAfterFetch() {
        queue.enqueue(createTask("https://example.com/page1"));
        queue.pollEligible();

        long remaining = queue.getRemainingDelayMillis();
        assertTrue(remaining > 0, "Should have remaining delay after fetch");
    }

    private CrawlTask createTask(String url) {
        URI uri = URI.create(url);
        return new CrawlTask(uri, "example.com", 0, Instant.now());
    }
}
