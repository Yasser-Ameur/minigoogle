package com.minigoogle.core.concurrent;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Thread-safe holder of a single "current" resource with copy-on-write swap and
 * reference-counted retirement.
 *
 * <p>Readers acquire a lease on the current value, which keeps that value's
 * resources alive, and release it when done. A writer publishes a new value
 * atomically; the previous value is closed only after the writer's ownership
 * reference and every outstanding reader lease have been released. This lets a
 * reindex/build run concurrently with readers serving the previous value and
 * guarantees no reader ever observes a closed value.</p>
 *
 * @param <T> the type of the managed resource
 */
public final class ConcurrentIndex<T> {

    private volatile Entry<T> current;

    /**
     * Takes a lease on the current value. The lease's {@link Lease#close()} must
     * be called when the caller is done (prefer try-with-resources).
     *
     * @return a lease whose value is null when no value has been published yet
     */
    public Lease<T> lease() {
        while (true) {
            Entry<T> entry = current;
            if (entry == null) {
                return Lease.empty();
            }
            T value = entry.acquire();
            if (value != null) {
                return new Lease<>(entry);
            }
            // The entry was retired between reading `current` and acquiring;
            // loop to pick up the newly published value.
        }
    }

    /**
     * Publishes a new current value. The previously published value is retired
     * and its resources are released once no readers hold it.
     */
    public void publish(Entry<T> entry) {
        Entry<T> previous = current;
        current = entry;
        if (previous != null) {
            previous.retire();
        }
    }

    /**
     * A published value together with its resource-release callback. The
     * callback runs exactly once, when the value is no longer referenced by
     * the holder or any reader lease.
     */
    public static final class Entry<T> {

        private final T value;
        private final Runnable onClosed;
        private final AtomicInteger references = new AtomicInteger(1);
        private volatile boolean retired;

        private Entry(T value, Runnable onClosed) {
            this.value = value;
            this.onClosed = onClosed;
        }

        /**
         * @param value      the managed value
         * @param onClosed   called exactly once when the value is fully released
         */
        public static <T> Entry<T> of(T value, Runnable onClosed) {
            return new Entry<>(value, onClosed);
        }

        /**
         * Acquires a reader reference. Returns null when the entry has been
         * retired, in which case the caller must re-read the current value.
         */
        T acquire() {
            while (true) {
                int n = references.get();
                if (n <= 0 || retired) {
                    return null;
                }
                if (references.compareAndSet(n, n + 1)) {
                    return value;
                }
            }
        }

        /**
         * Releases a reader reference. When the last reference (the holder's
         * ownership) is released, the close callback runs.
         */
        public void release() {
            if (references.decrementAndGet() == 0) {
                onClosed.run();
            }
        }

        /**
         * Revokes the holder's ownership. The close callback runs once the
         * value has no remaining readers.
         */
        void retire() {
            retired = true;
            release();
        }
    }

    /**
     * A held reader reference. Always close it (try-with-resources).
     */
    public static final class Lease<T> implements AutoCloseable {

        private static final Lease<?> EMPTY = new Lease<>(null);

        private final Entry<T> entry;

        private Lease(Entry<T> entry) {
            this.entry = entry;
        }

        /**
         * @return the leased value, or null when no value is published
         */
        public T value() {
            return entry == null ? null : entry.value;
        }

        @Override
        public void close() {
            if (entry != null) {
                entry.release();
            }
        }

        @SuppressWarnings("unchecked")
        static <T> Lease<T> empty() {
            return (Lease<T>) EMPTY;
        }
    }
}
