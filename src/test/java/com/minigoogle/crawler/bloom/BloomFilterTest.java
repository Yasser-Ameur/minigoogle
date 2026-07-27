package com.minigoogle.crawler.bloom;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for BloomFilter functionality. */
class BloomFilterTest {

    @TempDir
    Path tempDir;

    @Test
    void testAddAndContains() {
        BloomFilter filter = new BloomFilter(1000, 0.01);

        filter.add("https://example.com");
        filter.add("https://google.com");
        filter.add("https://github.com");

        assertTrue(filter.probablyContains("https://example.com"));
        assertTrue(filter.probablyContains("https://google.com"));
        assertTrue(filter.probablyContains("https://github.com"));
        assertFalse(filter.probablyContains("https://unknown.com"));
    }

    @Test
    void testFalsePositiveRate() {
        int expectedElements = 10000;
        double targetFpr = 0.01;
        BloomFilter filter = new BloomFilter(expectedElements, targetFpr);

        for (int i = 0; i < expectedElements; i++) {
            filter.add("url_" + i);
        }

        int falsePositives = 0;
        int testCount = 10000;
        for (int i = 0; i < testCount; i++) {
            if (filter.probablyContains("nonexistent_" + i)) {
                falsePositives++;
            }
        }

        double actualFpr = (double) falsePositives / testCount;
        assertTrue(actualFpr < 0.05, "False positive rate " + actualFpr + " exceeds 5%");
    }

    @Test
    void testNoFalseNegatives() {
        BloomFilter filter = new BloomFilter(1000, 0.01);

        String[] urls = {"https://a.com", "https://b.com", "https://c.com", "https://d.com", "https://e.com"};
        for (String url : urls) {
            filter.add(url);
        }

        for (String url : urls) {
            assertTrue(filter.probablyContains(url), "False negative for: " + url);
        }
    }

    @Test
    void testSaveAndLoad() throws IOException {
        BloomFilter original = new BloomFilter(1000, 0.01);
        original.add("https://example.com");
        original.add("https://google.com");
        original.add("https://github.com");

        String filePath = tempDir.resolve("bloom.bin").toString();
        original.save(filePath);

        BloomFilter loaded = BloomFilter.load(filePath);

        assertEquals(original.getBitCount(), loaded.getBitCount());
        assertEquals(original.getHashCount(), loaded.getHashCount());
        assertTrue(loaded.probablyContains("https://example.com"));
        assertTrue(loaded.probablyContains("https://google.com"));
        assertTrue(loaded.probablyContains("https://github.com"));
        assertFalse(loaded.probablyContains("https://nonexistent.com"));
    }

    @Test
    void testMerge() {
        BloomFilter filter1 = new BloomFilter(1000, 0.01);
        BloomFilter filter2 = new BloomFilter(1000, 0.01);

        filter1.add("https://a.com");
        filter1.add("https://b.com");
        filter2.add("https://c.com");
        filter2.add("https://d.com");

        filter1.merge(filter2);

        assertTrue(filter1.probablyContains("https://a.com"));
        assertTrue(filter1.probablyContains("https://b.com"));
        assertTrue(filter1.probablyContains("https://c.com"));
        assertTrue(filter1.probablyContains("https://d.com"));
    }

    @Test
    void testMergeIncompatibleFilters() {
        BloomFilter filter1 = new BloomFilter(1000, 0.01);
        BloomFilter filter2 = new BloomFilter(5000, 0.01);

        assertThrows(IllegalArgumentException.class, () -> filter1.merge(filter2));
    }

    @Test
    void testVisitedUrlStoreInterface() {
        BloomFilter filter = new BloomFilter(1000, 0.01);
        java.net.URI uri1 = java.net.URI.create("https://example.com");
        java.net.URI uri2 = java.net.URI.create("https://google.com");

        assertFalse(filter.isVisitedOrMark(uri1));
        assertTrue(filter.isVisitedOrMark(uri1));
        assertFalse(filter.isVisitedOrMark(uri2));
        assertTrue(filter.isVisitedOrMark(uri2));
    }

    @Test
    void testNullUri() {
        BloomFilter filter = new BloomFilter(1000, 0.01);
        assertTrue(filter.isVisitedOrMark(null));
    }
}
