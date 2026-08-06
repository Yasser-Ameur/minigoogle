package com.minigoogle.cluster;

/**
 * A single Raft log entry. Indexes are 1-based; index 0 is the empty-log
 * sentinel that does not correspond to a stored entry.
 */
public record LogEntry(int index, int term, byte[] payload) {
}
