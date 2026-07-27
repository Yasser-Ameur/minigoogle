package com.minigoogle.network.serialization;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/**
 * Centralized serialization wrapper.
 * Ensures consistent configuration (e.g. date formats, null handling) across all services.
 */
public class JsonSerializer {

    private static final Gson GSON = new GsonBuilder()
            // In a real system, you might configure specific date formats or type adapters here
            .create();

    public static String toJson(Object obj) {
        return GSON.toJson(obj);
    }

    public static <T> T fromJson(String json, Class<T> clazz) {
        return GSON.fromJson(json, clazz);
    }
}
