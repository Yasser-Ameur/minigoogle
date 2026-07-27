package com.minigoogle.semantic.embedding;

import java.util.Arrays;
import java.util.Random;
import java.util.Objects;

/**
 * An immutable dense vector wrapping a double array with common linear algebra utilities.
 *
 * <p>All operations return new instances, preserving immutability.
 * Input arrays are defensively copied to prevent external mutation.</p>
 */
public final class DenseVector {

    private final double[] data;

    /**
     * Creates a DenseVector from the given array. A defensive copy is made.
     *
     * @param data The underlying data. Must not be null or empty.
     */
    public DenseVector(double[] data) {
        Objects.requireNonNull(data, "data must not be null");
        if (data.length == 0) {
            throw new IllegalArgumentException("data must not be empty");
        }
        this.data = data.clone();
    }

    /**
     * Creates a zero vector of the specified dimension.
     *
     * @param dimension The number of elements. Must be positive.
     * @return A new zero vector.
     */
    public static DenseVector zero(int dimension) {
        if (dimension <= 0) {
            throw new IllegalArgumentException("dimension must be positive");
        }
        return new DenseVector(new double[dimension]);
    }

    /**
     * Creates a vector with random values sampled from a standard normal distribution.
     *
     * @param dimension The number of elements. Must be positive.
     * @param rng       The random number generator.
     * @return A new random vector.
     */
    public static DenseVector random(int dimension, Random rng) {
        if (dimension <= 0) {
            throw new IllegalArgumentException("dimension must be positive");
        }
        Objects.requireNonNull(rng, "rng must not be null");
        double[] data = new double[dimension];
        for (int i = 0; i < dimension; i++) {
            data[i] = rng.nextGaussian();
        }
        return new DenseVector(data);
    }

    /**
     * Returns the value at the specified index.
     *
     * @param index The zero-based index.
     * @return The value at that position.
     */
    public double get(int index) {
        return data[index];
    }

    /**
     * Returns the dimension (number of elements) of this vector.
     *
     * @return The dimension.
     */
    public int dimension() {
        return data.length;
    }

    /**
     * Returns a copy of the underlying array.
     *
     * @return A defensive copy of the data array.
     */
    public double[] toArray() {
        return data.clone();
    }

    /**
     * Computes the dot product of this vector with another.
     *
     * @param other The other vector. Must have the same dimension.
     * @return The dot product value.
     */
    public double dot(DenseVector other) {
        checkDimension(other);
        double result = 0;
        for (int i = 0; i < data.length; i++) {
            result += data[i] * other.data[i];
        }
        return result;
    }

    /**
     * Computes the L2 (Euclidean) norm of this vector.
     *
     * @return The L2 norm.
     */
    public double l2Norm() {
        double sum = 0;
        for (double v : data) {
            sum += v * v;
        }
        return Math.sqrt(sum);
    }

    /**
     * Returns a unit-length copy of this vector.
     * If the vector is zero, a zero vector of the same dimension is returned.
     *
     * @return A normalized copy.
     */
    public DenseVector normalize() {
        double norm = l2Norm();
        if (norm == 0) {
            return this;
        }
        double[] result = new double[data.length];
        for (int i = 0; i < data.length; i++) {
            result[i] = data[i] / norm;
        }
        return new DenseVector(result);
    }

    /**
     * Returns the element-wise sum of this vector and another.
     *
     * @param other The other vector. Must have the same dimension.
     * @return A new vector containing the sum.
     */
    public DenseVector add(DenseVector other) {
        checkDimension(other);
        double[] result = new double[data.length];
        for (int i = 0; i < data.length; i++) {
            result[i] = data[i] + other.data[i];
        }
        return new DenseVector(result);
    }

    /**
     * Returns this vector scaled by the given factor.
     *
     * @param scalar The scale factor.
     * @return A new scaled vector.
     */
    public DenseVector scale(double scalar) {
        double[] result = new double[data.length];
        for (int i = 0; i < data.length; i++) {
            result[i] = data[i] * scalar;
        }
        return new DenseVector(result);
    }

    private void checkDimension(DenseVector other) {
        if (data.length != other.data.length) {
            throw new IllegalArgumentException(
                    "Dimension mismatch: " + data.length + " vs " + other.data.length);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DenseVector that)) return false;
        return Arrays.equals(data, that.data);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(data);
    }

    @Override
    public String toString() {
        return "DenseVector" + Arrays.toString(data);
    }
}
