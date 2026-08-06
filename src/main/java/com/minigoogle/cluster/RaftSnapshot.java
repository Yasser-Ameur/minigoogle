package com.minigoogle.cluster;

/**
 * A captured state-machine snapshot used to compact the Raft log.
 *
 * <p>Records the applied-state data plus the log position it covers. Because
 * compaction re-bases the log at {@code lastIncludedIndex}, the snapshot also
 * serves as the log base across restarts: a compacted {@code raft-log.bin}
 * tail is only interpretable with the snapshot's {@code lastIncludedIndex}
 * and {@code lastIncludedTerm}.
 *
 * <p>The snapshot also carries the committed configuration at capture time
 * (v2 files, see {@link com.minigoogle.storage.metadata.RaftSnapshotStore}), so
 * a node that joins by InstallSnapshot, or a restarted node whose log was
 * compacted, learns the member set even when the config-change entries were
 * compacted away. An empty {@link ClusterConfiguration#EMPTY} means the
 * snapshot predates membership reconfiguration and carries no config.
 */
public record RaftSnapshot(int lastIncludedIndex, int lastIncludedTerm, byte[] data,
                           ClusterConfiguration config) {

    /**
     * Creates a snapshot without a committed configuration (v1-style), used by
     * pre-reconfiguration callers and tests.
     */
    public RaftSnapshot(int lastIncludedIndex, int lastIncludedTerm, byte[] data) {
        this(lastIncludedIndex, lastIncludedTerm, data, ClusterConfiguration.EMPTY);
    }
}
