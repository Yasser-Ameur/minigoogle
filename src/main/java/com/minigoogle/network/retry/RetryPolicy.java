package com.minigoogle.network.retry;

/**
 * Implements exponential backoff for network requests.
 */
public class RetryPolicy {

    private final int maxRetries;
    private final long baseDelayMs;

    public RetryPolicy(int maxRetries, long baseDelayMs) {
        this.maxRetries = maxRetries;
        this.baseDelayMs = baseDelayMs;
    }

    public RetryPolicy() {
        this(3, 1000); // Default: 3 retries, starting with a 1 second delay
    }

    /**
     * Executes a given operation with exponential backoff on failure.
     *
     * @param operation The operation to execute, returning a result of type T.
     * @param <T>       The return type.
     * @return The result of the operation if successful.
     * @throws Exception If the operation fails after all retries.
     */
    public <T> T execute(RetryableOperation<T> operation) throws Exception {
        int attempt = 0;
        while (true) {
            try {
                return operation.execute();
            } catch (Exception e) {
                if (attempt >= maxRetries) {
                    throw new Exception("Operation failed after " + maxRetries + " retries", e);
                }
                long delay = baseDelayMs * (1L << attempt);
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new Exception("Retry interrupted", ie);
                }
                attempt++;
            }
        }
    }

    @FunctionalInterface
    public interface RetryableOperation<T> {
        T execute() throws Exception;
    }
}
