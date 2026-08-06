package com.minigoogle.cluster;

import com.minigoogle.cluster.transport.ClusterProtocol;
import com.minigoogle.cluster.transport.RaftTransport;
import com.minigoogle.cluster.transport.dto.AppendEntriesRequest;
import com.minigoogle.cluster.transport.dto.AppendEntriesResponse;
import com.minigoogle.cluster.transport.dto.RequestVoteRequest;
import com.minigoogle.cluster.transport.dto.RequestVoteResponse;
import com.minigoogle.storage.metadata.RaftMetadata;
import com.minigoogle.storage.metadata.RaftMetadataStore;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Raft consensus protocol for electing a cluster leader.
 *
 * Per ARCHITECTURE.md Ch14:
 *   Raft provides strong consistency through leader election.
 *   Nodes are in one of three states: Follower, Candidate, Leader.
 *   Heartbeats from the leader prevent new elections.
 *   If the leader fails, a candidate wins an election and becomes leader.
 *
 * This implementation covers the leader election portion of Raft.
 * When a {@link RaftTransport} is injected, elections and heartbeats cross
 * the wire: candidates send RequestVote RPCs, the leader sends AppendEntries
 * heartbeats, and any higher term observed in a response steps the node down.
 *
 * Election metadata ({@code currentTerm} and {@code votedFor}) is persisted
 * through an optional {@link RaftMetadataStore}: it is written before every
 * vote reply and every term transition, and restored on construction, so a
 * node never double-votes or regresses its term across a restart.
 */
public class RaftConsensus {

    private final String nodeId;
    private volatile RaftState state;
    private volatile String currentLeader;
    private volatile int currentTerm;
    private volatile int votesReceived;
    private volatile String votedFor;

    private final RaftTransport transport;
    private final Supplier<List<String>> peerSupplier;
    private final RaftMetadataStore metadataStore;

    // Log replication is not implemented; the position stays at 0.
    private volatile int lastLogIndex;
    private volatile int lastLogTerm;
    private volatile int commitIndex;

    private ScheduledExecutorService scheduler;
    private final long electionTimeoutMs;
    private final long heartbeatIntervalMs;
    private final int clusterSize;

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
        this.nodeId = nodeId;
        this.state = RaftState.FOLLOWER;
        this.currentLeader = null;
        this.votesReceived = 0;
        this.lastLogIndex = 0;
        this.lastLogTerm = 0;
        this.commitIndex = 0;
        this.electionTimeoutMs = electionTimeoutMs;
        this.heartbeatIntervalMs = heartbeatIntervalMs;
        this.clusterSize = clusterSize;
        this.transport = transport;
        this.peerSupplier = peerSupplier;
        this.metadataStore = metadataStore == null ? RaftMetadataStore.inMemory() : metadataStore;
        RaftMetadata restored = restoreMetadata();
        this.currentTerm = restored.currentTerm();
        this.votedFor = restored.votedFor();
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
     * Handles a heartbeat from the current leader.
     * Resets the election timeout.
     */
    public synchronized void receiveHeartbeat(String leaderId, int term) {
        if (term >= currentTerm) {
            boolean termChanged = term > currentTerm;
            currentTerm = term;
            currentLeader = leaderId;
            state = RaftState.FOLLOWER;
            votesReceived = 0;
            if (termChanged) {
                persistMetadata();
            }
        }
        scheduleElectionTimeout();
    }

    /**
     * Handles a vote request from a candidate.
     *
     * @return true if a vote is granted.
     */
    public synchronized boolean receiveVoteRequest(String candidateId, int term) {
        if (term > currentTerm) {
            currentTerm = term;
            currentLeader = null;
            state = RaftState.FOLLOWER;
            votesReceived = 0;
            votedFor = candidateId;
            persistMetadata();
            scheduleElectionTimeout();
            return true; // Vote for the candidate with higher term
        }
        if (term == currentTerm && (votedFor == null || votedFor.equals(candidateId))) {
            votedFor = candidateId;
            persistMetadata();
            scheduleElectionTimeout();
            return true;
        }
        return false;
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
     * Transitions this node to leader state and starts the heartbeat loop.
     */
    public synchronized void becomeLeader() {
        state = RaftState.LEADER;
        currentLeader = nodeId;
        scheduleHeartbeats();
    }

    /**
     * Sends AppendEntries heartbeats to all followers (called by the leader).
     */
    public synchronized void sendHeartbeats() {
        if (state != RaftState.LEADER || transport == null) {
            return;
        }
        int term = currentTerm;
        for (String peer : peers()) {
            AppendEntriesRequest req = new AppendEntriesRequest(
                    ClusterProtocol.PROTOCOL_VERSION,
                    ClusterProtocol.newId(),
                    ClusterProtocol.newId(),
                    nodeId,
                    ClusterProtocol.now(),
                    nodeId,
                    term,
                    lastLogIndex,
                    lastLogTerm,
                    List.of(),
                    commitIndex
            );
            transport.sendAppendEntries(peer, req)
                    .whenComplete((resp, err) -> onAppendEntriesResponse(resp, err));
        }
        scheduleHeartbeats();
    }

    public String getNodeId() { return nodeId; }
    public RaftState getState() { return state; }
    public String getCurrentLeader() { return currentLeader; }
    public int getCurrentTerm() { return currentTerm; }

    /**
     * Sends a RequestVote RPC to every peer for the current election term.
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
                    lastLogIndex,
                    lastLogTerm
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
     * Steps down to follower if a response reports a higher term.
     */
    private void onAppendEntriesResponse(AppendEntriesResponse resp, Throwable err) {
        if (err != null) {
            return;
        }
        synchronized (this) {
            if (resp.term() > currentTerm) {
                stepDown(resp.term());
            }
        }
    }

    private void stepDown(int term) {
        currentTerm = term;
        state = RaftState.FOLLOWER;
        currentLeader = null;
        votesReceived = 0;
        votedFor = null;
        persistMetadata();
        scheduleElectionTimeout();
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
     * @return The number of votes required to win an election.
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
            long jitter = (long) (electionTimeoutMs * 0.5 + Math.random() * electionTimeoutMs * 0.5);
            scheduler.schedule(this::startElection, jitter, TimeUnit.MILLISECONDS);
        }
    }

    public enum RaftState {
        FOLLOWER,
        CANDIDATE,
        LEADER
    }
}
