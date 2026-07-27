package com.minigoogle.crawler.frontier;

import com.minigoogle.crawler.model.UrlTask;

/**
 * Manages the queue of URLs waiting to be crawled.
 */
public interface FrontierQueue {
    
    /**
     * Adds a task to the queue if it's eligible.
     *
     * @param task The task to add.
     */
    void add(UrlTask task);
    
    /**
     * Takes the next available task from the queue, blocking if necessary.
     *
     * @return The next UrlTask.
     * @throws InterruptedException if the thread is interrupted while waiting.
     */
    UrlTask take() throws InterruptedException;
    
    /**
     * @return true if the queue is empty.
     */
    boolean isEmpty();
}
