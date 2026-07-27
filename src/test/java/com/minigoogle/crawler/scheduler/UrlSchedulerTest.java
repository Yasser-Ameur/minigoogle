package com.minigoogle.crawler.scheduler;

import com.minigoogle.crawler.model.CrawlTask;
import com.minigoogle.crawler.model.UrlState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for UrlScheduler functionality. */
class UrlSchedulerTest {

    private UrlScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new UrlScheduler();
    }

    @Test
    void testSubmitAndDispatch() {
        CrawlTask task = createTask("https://example.com/page1", "example.com");
        scheduler.submitTask(task);

        assertEquals(1, scheduler.getQueueSize());
        assertEquals(UrlState.QUEUED, task.getState());

        Optional<CrawlTask> dispatched = scheduler.nextEligibleTask();
        assertTrue(dispatched.isPresent());
        assertEquals("https://example.com/page1", dispatched.get().getUrl().toString());
    }

    @Test
    void testDomainAlternation() {
        CrawlTask task1 = createTask("https://google.com/search", "google.com");
        CrawlTask task2 = createTask("https://github.com/repo", "github.com");
        CrawlTask task3 = createTask("https://google.com/mail", "google.com");
        CrawlTask task4 = createTask("https://github.com/issues", "github.com");

        scheduler.submitTask(task1);
        scheduler.submitTask(task2);
        scheduler.submitTask(task3);
        scheduler.submitTask(task4);

        Optional<CrawlTask> first = scheduler.nextEligibleTask();
        Optional<CrawlTask> second = scheduler.nextEligibleTask();

        assertTrue(first.isPresent());
        assertTrue(second.isPresent());

        String firstDomain = first.get().getDomain();
        String secondDomain = second.get().getDomain();
        assertNotEquals(firstDomain, secondDomain, "Should alternate between domains");
    }

    @Test
    void testPriorityComputation() {
        CrawlTask highPriorityTask = createTask("https://google.com", "google.com");
        CrawlTask lowPriorityTask = createTask("https://randomblog.xyz/page", "randomblog.xyz");

        int highPriority = scheduler.computePriority(highPriorityTask);
        int lowPriority = scheduler.computePriority(lowPriorityTask);

        assertTrue(highPriority >= 0 && highPriority <= 100);
        assertTrue(lowPriority >= 0 && lowPriority <= 100);
    }

    @Test
    void testOnTaskFailed() {
        CrawlTask task = createTask("https://example.com/page1", "example.com");
        scheduler.submitTask(task);
        scheduler.nextEligibleTask();

        scheduler.onTaskFailed(task);

        assertEquals(UrlState.RETRY, task.getState());
        assertEquals(1, task.getRetryCount());
    }

    @Test
    void testOnTaskFailedMaxRetries() {
        CrawlTask task = createTask("https://example.com/page1", "example.com");
        scheduler.submitTask(task);
        scheduler.nextEligibleTask();

        for (int i = 0; i < 4; i++) {
            scheduler.onTaskFailed(task);
            if (task.getState() == UrlState.RETRY) {
                task.requeue();
                scheduler.submitTask(task);
                scheduler.nextEligibleTask();
            }
        }

        assertEquals(UrlState.FAILED, task.getState());
    }

    @Test
    void testEmptyScheduler() {
        assertTrue(scheduler.isEmpty());
        assertEquals(0, scheduler.getQueueSize());

        Optional<CrawlTask> task = scheduler.nextEligibleTask();
        assertFalse(task.isPresent());
    }

    @Test
    void testDomainLinkCount() {
        scheduler.incrementLinkCount("example.com");
        scheduler.incrementLinkCount("example.com");
        scheduler.incrementLinkCount("example.com");

        CrawlTask task = createTask("https://example.com/page1", "example.com");
        scheduler.submitTask(task);

        int priority = scheduler.computePriority(task);
        assertTrue(priority > 0);
    }

    @Test
    void testActiveDomains() {
        scheduler.submitTask(createTask("https://google.com", "google.com"));
        scheduler.submitTask(createTask("https://github.com", "github.com"));

        assertEquals(2, scheduler.getDomainQueueCount());
        assertTrue(scheduler.getActiveDomains().contains("google.com"));
        assertTrue(scheduler.getActiveDomains().contains("github.com"));
    }

    private CrawlTask createTask(String url, String domain) {
        URI uri = URI.create(url);
        return new CrawlTask(uri, domain, 0, Instant.now());
    }
}
