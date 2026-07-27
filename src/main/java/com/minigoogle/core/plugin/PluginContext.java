package com.minigoogle.core.plugin;

import com.minigoogle.core.config.Configuration;
import com.minigoogle.core.event.EventBus;
import com.minigoogle.core.metrics.MetricRegistry;

public record PluginContext(
    Configuration configuration,
    EventBus eventBus,
    MetricRegistry metricRegistry
) {
}
