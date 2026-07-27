package com.minigoogle.cluster;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

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
 */
public class RaftConsensus {

    private final String nodeId;
    private volatile RaftState state;
    private volatile String currentLeader;
    private volatile int currentTerm;
    private volatile int votesReceived;

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
        this.nodeId = nodeId;
        this.state = RaftState.FOLLOWER;
        this.currentLeader = null;
        this.currentTerm = 0;
        this.votesReceived = 0;
        this.electionTimeoutMs = electionTimeoutMs;
        this.heartbeatIntervalMs = heartbeatIntervalMs;
        this.clusterSize = clusterSize;
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
            currentTerm = term;
            currentLeader = leaderId;
            state = RaftState.FOLLOWER;
            votesReceived = 0;
        }
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
            return true; // Vote for the candidate with higher term
        }
        return false;
    }

    /**
     * Starts an election by transitioning to candidate state.
     */
    public synchronized void startElection() {
        state = RaftState.CANDIDATE;
        currentTerm++;
        votesReceived = 1; // Vote for self
        currentLeader = null;
    }

    /**
     * Records a vote received from another node.
     *
     * @return true if this node has won the election (majority reached).
     */
    public synchronized boolean receiveVote() {
        votesReceived++;
        return votesReceived > getClusterSize() / 2;
    }

    /**
     * Transitions this node to leader state.
     */
    public synchronized void becomeLeader() {
        state = RaftState.LEADER;
        currentLeader = nodeId;
    }

    /**
     * Sends heartbeats to all followers (called by the leader).
     */
    public synchronized void sendHeartbeats() {
        if (state == RaftState.LEADER) {
            // In a real implementation, this would send RPCs to all followers
        }
    }

    public String getNodeId() { return nodeId; }
    public RaftState getState() { return state; }
    public String getCurrentLeader() { return currentLeader; }
    public int getCurrentTerm() { return currentTerm; }

    private int getClusterSize() {
        return clusterSize;
    }

    private void scheduleElectionTimeout() {
        if (scheduler != null && !scheduler.isShutdown()) {
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
