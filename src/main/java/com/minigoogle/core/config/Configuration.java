package com.minigoogle.core.config;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public final class Configuration {

    private final Map<String, String> properties;

    public Configuration(Map<String, String> properties) {
        this.properties = Collections.unmodifiableMap(new HashMap<>(properties));
    }

    public Configuration() {
        this(Collections.emptyMap());
    }

    public String get(String key) {
        return properties.get(key);
    }

    public String get(String key, String defaultValue) {
        return properties.getOrDefault(key, defaultValue);
    }

    public int getInt(String key, int defaultValue) {
        String val = properties.get(key);
        if (val == null) return defaultValue;
        try {
            return Integer.parseInt(val);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public long getLong(String key, long defaultValue) {
        String val = properties.get(key);
        if (val == null) return defaultValue;
        try {
            return Long.parseLong(val);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        String val = properties.get(key);
        if (val == null) return defaultValue;
        return Boolean.parseBoolean(val);
    }

    public Map<String, String> toMap() {
        return properties;
    }

    public boolean isEmpty() {
        return properties.isEmpty();
    }
}
