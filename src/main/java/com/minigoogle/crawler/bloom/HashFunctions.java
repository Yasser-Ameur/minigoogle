package com.minigoogle.crawler.bloom;

/**
 * Utility class providing murmur3, FNV-1a, and DJB2 hash functions
 * used by {@link BloomFilter} for generating multiple independent hash values.
 */
public class HashFunctions {

    private static final long GOLDEN_RATIO = 0x9E3779B97F4A7C15L;
    private static final int FNV_OFFSET_BASIS = 0x811c9dc5;
    private static final int FNV_PRIME = 0x01000193;

    private HashFunctions() {}

    public static long murmur3(String value, int seed) {
        byte[] data = value.getBytes();
        int length = data.length;
        long hash = seed;

        int nblocks = length >> 3;
        for (int i = 0; i < nblocks; i++) {
            int index = i << 3;
            long k = ((long) data[index] & 0xFFL)
                    | (((long) data[index + 1] & 0xFFL) << 8)
                    | (((long) data[index + 2] & 0xFFL) << 16)
                    | (((long) data[index + 3] & 0xFFL) << 24)
                    | (((long) data[index + 4] & 0xFFL) << 32)
                    | (((long) data[index + 5] & 0xFFL) << 40)
                    | (((long) data[index + 6] & 0xFFL) << 48)
                    | (((long) data[index + 7] & 0xFFL) << 56);

            k *= GOLDEN_RATIO;
            k = Long.rotateLeft(k, 31);
            k *= GOLDEN_RATIO;
            hash ^= k;
            hash = Long.rotateLeft(hash, 27);
            hash = hash * 5 + 0x52dce729;
        }

        long k1 = 0;
        int tail = nblocks << 3;
        switch (length - tail) {
            case 7: k1 ^= ((long) data[tail + 6] & 0xFFL) << 48;
            case 6: k1 ^= ((long) data[tail + 5] & 0xFFL) << 40;
            case 5: k1 ^= ((long) data[tail + 4] & 0xFFL) << 32;
            case 4: k1 ^= ((long) data[tail + 3] & 0xFFL) << 24;
            case 3: k1 ^= ((long) data[tail + 2] & 0xFFL) << 16;
            case 2: k1 ^= ((long) data[tail + 1] & 0xFFL) << 8;
            case 1: k1 ^= ((long) data[tail] & 0xFFL);
                    k1 *= GOLDEN_RATIO;
                    k1 = Long.rotateLeft(k1, 31);
                    k1 *= GOLDEN_RATIO;
                    hash ^= k1;
        }

        hash ^= length;
        hash ^= hash >>> 33;
        hash *= 0xff51afd7ed558ccdL;
        hash ^= hash >>> 33;
        hash *= 0xc4ceb9fe1a85ec53L;
        hash ^= hash >>> 33;

        return hash;
    }

    public static long fnv1a(String value) {
        int hash = FNV_OFFSET_BASIS;
        for (int i = 0; i < value.length(); i++) {
            hash ^= value.charAt(i);
            hash *= FNV_PRIME;
        }
        return hash & 0xFFFFFFFFL;
    }

    public static long djb2(String value) {
        long hash = 5381;
        for (int i = 0; i < value.length(); i++) {
            hash = ((hash << 5) + hash) + value.charAt(i);
        }
        return hash;
    }

    public static long[] generateHashes(String value, int count) {
        long[] hashes = new long[count];
        long m1 = murmur3(value, 0x9747b28c);
        long m2 = murmur3(value, 0x42d27d45);
        for (int i = 0; i < count; i++) {
            hashes[i] = Math.abs(m1 + (long) i * m2);
        }
        return hashes;
    }
}
