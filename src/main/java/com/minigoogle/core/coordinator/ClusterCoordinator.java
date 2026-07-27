package com.minigoogle.core.coordinator;

public interface ClusterCoordinator {
    void start();
    void stop();
    boolean isHealthy();
}
