package com.minigoogle.cluster;

/**
 * Consumes Raft-committed log entries.
 *
 * <p>An implementation is invoked once per committed entry, in increasing
 * index order, single-threaded, by the consensus layer. Every node applies the
 * same committed prefix deterministically, so a state machine rebuilt from the
 * log matches the leader's state.
 */
public interface StateMachine {

    /**
     * Applies one committed entry to this state machine.
     *
     * @param entry The committed log entry, in index order.
     */
    void apply(LogEntry entry);

    /**
     * @return Whether this state machine can capture and restore its full
     *         state via {@link #snapshot()} / {@link #restore(byte[])}. Only
     *         snapshotable state machines participate in log compaction; a
     *         non-snapshotable state machine is never snapshotted, so a
     *         follower can never be handed an empty snapshot.
     */
    default boolean isSnapshotable() {
        return false;
    }

    /**
     * Captures the full applied state as opaque bytes. Invoked under the
     * consensus lock, so the returned bytes are internally consistent with
     * {@code commitIndex}.
     *
     * @return The serialized state.
     */
    default byte[] snapshot() {
        throw new UnsupportedOperationException("State machine does not support snapshots");
    }

    /**
     * Replaces the full applied state with the given snapshot bytes, as when a
     * node restarts from a durable snapshot or installs a leader's snapshot.
     *
     * @param snapshot The serialized state from {@link #snapshot()}.
     */
    default void restore(byte[] snapshot) {
        throw new UnsupportedOperationException("State machine does not support snapshots");
    }
}
