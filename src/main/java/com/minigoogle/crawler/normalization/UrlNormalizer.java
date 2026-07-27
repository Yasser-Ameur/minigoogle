package com.minigoogle.crawler.normalization;

import java.net.URI;
import java.util.Optional;

/**
 * Responsible for normalizing URLs to prevent duplicate crawling.
 */
public interface UrlNormalizer {
    
    /**
     * Normalizes a given URL string.
     *
     * @param rawUrl The raw URL string.
     * @return An Optional containing the normalized URI, or empty if the URL is invalid.
     */
    Optional<URI> normalize(String rawUrl);
    
    /**
     * Normalizes a relative or absolute URL string against a base URI.
     *
     * @param baseUri The base URI.
     * @param rawUrl The raw URL string (can be relative).
     * @return An Optional containing the normalized absolute URI, or empty if invalid.
     */
    Optional<URI> normalize(URI baseUri, String rawUrl);
}
