package com.minigoogle.performance.util;

/**
 * Simple nanosecond-precision timer for measuring code execution duration.
 *
 * <p>Call {@link #start()} before the code section to time, then call
 * {@link #stop()} to obtain the elapsed duration. The timer can be
 * {@link #reset()} and reused across multiple measurements.</p>
 */
public class Timer {

    private long startNanos;

    /**
     * Records the current time as the start of a measurement interval.
     */
    public void start() {
        startNanos = System.nanoTime();
    }

    /**
     * Stops the timer and returns the elapsed time in nanoseconds
     * since the last {@link #start()} call.
     *
     * @return The elapsed time in nanoseconds.
     */
    public long stop() {
        return System.nanoTime() - startNanos;
    }

    /**
     * Stops the timer and returns the elapsed time in milliseconds
     * since the last {@link #start()} call.
     *
     * @return The elapsed time in milliseconds, as a double for sub-millisecond precision.
     */
    public double stopMillis() {
        return stop() / 1_000_000.0;
    }

    /**
     * Resets the timer, clearing the recorded start time.
     */
    public void reset() {
        startNanos = 0;
    }

    /**
     * Returns the time elapsed since the last {@link #start()} call
     * without stopping the timer.
     *
     * @return The elapsed time in nanoseconds.
     */
    public long getElapsedNanos() {
        return System.nanoTime() - startNanos;
    }
}
