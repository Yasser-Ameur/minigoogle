package com.minigoogle.cluster;

/**
 * A captured state-machine snapshot used to compact the Raft log.
 *
 * <p>Records the applied-state data plus the log position it covers. Because
 * compaction re-bases the log at {@code lastIncludedIndex}, the snapshot also
 * serves as the log base across restarts: a compacted {@code raft-log.bin}
 * tail is only interpretable with the snapshot's {@code lastIncludedIndex}
 * and {@code lastIncludedTerm}.
 */
public record RaftSnapshot(int lastIncludedIndex, int lastIncludedTerm, byte[] data) {
}
