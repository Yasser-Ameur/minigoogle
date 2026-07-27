package com.minigoogle.core.plugin;

public interface Plugin {
    String name();
    String version();
    void onActivate(PluginContext context);
    void onDeactivate();
}
