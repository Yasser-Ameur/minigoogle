package com.minigoogle.crawler.bloom;

import com.minigoogle.crawler.duplicate.VisitedUrlStore;

import java.io.*;
import java.net.URI;

/**
 * Bloom filter for URL deduplication with configurable size and hash count.
 * Implements the {@link VisitedUrlStore} interface to provide probabilistic
 * membership testing with tunable false-positive rates.
 */
public class BloomFilter implements VisitedUrlStore, Serializable {

    private static final long serialVersionUID = 1L;

    private final long[] bits;
    private final int numHashes;
    private final int size;

    public BloomFilter(int expectedElements, double falsePositiveRate) {
        this.size = optimalSize(expectedElements, falsePositiveRate);
        this.numHashes = optimalNumHashes(expectedElements, this.size);
        this.bits = new long[(this.size + 63) >>> 6];
    }

    BloomFilter(int size, int numHashes) {
        this.size = size;
        this.numHashes = numHashes;
        this.bits = new long[(size + 63) >>> 6];
    }

    public void add(String value) {
        long[] hashes = HashFunctions.generateHashes(value, numHashes);
        for (int i = 0; i < numHashes; i++) {
            int index = (int) (hashes[i] % size);
            int wordIndex = index >>> 6;
            int bitIndex = index & 63;
            bits[wordIndex] |= (1L << bitIndex);
        }
    }

    public boolean probablyContains(String value) {
        long[] hashes = HashFunctions.generateHashes(value, numHashes);
        for (int i = 0; i < numHashes; i++) {
            int index = (int) (hashes[i] % size);
            int wordIndex = index >>> 6;
            int bitIndex = index & 63;
            if ((bits[wordIndex] & (1L << bitIndex)) == 0) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean isVisitedOrMark(URI uri) {
        if (uri == null) return true;
        String value = uri.toString();
        if (probablyContains(value)) {
            return true;
        }
        add(value);
        return false;
    }

    public int getBitCount() {
        return size;
    }

    public int getHashCount() {
        return numHashes;
    }

    public void merge(BloomFilter other) {
        if (this.size != other.size || this.numHashes != other.numHashes) {
            throw new IllegalArgumentException("Bloom filters must have the same size and hash count to merge");
        }
        for (int i = 0; i < bits.length; i++) {
            bits[i] |= other.bits[i];
        }
    }

    public void save(String filePath) throws IOException {
        try (DataOutputStream dos = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(filePath)))) {
            dos.writeInt(size);
            dos.writeInt(numHashes);
            for (long word : bits) {
                dos.writeLong(word);
            }
        }
    }

    public static BloomFilter load(String filePath) throws IOException {
        try (DataInputStream dis = new DataInputStream(new BufferedInputStream(new FileInputStream(filePath)))) {
            int size = dis.readInt();
            int numHashes = dis.readInt();
            BloomFilter filter = new BloomFilter(size, numHashes);
            for (int i = 0; i < filter.bits.length; i++) {
                filter.bits[i] = dis.readLong();
            }
            return filter;
        }
    }

    private static int optimalSize(int expectedElements, double falsePositiveRate) {
        return (int) Math.ceil(-((double) expectedElements * Math.log(falsePositiveRate)) / (Math.log(2) * Math.log(2)));
    }

    private static int optimalNumHashes(int expectedElements, int size) {
        return Math.max(1, (int) Math.round(((double) size / expectedElements) * Math.log(2)));
    }
}
