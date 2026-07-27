package com.minigoogle.crawler.normalization;

import org.junit.jupiter.api.Test;
import java.net.URI;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for StandardUrlNormalizer functionality. */
class StandardUrlNormalizerTest {

    private final StandardUrlNormalizer normalizer = new StandardUrlNormalizer();

    @Test
    void testBasicNormalization() {
        Optional<URI> uri = normalizer.normalize("HTTPS://Google.com:443/docs/../index.html?utm_source=test");
        assertTrue(uri.isPresent());
        assertEquals("https://google.com/index.html", uri.get().toString());
    }

    @Test
    void testRemoveDuplicateSlashes() {
        Optional<URI> uri = normalizer.normalize("http://example.com//path//to//page");
        assertTrue(uri.isPresent());
        assertEquals("http://example.com/path/to/page", uri.get().toString());
    }

    @Test
    void testResolveRelativeUrl() {
        URI base = URI.create("https://example.com/folder/");
        Optional<URI> resolved = normalizer.normalize(base, "../index.html");
        assertTrue(resolved.isPresent());
        assertEquals("https://example.com/index.html", resolved.get().toString());
    }

    @Test
    void testRemoveFragment() {
        Optional<URI> uri = normalizer.normalize("https://example.com/page#section1");
        assertTrue(uri.isPresent());
        assertEquals("https://example.com/page", uri.get().toString());
    }
}
