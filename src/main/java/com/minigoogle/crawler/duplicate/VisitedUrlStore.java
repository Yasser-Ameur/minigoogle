package com.minigoogle.crawler.duplicate;

import java.net.URI;

/**
 * Responsible for tracking visited URLs to prevent duplicate crawling.
 */
public interface VisitedUrlStore {
    
    /**
     * Checks if a URL has been visited, and if not, marks it as visited.
     *
     * @param uri The normalized URI to check and mark.
     * @return true if the URL was ALREADY visited, false if it was NEW (and is now marked as visited).
     */
    boolean isVisitedOrMark(URI uri);
}
