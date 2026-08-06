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
import com.minigoogle.storage.metadata.RaftMetadata;
import com.minigoogle.storage.metadata.RaftMetadataStore;
import com.minigoogle.storage.metadata.RaftSnapshotStore;

import java.io.IOException;
import java.io.UncheckedIOException;
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
    private final int snapshotInterval;

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
                stateMachine, appliedStore, null, 0);
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
            boolean upToDate = isCandidateLogUpToDate(lastLogIndex, lastLogTerm);
            votedFor = upToDate ? candidateId : null;
            persistMetadata();
            scheduleElectionTimeout();
            return upToDate;
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
     * to the state machine in index order, then records the watermark and
     * offers the opportunity to snapshot/compact. Runs under the consensus
     * lock, so a write ack (which happens only after the leader's own apply)
     * guarantees the effect is visible to subsequent reads.
     */
    private void applyCommitted(int newCommitIndex) {
        if (stateMachine == null) {
            return;
        }
        for (int i = lastApplied + 1; i <= newCommitIndex; i++) {
            stateMachine.apply(logEntryAt(i));
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
        RaftSnapshot snapshot = new RaftSnapshot(lastApplied, term, stateMachine.snapshot());
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
     * behind its snapshot.
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
        if (stateMachine != null) {
            for (int i = snapshotIndex + 1; i <= lastApplied; i++) {
                stateMachine.apply(logEntryAt(i));
            }
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
                commitIndex
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
                snapshot.data()
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
     * Handles an InstallSnapshot RPC from a leader. When the snapshot covers
     * entries the follower has not applied, the follower adopts it: a matching
     * local log is compacted (its tail is retained), a mismatched or shorter
     * log is replaced entirely, the state machine is restored, and the applied
     * watermark advances. A snapshot at or behind the applied watermark is
     * ignored (the local state is already at least as fresh). A higher term
     * reverts this node to follower and persists the term before the reply.
     *
     * @param leaderId          The leader's node ID.
     * @param term              The leader's current term.
     * @param lastIncludedIndex The last log index the snapshot covers.
     * @param lastIncludedTerm  The term at {@code lastIncludedIndex}.
     * @param data              The snapshot's state-machine data.
     * @return true if the snapshot was accepted.
     */
    public synchronized boolean receiveInstallSnapshot(String leaderId, int term, int lastIncludedIndex,
                                                       int lastIncludedTerm, byte[] data) {
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
            if (stateMachine != null) {
                stateMachine.restore(data);
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
                    snapshotStore.save(new RaftSnapshot(lastIncludedIndex, lastIncludedTerm, data));
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
     * @return The peer IDs excluding this node, resolved from the peer supplier.
     */
    private List<String> peers() {
        if (peerSupplier == null) {
            return List.of();
        }
        return peerSupplier.get().stream()
                .filter(p -> p != null && !p.equals(nodeId))
                .distinct()
                .toList();
    }

    /**
     * @return The number of votes required to win an election (or commit an entry).
     */
    private int majorityThreshold() {
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
        if (scheduler != null && !scheduler.isShutdown() && state != RaftState.LEADER) {
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
