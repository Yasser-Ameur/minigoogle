package com.minigoogle.performance;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Variable-byte integer encoding for compact posting list storage.
 *
 * Per ARCHITECTURE.md Ch12:
 *   Variable-byte encoding compresses integer posting lists
 *   using fewer bytes for smaller values.
 *
 * Encoding: each byte uses 7 data bits + 1 continuation bit.
 * If the continuation bit is 1, more bytes follow.
 * Small values (0-127) encode in 1 byte; large values use more.
 */
public class VariableByteEncoder {

    /**
     * Encodes an integer using variable-byte encoding.
     *
     * @param value The integer to encode (must be >= 0).
     * @return The encoded byte array.
     */
    public static byte[] encode(int value) {
        if (value < 0) {
            throw new IllegalArgumentException("Value must be non-negative");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while (value > 127) {
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.write(value & 0x7F);
        return out.toByteArray();
    }

    /**
     * Encodes an array of integers.
     */
    public static byte[] encode(int[] values) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int v : values) {
            byte[] encoded = encode(v);
            out.write(encoded, 0, encoded.length);
        }
        return out.toByteArray();
    }

    /**
     * Decodes a variable-byte encoded byte array back to an integer.
     *
     * @param data  The encoded bytes.
     * @param offset The start offset within the array.
     * @return A DecodeResult containing the value and bytes consumed.
     */
    public static DecodeResult decode(byte[] data, int offset) {
        int result = 0;
        int shift = 0;
        int bytesConsumed = 0;

        while (offset + bytesConsumed < data.length) {
            byte b = data[offset + bytesConsumed];
            result |= (b & 0x7F) << shift;
            bytesConsumed++;
            if ((b & 0x80) == 0) {
                break;
            }
            shift += 7;
        }

        return new DecodeResult(result, bytesConsumed);
    }

    /**
     * Decodes an entire byte array into an integer array.
     */
    public static int[] decodeAll(byte[] data) {
        List<Integer> values = new ArrayList<>();
        int offset = 0;
        while (offset < data.length) {
            DecodeResult result = decode(data, offset);
            values.add(result.value());
            offset += result.bytesConsumed();
        }
        return values.stream().mapToInt(Integer::intValue).toArray();
    }

    public record DecodeResult(int value, int bytesConsumed) {
    }
}
