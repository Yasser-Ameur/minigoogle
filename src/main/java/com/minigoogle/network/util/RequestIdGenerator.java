package com.minigoogle.network.util;

import java.util.UUID;

/**
 * Generates unique identifiers for tracking requests across node boundaries.
 */
public class RequestIdGenerator {

    /**
     * @return A newly generated UUID string for a request.
     */
    public static String generate() {
        return UUID.randomUUID().toString();
    }
}
