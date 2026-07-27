package com.minigoogle.indexer.stemming;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Tests for PorterStemmer functionality. */
class PorterStemmerTest {
    @Test
    void testBasicStemming() {
        PorterStemmer stemmer = new PorterStemmer();
        assertEquals("run", stemmer.stem("running"));
        assertEquals("run", stemmer.stem("runner"));
        assertEquals("run", stemmer.stem("runs"));
    }
}
