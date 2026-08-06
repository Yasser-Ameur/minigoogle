package com.minigoogle.cluster;

import com.minigoogle.cluster.transport.ClusterProtocol;
import com.minigoogle.cluster.transport.RaftTransport;
import com.minigoogle.cluster.transport.dto.AppendEntriesRequest;
import com.minigoogle.cluster.transport.dto.AppendEntriesResponse;
import com.minigoogle.cluster.transport.dto.InstallSnapshotRequest;
import com.minigoogle.cluster.transport.dto.InstallSnapshotResponse;
import com.minigoogle.cluster.transport.dto.RequestVoteRequest;
import com.minigoogle.cluster.transport.dto.RequestVoteResponse;
import com.minigoogle.storage.metadata.RaftAppliedStore;
import com.minigoogle.storage.metadata.RaftConfigurationStore;
import com.minigoogle.storage.metadata.RaftMetadata;
import com.minigoogle.storage.metadata.RaftMetadataStore;
import com.minigoogle.storage.metadata.RaftSnapshotStore;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Raft consensus protocol for electing a cluster leader and replicating a
 * committed log.
 *
 * <p>Per ARCHITECTURE.md Ch14, Raft provides strong consistency through
 * leader election and log replication:
 *   Nodes are in one of three states: Follower, Candidate, Leader.
 *   Heartbeats from the leader prevent new elections.
 *   If the leader fails, a candidate wins an election and becomes leader.
 *   Entries are appended to the leader's log, replicated to a majority, and
 *   only then committed.
 *
 * <p>This implementation covers leader election and full log replication:
 * leaders append entries to their {@link RaftLog} and replicate them over a
 * {@link RaftTransport}; followers perform the Raft log-consistency check,
 * truncate conflicting tails, and report success; the leader advances
 * {@code commitIndex} only when a current-term entry sits on a majority of
 * peers. Vote requests carry the candidate's last log index/term and are
 * denied to candidates whose log is behind the local one, so a committed
 * entry can never be overwritten by a newly elected leader.
 *
 * <p>Election metadata ({@code currentTerm} and {@code votedFor}) is persisted
 * through an optional {@link RaftMetadataStore}: it is written before every
 * vote reply and every term transition, and restored on construction, so a
 * node never double-votes or regresses its term across a restart. The log
 * itself is persisted through the optional {@link RaftLog} WAL.
 *
 * <p>Membership is reconfigurable through the replicated log: config-change
 * entries ({@link ConfigChange}) commit like any other entry, the committed
 * configuration ({@link ClusterConfiguration}) is persisted through the
 * optional {@link RaftConfigurationStore} and drives every quorum decision
 * (one server at a time, so old and new quorums always intersect), and a
 * leader removed by a committed change steps down. Until a configuration is
 * established, the consensus runs in bootstrap mode and derives its peers and
 * quorum from the peer supplier / cluster size, byte-for-byte as before.
 */
public class RaftConsensus {

    /** Maximum entries sent in a single AppendEntries request. */
    private static final int MAX_ENTRIES_PER_REQUEST = 64;

    /** Floor for the read-barrier quorum wait, independent of heartbeat cadence. */
    private static final long READ_BARRIER_MIN_TIMEOUT_MS = 500;

    private final String nodeId;
    private volatile RaftState state;
    private volatile String currentLeader;
    private volatile int currentTerm;
    private volatile int votesReceived;
    private volatile String votedFor;

    private final RaftTransport transport;
    private final Supplier<List<String>> peerSupplier;
    private final RaftMetadataStore metadataStore;
    private final RaftLog log;
    private final StateMachine stateMachine;
    private final RaftAppliedStore appliedStore;
    private final RaftSnapshotStore snapshotStore;
    private final RaftConfigurationStore configStore;
    private final int snapshotInterval;

    /**
     * The committed Raft configuration: the member set the cluster uses for
     * quorum. Restored from {@link RaftConfigurationStore} on construction.
     * Empty until {@link #initializeConfig(List)} is called or a config-change
     * entry is applied, which keeps the legacy bootstrap mode (quorum derived
     * from the peer supplier / cluster size) byte-for-byte.
     */
    private volatile ClusterConfiguration committedConfig = ClusterConfiguration.EMPTY;

    private volatile boolean configEstablished;

    private volatile int commitIndex;
    private volatile int lastApplied;
    private volatile int lastSnapshotIndex;

    private final Map<String, Integer> nextIndex = new HashMap<>();
    private final Map<String, Integer> matchIndex = new HashMap<>();

    /** Guards the read-barrier rounds below. */
    private final Object barrierLock = new Object();
    private int barrierRound;
    private final Map<Integer, Set<String>> barrierResponders = new HashMap<>();
    private final Map<Integer, CompletableFuture<Boolean>> barrierFutures = new HashMap<>();

    private ScheduledExecutorService scheduler;
    private final long electionTimeoutMs;
    private final long heartbeatIntervalMs;
    private final int clusterSize;

    /**
     * The single pending election-timeout task. Each reschedule cancels the
     * previous task, so a node that keeps hearing from a leader never piles up
     * stale election tasks; without cancellation, a contested startup floods
     * every node with tasks that fire back-to-back and ratchet the term upward
     * without ever converging on a leader.
     */
    private volatile ScheduledFuture<?> electionTimeoutTask;

    /**
     * Creates a Raft consensus node with full configuration.
     *
     * @param nodeId              The unique identifier for this node.
     * @param electionTimeoutMs   The election timeout in milliseconds.
     * @param heartbeatIntervalMs The heartbeat interval in milliseconds.
     * @param clusterSize         The total number of nodes in the cluster.
     */
    public RaftConsensus(String nodeId, long electionTimeoutMs, long heartbeatIntervalMs, int clusterSize) {
        this(nodeId, electionTimeoutMs, heartbeatIntervalMs, clusterSize, null, null);
    }

    /**
     * Creates a Raft consensus node with default cluster size of 3.
     *
     * @param nodeId              The unique identifier for this node.
     * @param electionTimeoutMs   The election timeout in milliseconds.
     * @param heartbeatIntervalMs The heartbeat interval in milliseconds.
     */
    public RaftConsensus(String nodeId, long electionTimeoutMs, long heartbeatIntervalMs) {
        this(nodeId, electionTimeoutMs, heartbeatIntervalMs, 3);
    }

    /**
     * Creates a Raft consensus node with default timeouts.
     *
     * @param nodeId       The unique identifier for this node.
     * @param clusterSize  The total number of nodes in the cluster.
     */
    public RaftConsensus(String nodeId, int clusterSize) {
        this(nodeId, 5000, 1000, clusterSize);
    }

    /**
     * Creates a Raft consensus node with all defaults.
     *
     * @param nodeId The unique identifier for this node.
     */
    public RaftConsensus(String nodeId) {
        this(nodeId, 5000, 1000, 3);
    }

    /**
     * Full configuration with transport and peer discovery.
     *
     * @param nodeId              The unique identifier for this node.
     * @param electionTimeoutMs   The election timeout in milliseconds.
     * @param heartbeatIntervalMs The heartbeat interval in milliseconds.
     * @param clusterSize         Fallback cluster size when no peer supplier is given.
     * @param transport           The transport for cluster RPCs, or {@code null} for in-memory operation.
     * @param peerSupplier        Supplies the current peer IDs, or {@code null} to rely on {@code clusterSize}.
     */
    public RaftConsensus(String nodeId, long electionTimeoutMs, long heartbeatIntervalMs, int clusterSize,
                         RaftTransport transport, Supplier<List<String>> peerSupplier) {
        this(nodeId, electionTimeoutMs, heartbeatIntervalMs, clusterSize, transport, peerSupplier,
                RaftMetadataStore.inMemory());
    }

    /**
     * Full configuration with transport, peer discovery, and durable election
     * metadata. Restores the persisted {@code currentTerm} and {@code votedFor}
     * on construction so a restart never double-votes or regresses its term.
     * A corrupt metadata file fails startup fast (unchecked) rather than
     * silently resetting a vote.
     *
     * @param nodeId              The unique identifier for this node.
     * @param electionTimeoutMs   The election timeout in milliseconds.
     * @param heartbeatIntervalMs The heartbeat interval in milliseconds.
     * @param clusterSize         Fallback cluster size when no peer supplier is given.
     * @param transport           The transport for cluster RPCs, or {@code null} for in-memory operation.
     * @param peerSupplier        Supplies the current peer IDs, or {@code null} to rely on {@code clusterSize}.
     * @param metadataStore       The store for {@code currentTerm} and {@code votedFor}, or {@code null}
     *                            to keep the metadata in memory only.
     */
    public RaftConsensus(String nodeId, long electionTimeoutMs, long heartbeatIntervalMs, int clusterSize,
                         RaftTransport transport, Supplier<List<String>> peerSupplier,
                         RaftMetadataStore metadataStore) {
        this(nodeId, electionTimeoutMs, heartbeatIntervalMs, clusterSize, transport, peerSupplier, metadataStore, null);
    }

    /**
     * Full configuration with transport, peer discovery, durable election
     * metadata, and a durable replicated log. On construction the persisted
     * {@code currentTerm}/{@code votedFor} are restored and the {@link RaftLog}
     * replays its WAL, so a restarted node resumes with the same election state
     * and log prefix. A corrupt metadata file fails startup fast (unchecked).
     *
     * @param nodeId              The unique identifier for this node.
     * @param electionTimeoutMs   The election timeout in milliseconds.
     * @param heartbeatIntervalMs The heartbeat interval in milliseconds.
     * @param clusterSize         Fallback cluster size when no peer supplier is given.
     * @param transport           The transport for cluster RPCs, or {@code null} for in-memory operation.
     * @param peerSupplier        Supplies the current peer IDs, or {@code null} to rely on {@code clusterSize}.
     * @param metadataStore       The store for {@code currentTerm} and {@code votedFor}, or {@code null}
     *                            to keep the metadata in memory only.
     * @param log                 The replicated log, or {@code null} for a memory-only log.
     */
    public RaftConsensus(String nodeId, long electionTimeoutMs, long heartbeatIntervalMs, int clusterSize,
                         RaftTransport transport, Supplier<List<String>> peerSupplier,
                         RaftMetadataStore metadataStore, RaftLog log) {
        this(nodeId, electionTimeoutMs, heartbeatIntervalMs, clusterSize, transport, peerSupplier, metadataStore, log,
                null, null);
    }

    /**
     * Full configuration with transport, peer discovery, durable election
     * metadata, a durable replicated log, and an optional state machine that
     * consumes committed entries.
     *
     * <p>Every time {@code commitIndex} advances (on the leader after a quorum,
     * on followers up to {@code leaderCommit}), the newly committed range
     * {@code [lastApplied+1 .. commitIndex]} is applied to the state machine in
     * index order. When an {@link RaftAppliedStore} is provided, the watermark
     * is persisted after each applied batch and the deterministic committed
     * prefix is re-applied on construction, so a restarted node resumes with
     * the same state without waiting for the next commit. Either may be
     * {@code null} to keep the pre-existing apply-free behavior.
     *
     * @param nodeId              The unique identifier for this node.
     * @param electionTimeoutMs   The election timeout in milliseconds.
     * @param heartbeatIntervalMs The heartbeat interval in milliseconds.
     * @param clusterSize         Fallback cluster size when no peer supplier is given.
     * @param transport           The transport for cluster RPCs, or {@code null} for in-memory operation.
     * @param peerSupplier        Supplies the current peer IDs, or {@code null} to rely on {@code clusterSize}.
     * @param metadataStore       The store for {@code currentTerm} and {@code votedFor}, or {@code null}
     *                            to keep the metadata in memory only.
     * @param log                 The replicated log, or {@code null} for a memory-only log.
     * @param stateMachine        Consumer of committed entries, or {@code null} for none.
     * @param appliedStore        Store for the apply watermark, or {@code null} for none.
     */
    public RaftConsensus(String nodeId, long electionTimeoutMs, long heartbeatIntervalMs, int clusterSize,
                         RaftTransport transport, Supplier<List<String>> peerSupplier,
                         RaftMetadataStore metadataStore, RaftLog log,
                         StateMachine stateMachine, RaftAppliedStore appliedStore) {
        this(nodeId, electionTimeoutMs, heartbeatIntervalMs, clusterSize, transport, peerSupplier, metadataStore, log,
                stateMachine, appliedStore, null, 0, null);
    }

    /**
     * Full configuration with transport, peer discovery, durable election
     * metadata, a durable replicated log, an optional state machine, and
     * periodic log compaction via state-machine snapshots.
     *
     * <p>Every {@code snapshotInterval} committed entries, the consensus layer
     * captures the state machine's state into the {@link RaftSnapshotStore}
     * and compacts the log prefix through {@link RaftLog#compact}, so the log
     * and restart cost stay bounded. On construction the latest snapshot is
     * restored and only the tail above it is re-applied. A lagging follower
     * whose next index falls below the leader's first retained index receives
     * the snapshot over the {@link RaftTransport} InstallSnapshot RPC. Any of
     * the state-machine-related parameters may be {@code null} to keep the
     * pre-existing behavior.
     *
     * @param nodeId              The unique identifier for this node.
     * @param electionTimeoutMs   The election timeout in milliseconds.
     * @param heartbeatIntervalMs The heartbeat interval in milliseconds.
     * @param clusterSize         Fallback cluster size when no peer supplier is given.
     * @param transport           The transport for cluster RPCs, or {@code null} for in-memory operation.
     * @param peerSupplier        Supplies the current peer IDs, or {@code null} to rely on {@code clusterSize}.
     * @param metadataStore       The store for {@code currentTerm} and {@code votedFor}, or {@code null}
     *                            to keep the metadata in memory only.
     * @param log                 The replicated log, or {@code null} for a memory-only log.
     * @param stateMachine        Consumer of committed entries, or {@code null} for none.
     * @param appliedStore        Store for the apply watermark, or {@code null} for none.
     * @param snapshotStore       Store for state-machine snapshots, or {@code null} to disable compaction.
     * @param snapshotInterval    Entries between snapshots; ignored when
     *                            {@code snapshotStore} is {@code null}.
     */
    public RaftConsensus(String nodeId, long electionTimeoutMs, long heartbeatIntervalMs, int clusterSize,
                         RaftTransport transport, Supplier<List<String>> peerSupplier,
                         RaftMetadataStore metadataStore, RaftLog log,
                         StateMachine stateMachine, RaftAppliedStore appliedStore,
                         RaftSnapshotStore snapshotStore, int snapshotInterval) {
        this(nodeId, electionTimeoutMs, heartbeatIntervalMs, clusterSize, transport, peerSupplier, metadataStore, log,
                stateMachine, appliedStore, snapshotStore, snapshotInterval, null);
    }

    /**
     * Full configuration with transport, peer discovery, durable election
     * metadata, a durable replicated log, an optional state machine, periodic
     * log compaction via state-machine snapshots, and a durable committed
     * configuration for membership reconfiguration.
     *
     * <p>Every {@code snapshotInterval} committed entries, the consensus layer
     * captures the state machine's state into the {@link RaftSnapshotStore}
     * and compacts the log prefix through {@link RaftLog#compact}, so the log
     * and restart cost stay bounded. On construction the latest snapshot is
     * restored and only the tail above it is re-applied. A lagging follower
     * whose next index falls below the leader's first retained index receives
     * the snapshot over the {@link RaftTransport} InstallSnapshot RPC.
     *
     * <p>On construction the committed configuration is restored from the
     * {@link RaftConfigurationStore}, so a restarted node knows its cluster
     * before gossip converges. Until a configuration is established (see
     * {@link #initializeConfig(List)} or a committed config-change entry), the
     * consensus runs in bootstrap mode and derives its peers and quorum from
     * the peer supplier / cluster size, byte-for-byte as before. Any of the
     * state-machine-related parameters may be {@code null} to keep the
     * pre-existing behavior.
     *
     * @param nodeId              The unique identifier for this node.
     * @param electionTimeoutMs   The election timeout in milliseconds.
     * @param heartbeatIntervalMs The heartbeat interval in milliseconds.
     * @param clusterSize         Fallback cluster size when no peer supplier is given.
     * @param transport           The transport for cluster RPCs, or {@code null} for in-memory operation.
     * @param peerSupplier        Supplies the current peer IDs, or {@code null} to rely on {@code clusterSize}.
     * @param metadataStore       The store for {@code currentTerm} and {@code votedFor}, or {@code null}
     *                            to keep the metadata in memory only.
     * @param log                 The replicated log, or {@code null} for a memory-only log.
     * @param stateMachine        Consumer of committed entries, or {@code null} for none.
     * @param appliedStore        Store for the apply watermark, or {@code null} for none.
     * @param snapshotStore       Store for state-machine snapshots, or {@code null} to disable compaction.
     * @param snapshotInterval    Entries between snapshots; ignored when
     *                            {@code snapshotStore} is {@code null}.
     * @param configStore         Store for the committed configuration, or {@code null}
     *                            to keep it in memory only.
     */
    public RaftConsensus(String nodeId, long electionTimeoutMs, long heartbeatIntervalMs, int clusterSize,
                         RaftTransport transport, Supplier<List<String>> peerSupplier,
                         RaftMetadataStore metadataStore, RaftLog log,
                         StateMachine stateMachine, RaftAppliedStore appliedStore,
                         RaftSnapshotStore snapshotStore, int snapshotInterval,
                         RaftConfigurationStore configStore) {
        this.nodeId = nodeId;
        this.state = RaftState.FOLLOWER;
        this.currentLeader = null;
        this.votesReceived = 0;
        this.commitIndex = 0;
        this.lastApplied = 0;
        this.lastSnapshotIndex = 0;
        this.electionTimeoutMs = electionTimeoutMs;
        this.heartbeatIntervalMs = heartbeatIntervalMs;
        this.clusterSize = clusterSize;
        this.transport = transport;
        this.peerSupplier = peerSupplier;
        this.metadataStore = metadataStore == null ? RaftMetadataStore.inMemory() : metadataStore;
        this.log = log == null ? RaftLog.inMemory() : log;
        this.stateMachine = stateMachine;
        this.appliedStore = appliedStore;
        this.snapshotStore = snapshotStore;
        this.snapshotInterval = snapshotInterval;
        this.configStore = configStore == null ? RaftConfigurationStore.inMemory() : configStore;
        try {
            this.committedConfig = this.configStore.load();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load raft configuration; refusing to start", e);
        }
        this.configEstablished = !this.committedConfig.isEmpty();
        RaftMetadata restored = restoreMetadata();
        this.currentTerm = restored.currentTerm();
        this.votedFor = restored.votedFor();
        restoreAppliedState();
    }

    /**
     * Starts the Raft node. Begins as a follower.
     */
    public void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Raft-" + nodeId);
            t.setDaemon(true);
            return t;
        });
        scheduleElectionTimeout();
    }

    /**
     * Stops the Raft node.
     */
    public void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    /**
     * Handles a heartbeat from the current leader. Resets the election timeout.
     * Log replication fields are kept empty, so this matches the empty-log
     * behavior of the pre-replication protocol.
     */
    public synchronized void receiveHeartbeat(String leaderId, int term) {
        receiveAppendEntries(leaderId, term, log.lastIndex(), log.lastTerm(), List.of(), commitIndex);
    }

    /**
     * Handles a vote request from a candidate whose log position is unknown.
     * The candidate is treated as having an empty log (index 0, term 0), which
     * is indistinguishable from the pre-replication protocol.
     *
     * @return true if a vote is granted.
     */
    public synchronized boolean receiveVoteRequest(String candidateId, int term) {
        return receiveVoteRequest(candidateId, term, 0, 0);
    }

    /**
     * Handles a vote request from a candidate. The vote is granted only if the
     * candidate's log is at least as up to date as this node's, so a node with
     * a longer or more recent log cannot be out-voted by a stale candidate.
     *
     * @param candidateId     The candidate's node ID.
     * @param term            The candidate's term.
     * @param lastLogIndex    The candidate's last log index (0 for an empty log).
     * @param lastLogTerm     The candidate's last log term (0 for an empty log).
     * @return true if a vote is granted.
     */
    public synchronized boolean receiveVoteRequest(String candidateId, int term, int lastLogIndex, int lastLogTerm) {
        if (term > currentTerm) {
            currentTerm = term;
            currentLeader = null;
            state = RaftState.FOLLOWER;
            votesReceived = 0;
            if (configEstablished && !committedConfig.contains(candidateId)) {
                // The term still advances, but a removed or not-yet-added
                // server is never granted a vote: it is not a member, so it
                // could never win.
                votedFor = null;
                persistMetadata();
                scheduleElectionTimeout();
                return false;
            }
            boolean upToDate = isCandidateLogUpToDate(lastLogIndex, lastLogTerm);
            votedFor = upToDate ? candidateId : null;
            persistMetadata();
            scheduleElectionTimeout();
            return upToDate;
        }
        if (configEstablished && !committedConfig.contains(candidateId)) {
            return false;
        }
        if (term == currentTerm
                && (votedFor == null || votedFor.equals(candidateId))
                && isCandidateLogUpToDate(lastLogIndex, lastLogTerm)) {
            votedFor = candidateId;
            persistMetadata();
            scheduleElectionTimeout();
            return true;
        }
        return false;
    }

    /**
     * Handles an AppendEntries RPC from a leader. Applies the Raft log
     * consistency check, appends new entries, truncates any conflicting tail,
     * and advances {@code commitIndex} up to the leader's. A higher term
     * reverts this node to follower and persists the term before the reply.
     *
     * @param leaderId      The leader's node ID.
     * @param term          The leader's current term.
     * @param prevLogIndex  The index preceding the first new entry.
     * @param prevLogTerm   The term at {@code prevLogIndex}.
     * @param entries       New entry frames ({@code [4-byte term][payload]}).
     * @param leaderCommit  The leader's commit index.
     * @return true if the log matched and the entries were appended.
     */
    public synchronized boolean receiveAppendEntries(String leaderId, int term, int prevLogIndex, int prevLogTerm,
                                                     List<byte[]> entries, int leaderCommit) {
        return receiveAppendEntries(leaderId, term, prevLogIndex, prevLogTerm, entries, leaderCommit, null);
    }

    /**
     * Handles an AppendEntries RPC from a leader. A higher term reverts this
     * node to follower and persists the term before the reply. When the
     * leader's log matches the consistency prefix, the follower appends or
     * truncates the returned suffix and advances its commit index to the
     * leader's committed watermark.
     *
     * <p>When the leader carries a committed configuration (a node joining
     * through log replication, where its bootstrap config was never logged),
     * a follower that has no configuration yet adopts it: the leader's
     * committed config is derived from committed log entries, and a
     * bootstrapping node learns the member set it joined just as it would
     * through InstallSnapshot. Nodes with a config already established never
     * adopt from an RPC; for them the config comes from committed entries.
     *
     * @param leaderId          The leader's node ID.
     * @param term              The leader's current term.
     * @param prevLogIndex      The index of the entry immediately preceding
     *                          the first {@code entry}.
     * @param prevLogTerm       The term at {@code prevLogIndex}.
     * @param entries           The entries to append after the prefix.
     * @param leaderCommit      The leader's committed index.
     * @param config            The leader's committed configuration, or
     *                          {@code null}/{@code empty} when none is carried.
     * @return true if the consistency prefix matched and the entries were accepted.
     */
    public synchronized boolean receiveAppendEntries(String leaderId, int term, int prevLogIndex, int prevLogTerm,
                                                     List<byte[]> entries, int leaderCommit, List<String> config) {
        if (term < currentTerm) {
            return false;
        }
        boolean termChanged = term > currentTerm;
        currentTerm = term;
        currentLeader = leaderId;
        state = RaftState.FOLLOWER;
        votesReceived = 0;
        if (termChanged) {
            persistMetadata();
            failPendingBarriers();
        }
        if (prevLogIndex > log.lastIndex() || log.termAt(prevLogIndex) != prevLogTerm) {
            scheduleElectionTimeout();
            return false;
        }
        if (!configEstablished && config != null && !config.isEmpty()) {
            ClusterConfiguration adopted = ClusterConfiguration.of(config);
            if (!adopted.isEmpty()) {
                committedConfig = adopted;
                configEstablished = true;
                persistConfig();
            }
        }
        int index = prevLogIndex + 1;
        for (byte[] frame : entries) {
            int entryTerm = RaftLog.termFromFrame(frame);
            if (index <= log.lastIndex()) {
                if (log.termAt(index) == entryTerm) {
                    index++;
                    continue;
                }
                log.truncateFrom(index);
            }
            log.append(entryTerm, RaftLog.payloadFromFrame(frame));
            index++;
        }
        if (leaderCommit > commitIndex) {
            commitIndex = Math.min(leaderCommit, log.lastIndex());
            applyCommitted(commitIndex);
        }
        scheduleElectionTimeout();
        return true;
    }

    /**
     * Starts an election by transitioning to candidate state and
     * requesting votes from every known peer over the transport.
     */
    public synchronized void startElection() {
        if (state == RaftState.LEADER) {
            return;
        }
        if (configEstablished && !committedConfig.contains(nodeId)) {
            // A node removed by a committed config change no longer campaigns;
            // it is not a member, so it could never win.
            return;
        }
        state = RaftState.CANDIDATE;
        currentTerm++;
        votesReceived = 1; // Vote for self
        votedFor = nodeId;
        currentLeader = null;
        persistMetadata();
        requestVotesFromPeers();
        scheduleElectionTimeout();
    }

    /**
     * Records a vote received from another node.
     *
     * @return true if this node has won the election (majority reached).
     */
    public synchronized boolean receiveVote() {
        votesReceived++;
        return votesReceived >= majorityThreshold();
    }

    /**
     * Transitions this node to leader state and initializes replication state.
     * The first AppendEntries round is driven by {@link #appendEntry} or the
     * scheduled heartbeat loop.
     */
    public synchronized void becomeLeader() {
        state = RaftState.LEADER;
        currentLeader = nodeId;
        int lastIndex = log.lastIndex();
        for (String peer : peers()) {
            nextIndex.put(peer, lastIndex + 1);
            matchIndex.put(peer, 0);
        }
        scheduleHeartbeats();
    }

    /**
     * Appends a payload to the leader's log in the current term and pushes it
     * to every follower. Only the leader may append.
     *
     * @param payload The opaque payload.
     * @return The new entry's 1-based index.
     * @throws IllegalStateException If this node is not the leader.
     */
    public synchronized int appendEntry(byte[] payload) {
        if (state != RaftState.LEADER) {
            throw new IllegalStateException("Only the leader may append to the Raft log");
        }
        int index = log.append(currentTerm, payload);
        if (majorityThreshold() <= 1) {
            commitIndex = index;
            applyCommitted(index);
        }
        sendHeartbeats();
        return index;
    }

    /**
     * Establishes the bootstrap configuration: the member set the cluster
     * starts with (self plus the seed peers). Persisted so a restart restores
     * it before gossip converges. Allowed only once, before any config change
     * has been committed; afterwards membership is driven exclusively by
     * config-change entries.
     *
     * <p>Until this is called (or a config-change entry commits) the consensus
     * runs in bootstrap mode and derives its peers and quorum from the peer
     * supplier / cluster size, byte-for-byte as before.
     *
     * @param members The initial members; must include this node.
     * @throws IllegalStateException If a configuration is already established.
     * @throws IllegalArgumentException If {@code members} is empty.
     */
    public synchronized void initializeConfig(List<String> members) {
        if (configEstablished) {
            throw new IllegalStateException("Raft configuration already established");
        }
        ClusterConfiguration config = ClusterConfiguration.of(members);
        if (config.isEmpty()) {
            throw new IllegalArgumentException("Initial configuration must contain at least one member");
        }
        committedConfig = config;
        configEstablished = true;
        persistConfig();
    }

    /**
     * Adds or removes one server through the replicated log. The change is
     * appended like any other entry, replicated, and takes effect when it
     * commits. Only one change may be in flight at a time (one-server-at-a-time),
     * which keeps every old-config and new-config quorum intersecting so the
     * Raft safety invariant holds without joint consensus.
     *
     * <p>From the moment the entry is appended, the effective majority is the
     * larger of the old and new configs' majorities, and replication covers the
     * union of the two member sets, so the joining server catches up and the
     * leaving server is still heard until the change commits.
     *
     * @param change The membership change to append.
     * @return The new entry's 1-based index.
     * @throws IllegalStateException If this node is not the leader, no
     *         configuration is established, or another change is pending.
     */
    public synchronized int appendConfigChange(ConfigChange change) {
        if (state != RaftState.LEADER) {
            throw new IllegalStateException("Only the leader may append to the Raft log");
        }
        if (!configEstablished) {
            throw new IllegalStateException("No Raft configuration established; call initializeConfig first");
        }
        if (hasUncommittedConfigChange()) {
            throw new IllegalStateException("A config change is already pending; one at a time");
        }
        int index = log.append(currentTerm, change.encode());
        if (majorityThreshold() <= 1) {
            commitIndex = index;
            applyCommitted(index);
        }
        sendHeartbeats();
        return index;
    }

    /**
     * Applies a committed config-change entry: updates the committed
     * configuration, persists it, and clears the one-at-a-time window. A leader
     * removed by the new configuration steps down (keeping its term, so the
     * remaining members can re-elect). The change is consumed by the consensus
     * layer and never forwarded to the state machine.
     */
    private void applyConfigChange(ConfigChange change) {
        if (state == RaftState.LEADER && change.type() == ConfigChange.ChangeType.REMOVE
                && !change.nodeId().equals(nodeId)) {
            // The removed server must learn the new commit index before the
            // leader drops it from replication, or it would never apply the
            // removal and keep counting itself as a member. A single final
            // round carries the entry (if missing) and the commit watermark.
            sendAppendEntries(change.nodeId());
        }
        ClusterConfiguration target = change.type() == ConfigChange.ChangeType.ADD
                ? committedConfig.plus(change.nodeId())
                : committedConfig.minus(change.nodeId());
        committedConfig = target;
        configEstablished = true;
        persistConfig();
        if (state == RaftState.LEADER && !committedConfig.contains(nodeId)) {
            // The removal has committed on this leader. Propagate the new
            // commit index to the remaining members before stepping down, so
            // they apply the removal themselves instead of waiting for the
            // next leader's current-term entry to commit it indirectly.
            sendHeartbeats();
            stepDown(currentTerm);
        }
    }

    /**
     * @return The target configuration after any uncommitted config-change
     *         entries in {@code [commitIndex+1 .. lastIndex]} are applied to
     *         the committed configuration.
     */
    private ClusterConfiguration targetConfig() {
        ClusterConfiguration current = committedConfig;
        for (int i = commitIndex + 1; i <= log.lastIndex(); i++) {
            byte[] payload = log.payloadAt(i);
            if (payload != null && ConfigChange.isConfigFrame(payload)) {
                ConfigChange change = ConfigChange.decode(payload);
                current = change.type() == ConfigChange.ChangeType.ADD
                        ? current.plus(change.nodeId())
                        : current.minus(change.nodeId());
            }
        }
        return current;
    }

    /**
     * @return Whether any config-change entry is currently uncommitted.
     */
    private boolean hasUncommittedConfigChange() {
        for (int i = commitIndex + 1; i <= log.lastIndex(); i++) {
            byte[] payload = log.payloadAt(i);
            if (payload != null && ConfigChange.isConfigFrame(payload)) {
                return true;
            }
        }
        return false;
    }

    private void persistConfig() {
        try {
            configStore.persist(committedConfig);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to persist raft configuration", e);
        }
    }

    /**
     * @return The committed configuration, or the empty configuration before
     *         it is established.
     */
    public ClusterConfiguration getCommittedConfig() {
        return committedConfig;
    }

    /**
     * @return Whether a configuration has been established (via
     *         {@link #initializeConfig(List)} or a committed config-change
     *         entry). Until true, the consensus runs in bootstrap mode.
     */
    public boolean getConfigEstablished() {
        return configEstablished;
    }

    /**
     * Sends AppendEntries RPCs to all followers (called by the leader).
     */
    public synchronized void sendHeartbeats() {
        if (state != RaftState.LEADER || transport == null) {
            return;
        }
        for (String peer : peers()) {
            sendAppendEntries(peer);
        }
        scheduleHeartbeats();
    }

    public String getNodeId() { return nodeId; }
    public RaftState getState() { return state; }
    public String getCurrentLeader() { return currentLeader; }
    public String getVotedFor() { return votedFor; }
    public int getCurrentTerm() { return currentTerm; }
    public int getCommitIndex() { return commitIndex; }
    public int getLastLogIndex() { return log.lastIndex(); }
    public int getLastLogTerm() { return log.lastTerm(); }
    public int getLogFirstIndex() { return log.firstIndex(); }
    public int getLastApplied() { return lastApplied; }

    /**
     * Establishes a linearizable read barrier: confirms this node is still the
     * leader for its term by requiring a fresh quorum of AppendEntries acks
     * before returning, so a partitioned leader cannot serve stale reads.
     *
     * <p>A single-node cluster (no transport) trivially satisfies the barrier.
     * A node that is not the leader, or a leader that cannot gather a quorum
     * before the timeout, returns {@code false}.
     *
     * @return true when it is safe to serve a linearizable read.
     */
    public boolean prepareReadBarrier() {
        List<String> targets;
        int roundTerm;
        int prevLogIndex;
        int prevLogTerm;
        int snapshotCommit;
        synchronized (this) {
            if (state != RaftState.LEADER) {
                return false;
            }
            if (transport == null || majorityThreshold() <= 1) {
                return true;
            }
            roundTerm = currentTerm;
            prevLogIndex = log.lastIndex();
            prevLogTerm = log.lastTerm();
            snapshotCommit = commitIndex;
            targets = peers();
        }

        int round;
        CompletableFuture<Boolean> future;
        synchronized (barrierLock) {
            round = ++barrierRound;
            future = new CompletableFuture<>();
            barrierResponders.put(round, new HashSet<>());
            barrierFutures.put(round, future);
        }

        for (String peer : targets) {
            AppendEntriesRequest req = new AppendEntriesRequest(
                    ClusterProtocol.PROTOCOL_VERSION,
                    ClusterProtocol.newId(),
                    ClusterProtocol.newId(),
                    nodeId,
                    ClusterProtocol.now(),
                    nodeId,
                    roundTerm,
                    prevLogIndex,
                    prevLogTerm,
                    List.of(),
                    snapshotCommit
            );
            transport.sendAppendEntries(peer, req)
                    .whenComplete((resp, err) -> onBarrierResponse(round, peer, resp, err));
        }

        try {
            return future.get(Math.max(READ_BARRIER_MIN_TIMEOUT_MS, heartbeatIntervalMs * 2), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException | TimeoutException e) {
            return false;
        } finally {
            synchronized (barrierLock) {
                barrierResponders.remove(round);
                barrierFutures.remove(round);
            }
        }
    }

    /**
     * Counts a barrier round's AppendEntries response. A response is credited
     * only when it succeeds in the round's term; a higher term invalidates the
     * round (this leader is stale). The round completes once self plus the
     * credited peers form a strict majority.
     */
    private void onBarrierResponse(int round, String peer, AppendEntriesResponse resp, Throwable err) {
        if (err != null) {
            return;
        }
        synchronized (barrierLock) {
            CompletableFuture<Boolean> future = barrierFutures.get(round);
            Set<String> responders = barrierResponders.get(round);
            if (future == null || responders == null || future.isDone()) {
                return;
            }
            if (resp.term() > currentTerm) {
                future.complete(false);
                return;
            }
            if (resp.success()) {
                responders.add(peer);
            }
            if (responders.size() + 1 >= majorityThreshold()) {
                future.complete(true);
            }
        }
    }

    /**
     * Sends a RequestVote RPC to every peer for the current election term,
     * including this candidate's last log index and term so followers can
     * apply the Raft up-to-date check.
     */
    private void requestVotesFromPeers() {
        if (transport == null) {
            return;
        }
        int term = currentTerm;
        for (String peer : peers()) {
            RequestVoteRequest req = new RequestVoteRequest(
                    ClusterProtocol.PROTOCOL_VERSION,
                    ClusterProtocol.newId(),
                    ClusterProtocol.newId(),
                    nodeId,
                    ClusterProtocol.now(),
                    nodeId,
                    term,
                    log.lastIndex(),
                    log.lastTerm()
            );
            transport.sendRequestVote(peer, req)
                    .whenComplete((resp, err) -> onVoteResponse(resp, err, term));
        }
    }

    /**
     * Counts a granted vote. A higher term observed in the response wins the
     * election away from this candidate.
     */
    private void onVoteResponse(RequestVoteResponse resp, Throwable err, int termAtSend) {
        if (err != null) {
            return;
        }
        synchronized (this) {
            if (state != RaftState.CANDIDATE) {
                return;
            }
            if (resp.term() > currentTerm) {
                stepDown(resp.term());
                return;
            }
            if (resp.voteGranted() && resp.term() == termAtSend) {
                votesReceived++;
                if (votesReceived >= majorityThreshold()) {
                    becomeLeader();
                }
            }
        }
    }

    /**
     * Handles an AppendEntries response. A higher term steps the leader down.
     * A success raises the peer's match index and advances the commit index;
     * a failure backs off the next index so the leader re-sends a shorter
     * suffix on the next round.
     */
    private void onAppendEntriesResponse(String peer, int sentPrevLogIndex, int sentEntryCount,
                                         AppendEntriesResponse resp, Throwable err) {
        if (err != null) {
            return;
        }
        synchronized (this) {
            if (resp.term() > currentTerm) {
                stepDown(resp.term());
                return;
            }
            if (state != RaftState.LEADER) {
                return;
            }
            if (resp.success()) {
                int matched = sentPrevLogIndex + sentEntryCount;
                matchIndex.put(peer, Math.max(matchIndex.getOrDefault(peer, 0), matched));
                nextIndex.put(peer, matchIndex.get(peer) + 1);
                advanceCommit();
            } else {
                int current = nextIndex.getOrDefault(peer, log.lastIndex() + 1);
                nextIndex.put(peer, Math.max(1, current - 1));
            }
        }
    }

    /**
     * Steps down to follower if a response reports a higher term.
     */
    private void stepDown(int term) {
        currentTerm = term;
        state = RaftState.FOLLOWER;
        currentLeader = null;
        votesReceived = 0;
        votedFor = null;
        persistMetadata();
        failPendingBarriers();
        scheduleElectionTimeout();
    }

    /**
     * Aborts every in-flight read barrier as failed. Invoked whenever this node
     * loses leadership (term increase), so a stale leader never serves a read.
     */
    private void failPendingBarriers() {
        synchronized (barrierLock) {
            for (CompletableFuture<Boolean> future : barrierFutures.values()) {
                future.complete(false);
            }
            barrierFutures.clear();
            barrierResponders.clear();
        }
    }

    /**
     * Advances {@code commitIndex} to the highest index whose entry is in the
     * current term and is replicated on a strict majority of the cluster.
     * Only current-term entries are committed, so a leader cannot commit an
     * entry from a previous term that a future leader might overwrite.
     */
    private void advanceCommit() {
        int threshold = majorityThreshold();
        for (int n = log.lastIndex(); n > commitIndex; n--) {
            if (log.termAt(n) == currentTerm && countMatches(n) >= threshold) {
                commitIndex = n;
                applyCommitted(n);
                return;
            }
        }
    }

    /**
     * Applies the newly committed range {@code [lastApplied+1 .. newCommitIndex]}
     * in index order: config-change entries are consumed by the consensus layer,
     * everything else is applied to the state machine. Then records the
     * watermark and offers the opportunity to snapshot/compact. Runs under the
     * consensus lock, so a write ack (which happens only after the leader's own
     * apply) guarantees the effect is visible to subsequent reads.
     */
    private void applyCommitted(int newCommitIndex) {
        if (stateMachine == null && !configEstablished) {
            // Phase 3 behavior: without a state machine there is nothing to
            // apply, so the watermark stays put. Once a configuration is
            // established, config-change entries must still be consumed even
            // when no KV state machine is present.
            return;
        }
        for (int i = lastApplied + 1; i <= newCommitIndex; i++) {
            applyEntry(i);
        }
        lastApplied = newCommitIndex;
        if (appliedStore != null) {
            try {
                appliedStore.persist(lastApplied);
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to persist raft applied index", e);
            }
        }
        maybeSnapshot();
    }

    /**
     * Applies one committed entry. A config-change frame updates the committed
     * configuration and is never forwarded to the state machine; any other
     * frame is applied to the state machine when one is configured.
     */
    private void applyEntry(int index) {
        byte[] payload = log.payloadAt(index);
        if (payload != null && ConfigChange.isConfigFrame(payload)) {
            applyConfigChange(ConfigChange.decode(payload));
            return;
        }
        if (stateMachine != null) {
            stateMachine.apply(logEntryAt(index));
        }
    }

    /**
     * Periodically captures the applied state into a durable snapshot and
     * compacts the log prefix. The snapshot is written first, so a crash at any
     * point loses only the uncommitted tail: everything at or below the
     * snapshot is covered by the durable snapshot, and the tail entries being
     * rewritten are uncommitted (the applied watermark is at the snapshot
     * index at capture time).
     */
    private void maybeSnapshot() {
        if (snapshotStore == null || stateMachine == null || !stateMachine.isSnapshotable()) {
            return;
        }
        if (lastApplied - lastSnapshotIndex < snapshotInterval) {
            return;
        }
        int term = log.termAt(lastApplied);
        RaftSnapshot snapshot = new RaftSnapshot(lastApplied, term, stateMachine.snapshot(), committedConfig);
        try {
            snapshotStore.save(snapshot);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to persist raft snapshot", e);
        }
        lastSnapshotIndex = lastApplied;
        log.compact(lastApplied, term);
    }

    /**
     * Rebuilds the state machine on construction. The latest durable snapshot
     * (if any) is restored and the log is re-based at its last included index,
     * so a compacted WAL tail replays at the correct absolute indexes; then
     * the deterministic tail {@code [snapshotIndex+1 .. lastApplied]} is
     * re-applied. The watermark is floored at the snapshot index so a missing
     * or stale {@code raft-applied.bin} can never leave the state machine
     * behind its snapshot. Config-change entries in the replay reconcile the
     * committed configuration with the log (the store is the fast path; the
     * replay is idempotent).
     */
    private void restoreAppliedState() {
        int snapshotIndex = 0;
        if (snapshotStore != null) {
            RaftSnapshot snapshot;
            try {
                snapshot = snapshotStore.load();
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to load raft snapshot", e);
            }
            if (snapshot != null) {
                log.compact(snapshot.lastIncludedIndex(), snapshot.lastIncludedTerm());
                lastSnapshotIndex = snapshot.lastIncludedIndex();
                snapshotIndex = snapshot.lastIncludedIndex();
                if (!snapshot.config().isEmpty()) {
                    // The snapshot carries the committed configuration at its
                    // last included index; the tail replay below refines it with
                    // any config-change entries above the snapshot.
                    committedConfig = snapshot.config();
                    configEstablished = true;
                    persistConfig();
                }
                if (stateMachine != null) {
                    stateMachine.restore(snapshot.data());
                }
            }
        }
        if (appliedStore == null && snapshotStore == null) {
            return;
        }
        int loaded = 0;
        if (appliedStore != null) {
            try {
                loaded = appliedStore.load();
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to load raft applied index", e);
            }
        }
        lastApplied = Math.max(loaded, snapshotIndex);
        for (int i = snapshotIndex + 1; i <= lastApplied; i++) {
            applyEntry(i);
        }
    }

    private LogEntry logEntryAt(int index) {
        return new LogEntry(index, log.termAt(index), log.payloadAt(index));
    }

    /**
     * @return How many cluster members have a log at least as long as
     *         {@code index} (self always counts).
     */
    private int countMatches(int index) {
        int count = 1; // self
        for (int matched : matchIndex.values()) {
            if (matched >= index) {
                count++;
            }
        }
        return count;
    }

    /**
     * Raft's up-to-date rule: the candidate's log is up to date if its last
     * entry has a higher term, or the same term and an index at least as high.
     */
    private boolean isCandidateLogUpToDate(int candidateLastIndex, int candidateLastTerm) {
        int lastTerm = log.lastTerm();
        if (candidateLastTerm != lastTerm) {
            return candidateLastTerm > lastTerm;
        }
        return candidateLastIndex >= log.lastIndex();
    }

    /**
     * Sends an AppendEntries RPC for one follower, starting at the follower's
     * next expected index. When that index has been consumed by a snapshot, the
     * follower is too far behind for append-style replication and receives the
     * snapshot via {@link #sendInstallSnapshot(String)} instead.
     */
    private void sendAppendEntries(String peer) {
        int next = nextIndex.getOrDefault(peer, log.lastIndex() + 1);
        if (next < log.firstIndex()) {
            sendInstallSnapshot(peer);
            return;
        }
        int prevLogIndex = next - 1;
        int prevLogTerm = log.termAt(prevLogIndex);
        List<byte[]> entries = log.entriesFrom(next, MAX_ENTRIES_PER_REQUEST);
        int sentPrevLogIndex = prevLogIndex;
        int sentEntryCount = entries.size();
        List<String> config = configEstablished ? new ArrayList<>(committedConfig.members()) : List.of();
        AppendEntriesRequest req = new AppendEntriesRequest(
                ClusterProtocol.PROTOCOL_VERSION,
                ClusterProtocol.newId(),
                ClusterProtocol.newId(),
                nodeId,
                ClusterProtocol.now(),
                nodeId,
                currentTerm,
                prevLogIndex,
                prevLogTerm,
                entries,
                commitIndex,
                config
        );
        transport.sendAppendEntries(peer, req)
                .whenComplete((resp, err) -> onAppendEntriesResponse(peer, sentPrevLogIndex, sentEntryCount, resp, err));
    }

    /**
     * Sends the leader's durable snapshot to a follower that has fallen below
     * the leader's first retained index. A missing snapshot is a defensive
     * no-op (compaction only ever happens after a snapshot is saved).
     */
    private void sendInstallSnapshot(String peer) {
        if (snapshotStore == null) {
            return;
        }
        RaftSnapshot snapshot;
        try {
            snapshot = snapshotStore.load();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load raft snapshot for install", e);
        }
        if (snapshot == null) {
            return;
        }
        List<String> config = new ArrayList<>(snapshot.config().members());
        InstallSnapshotRequest req = new InstallSnapshotRequest(
                ClusterProtocol.PROTOCOL_VERSION,
                ClusterProtocol.newId(),
                ClusterProtocol.newId(),
                nodeId,
                ClusterProtocol.now(),
                nodeId,
                currentTerm,
                snapshot.lastIncludedIndex(),
                snapshot.lastIncludedTerm(),
                snapshot.data(),
                config
        );
        transport.sendInstallSnapshot(peer, req)
                .whenComplete((resp, err) -> onInstallSnapshotResponse(peer, snapshot.lastIncludedIndex(), resp, err));
    }

    /**
     * Handles an InstallSnapshot response. A higher term steps the leader down.
     * A success brings the follower up to the snapshot's last included index and
     * advances the commit index, since the snapshot may cover entries the
     * leader committed earlier.
     */
    private void onInstallSnapshotResponse(String peer, int lastIncludedIndex,
                                           InstallSnapshotResponse resp, Throwable err) {
        if (err != null) {
            return;
        }
        synchronized (this) {
            if (resp.term() > currentTerm) {
                stepDown(resp.term());
                return;
            }
            if (state != RaftState.LEADER || !resp.success()) {
                return;
            }
            matchIndex.put(peer, Math.max(matchIndex.getOrDefault(peer, 0), lastIncludedIndex));
            nextIndex.put(peer, matchIndex.get(peer) + 1);
            advanceCommit();
        }
    }

    /**
     * Handles an InstallSnapshot RPC from a leader without a committed
     * configuration (pre-reconfiguration senders). See the full overload.
     */
    public synchronized boolean receiveInstallSnapshot(String leaderId, int term, int lastIncludedIndex,
                                                       int lastIncludedTerm, byte[] data) {
        return receiveInstallSnapshot(leaderId, term, lastIncludedIndex, lastIncludedTerm, data, null);
    }

    /**
     * Handles an InstallSnapshot RPC from a leader. When the snapshot covers
     * entries the follower has not applied, the follower adopts it: a matching
     * local log is compacted (its tail is retained), a mismatched or shorter
     * log is replaced entirely, the state machine is restored, and the applied
     * watermark advances. A snapshot at or behind the applied watermark is
     * ignored (the local state is already at least as fresh). A higher term
     * reverts this node to follower and persists the term before the reply.
     *
     * <p>When the snapshot carries a committed configuration (v2), it is
     * adopted alongside the state machine, so a node joining through
     * InstallSnapshot learns the member set even if the config-change entries
     * were compacted away. The adopted config is also persisted into the
     * durable snapshot for the next restart.
     *
     * @param leaderId          The leader's node ID.
     * @param term              The leader's current term.
     * @param lastIncludedIndex The last log index the snapshot covers.
     * @param lastIncludedTerm  The term at {@code lastIncludedIndex}.
     * @param data              The snapshot's state-machine data.
     * @param config            The committed configuration at the snapshot, or
     *                          {@code null}/{@code empty} when none is carried.
     * @return true if the snapshot was accepted.
     */
    public synchronized boolean receiveInstallSnapshot(String leaderId, int term, int lastIncludedIndex,
                                                       int lastIncludedTerm, byte[] data, List<String> config) {
        if (term < currentTerm) {
            return false;
        }
        boolean termChanged = term > currentTerm;
        currentTerm = term;
        currentLeader = leaderId;
        state = RaftState.FOLLOWER;
        votesReceived = 0;
        if (termChanged) {
            persistMetadata();
            failPendingBarriers();
        }
        if (stateMachine != null && !stateMachine.isSnapshotable()) {
            return false;
        }
        if (lastIncludedIndex > lastApplied) {
            boolean matches = log.lastIndex() >= lastIncludedIndex
                    && log.termAt(lastIncludedIndex) == lastIncludedTerm;
            if (matches) {
                log.compact(lastIncludedIndex, lastIncludedTerm);
            } else {
                log.resetTo(lastIncludedIndex, lastIncludedTerm);
            }
            ClusterConfiguration snapshotConfig = config == null
                    ? ClusterConfiguration.EMPTY : ClusterConfiguration.of(config);
            if (stateMachine != null) {
                stateMachine.restore(data);
            }
            if (!snapshotConfig.isEmpty()) {
                committedConfig = snapshotConfig;
                configEstablished = true;
                persistConfig();
            }
            lastApplied = lastIncludedIndex;
            if (appliedStore != null) {
                try {
                    appliedStore.persist(lastApplied);
                } catch (IOException e) {
                    throw new UncheckedIOException("Failed to persist raft applied index", e);
                }
            }
            if (snapshotStore != null) {
                try {
                    snapshotStore.save(new RaftSnapshot(lastIncludedIndex, lastIncludedTerm, data, snapshotConfig));
                } catch (IOException e) {
                    throw new UncheckedIOException("Failed to persist raft snapshot", e);
                }
            }
            lastSnapshotIndex = lastIncludedIndex;
        }
        commitIndex = Math.max(commitIndex, lastIncludedIndex);
        scheduleElectionTimeout();
        return true;
    }

    /**
     * Restores the persisted {@code currentTerm} and {@code votedFor}. Fails
     * startup fast if the metadata file exists but cannot be parsed, rather
     * than silently forgetting a vote.
     */
    private RaftMetadata restoreMetadata() {
        try {
            return metadataStore.load();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load raft metadata; refusing to start", e);
        }
    }

    /**
     * Durably records the current term and vote. Invoked before any vote
     * reply or term transition is exposed. A persistence failure aborts the
     * operation: the grant has not been made durable and must not be
     * acknowledged.
     */
    private void persistMetadata() {
        try {
            metadataStore.persist(currentTerm, votedFor);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to persist raft metadata", e);
        }
    }

    /**
     * @return The peer IDs excluding this node. With a configuration
     *         established, the peers are the union of the committed and target
     *         configurations (so a joining server receives the change and a
     *         leaving server is still heard until the change commits);
     *         otherwise they are resolved from the peer supplier.
     */
    private List<String> peers() {
        if (configEstablished) {
            Set<String> members = new HashSet<>(committedConfig.members());
            members.addAll(targetConfig().members());
            members.remove(nodeId);
            return members.stream().distinct().toList();
        }
        if (peerSupplier == null) {
            return List.of();
        }
        return peerSupplier.get().stream()
                .filter(p -> p != null && !p.equals(nodeId))
                .distinct()
                .toList();
    }

    /**
     * @return The number of votes required to win an election (or commit an
     *         entry). With a configuration established, the strict majority of
     *         the committed configuration — or, while a config change is
     *         pending, the larger of the committed and target majorities, so a
     *         quorum must cover both. The configs differ by exactly one server,
     *         so any two such quorums intersect and Raft's safety invariant
     *         holds. Otherwise the legacy peer-supplier / cluster-size quorum.
     */
    private int majorityThreshold() {
        if (configEstablished) {
            int majority = committedConfig.majority();
            ClusterConfiguration target = targetConfig();
            if (!target.equals(committedConfig)) {
                majority = Math.max(majority, target.majority());
            }
            return majority;
        }
        if (peerSupplier != null) {
            Set<String> known = new HashSet<>();
            known.add(nodeId);
            for (String p : peerSupplier.get()) {
                if (p != null) {
                    known.add(p);
                }
            }
            return known.size() / 2 + 1;
        }
        return clusterSize / 2 + 1;
    }

    private void scheduleHeartbeats() {
        if (scheduler != null && !scheduler.isShutdown() && state == RaftState.LEADER) {
            scheduler.schedule(this::sendHeartbeats, heartbeatIntervalMs, TimeUnit.MILLISECONDS);
        }
    }

    private void scheduleElectionTimeout() {
        if (scheduler != null && !scheduler.isShutdown() && state != RaftState.LEADER
                && !(configEstablished && !committedConfig.contains(nodeId))) {
            ScheduledFuture<?> pending = electionTimeoutTask;
            if (pending != null) {
                pending.cancel(false);
            }
            long jitter = (long) (electionTimeoutMs * 0.5 + Math.random() * electionTimeoutMs * 0.5);
            electionTimeoutTask = scheduler.schedule(this::startElection, jitter, TimeUnit.MILLISECONDS);
        }
    }

    public enum RaftState {
        FOLLOWER,
        CANDIDATE,
        LEADER
    }
}
