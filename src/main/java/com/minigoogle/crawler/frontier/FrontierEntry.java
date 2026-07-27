package com.minigoogle.crawler.frontier;

import com.minigoogle.crawler.model.CrawlTask;

/** Priority wrapper around {@link CrawlTask} for frontier queue ordering. */
public class FrontierEntry implements Comparable<FrontierEntry> {

    private final CrawlTask task;

    public FrontierEntry(CrawlTask task) {
        this.task = task;
    }

    public CrawlTask getTask() {
        return task;
    }

    @Override
    public int compareTo(FrontierEntry other) {
        return Integer.compare(other.task.getPriority(), this.task.getPriority());
    }
}
