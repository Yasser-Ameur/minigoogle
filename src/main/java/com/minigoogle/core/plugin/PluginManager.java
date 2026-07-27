package com.minigoogle.core.plugin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class PluginManager {

    private static final Logger logger = LoggerFactory.getLogger(PluginManager.class);

    private final Map<String, Plugin> plugins = new LinkedHashMap<>();
    private final PluginContext context;
    private boolean active = false;

    public PluginManager(PluginContext context) {
        this.context = context;
    }

    public void register(Plugin plugin) {
        if (plugins.containsKey(plugin.name())) {
            logger.warn("Plugin '{}' already registered, skipping", plugin.name());
            return;
        }
        plugins.put(plugin.name(), plugin);
        logger.info("Registered plugin: {} v{}", plugin.name(), plugin.version());
    }

    public void activateAll() {
        for (Plugin plugin : plugins.values()) {
            try {
                plugin.onActivate(context);
                logger.info("Activated plugin: {}", plugin.name());
            } catch (Exception e) {
                logger.error("Failed to activate plugin {}: {}", plugin.name(), e.getMessage(), e);
            }
        }
        active = true;
    }

    public void deactivateAll() {
        for (Plugin plugin : plugins.values()) {
            try {
                plugin.onDeactivate();
                logger.info("Deactivated plugin: {}", plugin.name());
            } catch (Exception e) {
                logger.error("Failed to deactivate plugin {}: {}", plugin.name(), e.getMessage(), e);
            }
        }
        active = false;
    }

    public Plugin get(String name) {
        return plugins.get(name);
    }

    public List<Plugin> listAll() {
        return Collections.unmodifiableList(new ArrayList<>(plugins.values()));
    }

    public boolean isActive() {
        return active;
    }

    public int count() {
        return plugins.size();
    }
}
