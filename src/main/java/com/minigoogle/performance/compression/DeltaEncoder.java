package com.minigoogle.performance.compression;

import com.minigoogle.performance.VariableByteEncoder;

/**
 * Delta encoding for sorted integer arrays.
 *
 * <p>Each value in the output is the difference between the current value
 * and the previous value. Since posting lists are sorted by document ID,
 * deltas tend to be small and compress well with variable-byte encoding.</p>
 *
 * <p>For example, the sorted array {@code [10, 15, 22, 25]} encodes as
 * {@code [10, 5, 7, 3]}.</p>
 */
public final class DeltaEncoder {

    private DeltaEncoder() {
    }

    /**
     * Encodes a sorted integer array using delta encoding.
     *
     * @param sortedValues The sorted array of non-decreasing integers.
     * @return The delta-encoded array of the same length.
     * @throws IllegalArgumentException If the input is empty.
     */
    public static int[] encode(int[] sortedValues) {
        if (sortedValues.length == 0) {
            throw new IllegalArgumentException("Input array must not be empty");
        }
        int[] encoded = new int[sortedValues.length];
        encoded[0] = sortedValues[0];
        for (int i = 1; i < sortedValues.length; i++) {
            encoded[i] = sortedValues[i] - sortedValues[i - 1];
        }
        return encoded;
    }

    /**
     * Decodes a delta-encoded array back to the original sorted values.
     *
     * @param deltaEncoded The delta-encoded array.
     * @return The reconstructed sorted array.
     * @throws IllegalArgumentException If the input is empty.
     */
    public static int[] decode(int[] deltaEncoded) {
        if (deltaEncoded.length == 0) {
            throw new IllegalArgumentException("Input array must not be empty");
        }
        int[] decoded = new int[deltaEncoded.length];
        decoded[0] = deltaEncoded[0];
        for (int i = 1; i < deltaEncoded.length; i++) {
            decoded[i] = decoded[i - 1] + deltaEncoded[i];
        }
        return decoded;
    }

    /**
     * Delta-encodes a sorted array and then compresses the result
     * using variable-byte encoding.
     *
     * @param sortedValues The sorted array of non-decreasing integers.
     * @return The combined delta + VByte compressed byte array.
     */
    public static byte[] encodeAndCompress(int[] sortedValues) {
        return VariableByteEncoder.encode(encode(sortedValues));
    }

    /**
     * Decompresses a variable-byte encoded byte array and then
     * decodes the delta encoding to reconstruct the original sorted values.
     *
     * @param compressed The delta + VByte compressed byte array.
     * @return The reconstructed sorted array.
     */
    public static int[] decompressAndDecode(byte[] compressed) {
        return decode(VariableByteEncoder.decodeAll(compressed));
    }
}
