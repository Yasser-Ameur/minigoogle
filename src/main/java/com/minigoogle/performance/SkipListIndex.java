package com.minigoogle.performance;

import java.util.ArrayList;
import java.util.List;

/**
 * Skip list index for fast traversal of sorted integer sequences.
 *
 * Per ARCHITECTURE.md Ch12:
 *   Skip lists provide O(log n) seek within sorted posting lists,
 *   enabling fast intersection without scanning every element.
 *
 * Each level skips progressively more elements, allowing the search
 * to "skip ahead" when the target is far from the current position.
 */
public class SkipListIndex {

    private static final int MAX_LEVEL = 16;

    private final int[] data;
    private final int[][] skipPointers; // skipPointers[level][index]
    private final int[] maxAtLevel;     // max value at each level's last entry
    private final int levelCount;

    /**
     * Builds a skip list index over a sorted integer array.
     *
     * @param sortedData The sorted data to index.
     */
    public SkipListIndex(int[] sortedData) {
        this.data = sortedData;
        this.levelCount = Math.min(MAX_LEVEL, (int) (Math.log(sortedData.length) / Math.log(2)) + 1);
        this.skipPointers = new int[levelCount][];
        this.maxAtLevel = new int[levelCount];

        // Build skip pointers: level i skips 2^i elements
        for (int level = 0; level < levelCount; level++) {
            int stride = 1 << level;
            int size = (sortedData.length + stride - 1) / stride;
            skipPointers[level] = new int[size];
            for (int i = 0; i < size; i++) {
                int idx = Math.min(i * stride, sortedData.length - 1);
                skipPointers[level][i] = idx;
            }
            if (size > 0) {
                maxAtLevel[level] = sortedData[skipPointers[level][size - 1]];
            }
        }
    }

    /**
     * Seeks to the first element >= target in O(log n).
     *
     * @param target The value to seek to.
     * @return The index of the first element >= target, or data.length if not found.
     */
    public int seek(int target) {
        int pos = 0;

        // Skip down from highest level
        for (int level = levelCount - 1; level >= 0; level--) {
            while (pos < skipPointers[level].length - 1) {
                int nextIdx = skipPointers[level][pos + 1];
                if (data[nextIdx] < target) {
                    pos++;
                } else {
                    break;
                }
            }
        }

        // Linear scan at level 0
        int startIdx = skipPointers[0][pos];
        while (startIdx < data.length && data[startIdx] < target) {
            startIdx++;
        }
        return startIdx;
    }

    /**
     * Returns true if the sorted data contains the target value.
     */
    public boolean contains(int target) {
        int idx = seek(target);
        return idx < data.length && data[idx] == target;
    }

    /**
     * Returns the underlying data array.
     */
    public int[] getData() {
        return data;
    }

    /**
     * @return The number of elements indexed.
     */
    public int size() {
        return data.length;
    }
}
