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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
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

    private volatile int commitIndex;

    private final Map<String, Integer> nextIndex = new HashMap<>();
    private final Map<String, Integer> matchIndex = new HashMap<>();

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
        this.nodeId = nodeId;
        this.state = RaftState.FOLLOWER;
        this.currentLeader = null;
        this.votesReceived = 0;
        this.commitIndex = 0;
        this.electionTimeoutMs = electionTimeoutMs;
        this.heartbeatIntervalMs = heartbeatIntervalMs;
        this.clusterSize = clusterSize;
        this.transport = transport;
        this.peerSupplier = peerSupplier;
        this.metadataStore = metadataStore == null ? RaftMetadataStore.inMemory() : metadataStore;
        this.log = log == null ? RaftLog.inMemory() : log;
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
        scheduleElectionTimeout();
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
                return;
            }
        }
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
     * next expected index.
     */
    private void sendAppendEntries(String peer) {
        int next = nextIndex.getOrDefault(peer, log.lastIndex() + 1);
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
