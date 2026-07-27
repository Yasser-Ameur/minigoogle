package com.minigoogle.crawler.frontier;

import com.minigoogle.crawler.model.UrlTask;
import java.util.Comparator;
import java.util.concurrent.PriorityBlockingQueue;

/**
 * In-memory priority queue wrapper for crawl task scheduling.
 * Orders tasks by depth for BFS-like traversal using a thread-safe
 * {@link PriorityBlockingQueue}.
 */
public class InMemoryFrontierQueue implements FrontierQueue {

    // Simple priority based on depth for now (BFS-like)
    private final PriorityBlockingQueue<UrlTask> queue = new PriorityBlockingQueue<>(
        10000, 
        Comparator.comparingInt(UrlTask::depth)
    );

    @Override
    public void add(UrlTask task) {
        if (task != null) {
            queue.offer(task);
        }
    }

    @Override
    public UrlTask take() throws InterruptedException {
        return queue.take();
    }
    
    @Override
    public boolean isEmpty() {
        return queue.isEmpty();
    }
    
    public int size() {
        return queue.size();
    }
}
