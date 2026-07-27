package com.minigoogle.network.dto;

/**
 * Standard error payload returned when an API request fails.
 *
 * @param error   A high-level error code (e.g. "INVALID_QUERY", "INTERNAL_SERVER_ERROR").
 * @param message An optional human-readable message providing more details.
 */
public record ErrorResponse(
        String error,
        String message
) {
}
