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
}
