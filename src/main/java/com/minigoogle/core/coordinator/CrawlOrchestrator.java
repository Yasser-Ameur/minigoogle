package com.minigoogle.core.coordinator;

public interface CrawlOrchestrator {
    void start();
    void stop();
    int activeWorkers();
}
