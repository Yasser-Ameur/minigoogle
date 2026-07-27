package com.minigoogle.crawler.bloom;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for HashFunctions (murmur3, FNV-1a) functionality. */
class HashFunctionsTest {

    @Test
    void testMurmur3Consistency() {
        String value = "https://example.com";
        long hash1 = HashFunctions.murmur3(value, 0);
        long hash2 = HashFunctions.murmur3(value, 0);
        assertEquals(hash1, hash2);
    }

    @Test
    void testMurmur3DifferentSeedsProduceDifferentHashes() {
        String value = "https://example.com";
        long hash1 = HashFunctions.murmur3(value, 0);
        long hash2 = HashFunctions.murmur3(value, 1);
        assertNotEquals(hash1, hash2);
    }

    @Test
    void testFnv1aConsistency() {
        String value = "https://example.com";
        long hash1 = HashFunctions.fnv1a(value);
        long hash2 = HashFunctions.fnv1a(value);
        assertEquals(hash1, hash2);
    }

    @Test
    void testDjb2Consistency() {
        String value = "https://example.com";
        long hash1 = HashFunctions.djb2(value);
        long hash2 = HashFunctions.djb2(value);
        assertEquals(hash1, hash2);
    }

    @Test
    void testGenerateHashes() {
        String value = "https://example.com";
        long[] hashes = HashFunctions.generateHashes(value, 7);

        assertEquals(7, hashes.length);

        boolean allSame = true;
        for (int i = 1; i < hashes.length; i++) {
            if (hashes[i] != hashes[0]) {
                allSame = false;
                break;
            }
        }
        assertFalse(allSame, "All hashes should be different");
    }

    @Test
    void testDifferentInputsProduceDifferentHashes() {
        long hash1 = HashFunctions.murmur3("https://example.com", 0);
        long hash2 = HashFunctions.murmur3("https://google.com", 0);
        assertNotEquals(hash1, hash2);
    }

    @Test
    void testEmptyString() {
        assertDoesNotThrow(() -> {
            HashFunctions.murmur3("", 0);
            HashFunctions.fnv1a("");
            HashFunctions.djb2("");
            HashFunctions.generateHashes("", 5);
        });
    }
}
