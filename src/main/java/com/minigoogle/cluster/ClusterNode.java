package com.minigoogle.cluster;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minigoogle.cluster.state.KvCommand;
import com.minigoogle.cluster.state.ReplicatedKeyValueStore;
import com.minigoogle.cluster.transport.ClusterTransport;
import com.minigoogle.cluster.transport.NodeDirectory;
import com.minigoogle.cluster.transport.SearchTransport;
import com.minigoogle.cluster.transport.http.GossipHandler;
import com.minigoogle.cluster.transport.http.HttpMembershipTransport;
import com.minigoogle.cluster.transport.http.HttpRaftTransport;
import com.minigoogle.cluster.transport.http.HttpSearchTransport;
import com.minigoogle.cluster.transport.http.InternalClusterServer;
import com.minigoogle.cluster.transport.http.RaftHandler;
import com.minigoogle.cluster.transport.http.SearchHandler;
import com.minigoogle.distributed.query.execution.SearchExecutor;
import com.minigoogle.storage.filesystem.StorageLayout;
import com.minigoogle.storage.metadata.RaftAppliedStore;
import com.minigoogle.storage.metadata.RaftConfigurationStore;
import com.minigoogle.storage.metadata.RaftMetadataStore;
import com.minigoogle.storage.metadata.RaftSnapshotStore;
import com.minigoogle.storage.wal.WriteAheadLog;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Assemblies the Phase 1 cluster stack for a single node:
 * internal RPC server + gossip (with ring auto-update) + raft, all sharing
 * one node identity and one node directory.
 *
 * <p>When a {@link SearchExecutor} is provided (see the {@code localSearch}
 * constructor overload), the node also serves {@code /cluster/v1/search/dispatch}
 * so coordinators can fan queries out over the transport.</p>
 */
public class ClusterNode {
    private static final long OPERATION_TIMEOUT_MS = 10_000;

    /**
     * Default entries between state-machine snapshots when a node is given a
     * storage directory. Configurable via the explicit {@code snapshotInterval}
     * constructor overload.
     */
    private static final int SNAPSHOT_INTERVAL = 10_000;

    private final String nodeId;
    private final InternalClusterServer server;
    private final GossipProtocol gossip;
    private final RaftConsensus raft;
    private final ConsistentHashRing ring;
    private final List<ClusterTransport> transports;
    private final ReplicatedKeyValueStore kv;

    public ClusterNode(String nodeId, int port, NodeDirectory directory) throws IOException {
        this(nodeId, port, directory, 1000, 5000, 5000, 1000, null);
    }

    public ClusterNode(String nodeId, int port, NodeDirectory directory, long gossipInterval, long gossipTimeout) throws IOException {
        this(nodeId, port, directory, gossipInterval, gossipTimeout, 5000, 1000, null);
    }

    /**
     * Creates a cluster node that also serves search dispatch requests.
     *
     * @param nodeId           The unique identifier for this node.
     * @param port             The internal RPC port.
     * @param directory        Resolves peer node IDs to base URIs.
     * @param gossipInterval   Gossip round interval in milliseconds.
     * @param gossipTimeout    Failure detection timeout in milliseconds.
     * @param localSearch      Executor for local queries, or {@code null} to
     *                         disable the search dispatch endpoint.
     */
    public ClusterNode(String nodeId, int port, NodeDirectory directory, long gossipInterval, long gossipTimeout,
                       SearchExecutor localSearch) throws IOException {
        this(nodeId, port, directory, gossipInterval, gossipTimeout, 5000, 1000, localSearch);
    }

    /**
     * Creates a fully configured cluster node.
     *
     * @param nodeId               The unique identifier for this node.
     * @param port                 The internal RPC port.
     * @param directory            Resolves peer node IDs to base URIs.
     * @param gossipInterval       Gossip round interval in milliseconds.
     * @param gossipTimeout        Failure detection timeout in milliseconds.
     * @param raftElectionTimeout  Raft election timeout in milliseconds.
     * @param raftHeartbeat        Raft heartbeat interval in milliseconds.
     * @param localSearch          Executor for local queries, or {@code null} to
     *                             disable the search dispatch endpoint.
     */
    public ClusterNode(String nodeId, int port, NodeDirectory directory, long gossipInterval, long gossipTimeout,
                       long raftElectionTimeout, long raftHeartbeat, SearchExecutor localSearch) throws IOException {
        this(nodeId, port, directory, gossipInterval, gossipTimeout, raftElectionTimeout, raftHeartbeat, localSearch,
                ClusterSecurity.withRandomSecret());
    }

    /**
     * Creates a fully configured, authenticated cluster node.
     *
     * <p>Every internal endpoint is registered behind a bearer-token
     * {@code AuthFilter}; each transport attaches the node's derived token to
     * every request. Peers must share the same {@link ClusterSecurity} secret,
     * otherwise their derived tokens will not validate.</p>
     *
     * @param nodeId               The unique identifier for this node.
     * @param port                 The internal RPC port.
     * @param directory            Resolves peer node IDs to base URIs.
     * @param gossipInterval       Gossip round interval in milliseconds.
     * @param gossipTimeout        Failure detection timeout in milliseconds.
     * @param raftElectionTimeout  Raft election timeout in milliseconds.
     * @param raftHeartbeat        Raft heartbeat interval in milliseconds.
     * @param localSearch          Executor for local queries, or {@code null} to
     *                             disable the search dispatch endpoint.
     * @param security             The shared cluster security manager.
     */
    public ClusterNode(String nodeId, int port, NodeDirectory directory, long gossipInterval, long gossipTimeout,
                       long raftElectionTimeout, long raftHeartbeat, SearchExecutor localSearch,
                       ClusterSecurity security) throws IOException {
        this(nodeId, port, directory, gossipInterval, gossipTimeout, raftElectionTimeout, raftHeartbeat, localSearch,
                security, RaftMetadataStore.inMemory());
    }

    /**
     * Creates a fully configured, authenticated cluster node with durable Raft
     * election metadata rooted at {@code storageDirectory}.
     *
     * <p>Raft's {@code currentTerm} and {@code votedFor} are persisted to
     * {@code <storageDirectory>/raft-metadata.bin} before every vote reply, so
     * a node restarted on the same directory resumes with the same term and
     * vote and can never double-vote or regress its term.</p>
     *
     * @param nodeId               The unique identifier for this node.
     * @param port                 The internal RPC port.
     * @param directory            Resolves peer node IDs to base URIs.
     * @param gossipInterval       Gossip round interval in milliseconds.
     * @param gossipTimeout        Failure detection timeout in milliseconds.
     * @param raftElectionTimeout  Raft election timeout in milliseconds.
     * @param raftHeartbeat        Raft heartbeat interval in milliseconds.
     * @param localSearch          Executor for local queries, or {@code null} to
     *                             disable the search dispatch endpoint.
     * @param security             The shared cluster security manager.
     * @param storageDirectory     Base directory for durable cluster state, or
     *                             {@code null} to keep Raft metadata in memory.
     */
    public ClusterNode(String nodeId, int port, NodeDirectory directory, long gossipInterval, long gossipTimeout,
                       long raftElectionTimeout, long raftHeartbeat, SearchExecutor localSearch,
                       ClusterSecurity security, Path storageDirectory) throws IOException {
        this(nodeId, port, directory, gossipInterval, gossipTimeout, raftElectionTimeout, raftHeartbeat, localSearch,
                security, createRaftMetadataStore(storageDirectory), createRaftLog(storageDirectory),
                null, null, createRaftSnapshotStore(storageDirectory), SNAPSHOT_INTERVAL,
                createRaftConfigurationStore(storageDirectory));
    }

    /**
     * Creates a fully configured, authenticated cluster node with an explicit
     * Raft metadata store.
     *
     * @param nodeId               The unique identifier for this node.
     * @param port                 The internal RPC port.
     * @param directory            Resolves peer node IDs to base URIs.
     * @param gossipInterval       Gossip round interval in milliseconds.
     * @param gossipTimeout        Failure detection timeout in milliseconds.
     * @param raftElectionTimeout  Raft election timeout in milliseconds.
     * @param raftHeartbeat        Raft heartbeat interval in milliseconds.
     * @param localSearch          Executor for local queries, or {@code null} to
     *                             disable the search dispatch endpoint.
     * @param security             The shared cluster security manager.
     * @param raftMetadataStore    Store for {@code currentTerm} and
     *                             {@code votedFor}, or {@code null} to keep the
     *                             metadata in memory only.
     */
    public ClusterNode(String nodeId, int port, NodeDirectory directory, long gossipInterval, long gossipTimeout,
                       long raftElectionTimeout, long raftHeartbeat, SearchExecutor localSearch,
                       ClusterSecurity security, RaftMetadataStore raftMetadataStore) throws IOException {
        this(nodeId, port, directory, gossipInterval, gossipTimeout, raftElectionTimeout, raftHeartbeat, localSearch,
                security, raftMetadataStore, RaftLog.inMemory());
    }

    /**
     * Creates a fully configured, authenticated cluster node with explicit
     * Raft metadata store and replicated log.
     *
     * @param nodeId               The unique identifier for this node.
     * @param port                 The internal RPC port.
     * @param directory            Resolves peer node IDs to base URIs.
     * @param gossipInterval       Gossip round interval in milliseconds.
     * @param gossipTimeout        Failure detection timeout in milliseconds.
     * @param raftElectionTimeout  Raft election timeout in milliseconds.
     * @param raftHeartbeat        Raft heartbeat interval in milliseconds.
     * @param localSearch          Executor for local queries, or {@code null} to
     *                             disable the search dispatch endpoint.
     * @param security             The shared cluster security manager.
     * @param raftMetadataStore    Store for {@code currentTerm} and
     *                             {@code votedFor}, or {@code null} to keep the
     *                             metadata in memory only.
     * @param raftLog              The replicated log, or {@code null} for a
     *                             memory-only log.
     */
    public ClusterNode(String nodeId, int port, NodeDirectory directory, long gossipInterval, long gossipTimeout,
                       long raftElectionTimeout, long raftHeartbeat, SearchExecutor localSearch,
                       ClusterSecurity security, RaftMetadataStore raftMetadataStore, RaftLog raftLog) throws IOException {
        this(nodeId, port, directory, gossipInterval, gossipTimeout, raftElectionTimeout, raftHeartbeat, localSearch,
                security, raftMetadataStore, raftLog, null, null);
    }

    /**
     * Creates a fully configured, authenticated cluster node with an explicit
     * Raft metadata store, replicated log, and replicated key-value state
     * machine.
     *
     * <p>Committed entries are applied to {@code stateMachine} in order, and
     * the apply watermark is durably recorded in {@code appliedStore}, so a
     * node restarted on the same stores rebuilds the key-value state from its
     * log without waiting for the next commit. The public {@link #put(String, byte[])},
     * {@link #delete(String)}, and {@link #get(String)} operations require a
     * state machine; without one they fail with {@link IllegalStateException}.
     *
     * @param nodeId               The unique identifier for this node.
     * @param port                 The internal RPC port.
     * @param directory            Resolves peer node IDs to base URIs.
     * @param gossipInterval       Gossip round interval in milliseconds.
     * @param gossipTimeout        Failure detection timeout in milliseconds.
     * @param raftElectionTimeout  Raft election timeout in milliseconds.
     * @param raftHeartbeat        Raft heartbeat interval in milliseconds.
     * @param localSearch          Executor for local queries, or {@code null} to
     *                             disable the search dispatch endpoint.
     * @param security             The shared cluster security manager.
     * @param raftMetadataStore    Store for {@code currentTerm} and
     *                             {@code votedFor}, or {@code null} to keep the
     *                             metadata in memory only.
     * @param raftLog              The replicated log, or {@code null} for a
     *                             memory-only log.
     * @param stateMachine         The replicated key-value state machine, or
     *                             {@code null} to disable the client operations.
     * @param appliedStore         Store for the apply watermark, or {@code null}
     *                             to keep it in memory only.
     */
    public ClusterNode(String nodeId, int port, NodeDirectory directory, long gossipInterval, long gossipTimeout,
                       long raftElectionTimeout, long raftHeartbeat, SearchExecutor localSearch,
                       ClusterSecurity security, RaftMetadataStore raftMetadataStore, RaftLog raftLog,
                       ReplicatedKeyValueStore stateMachine, RaftAppliedStore appliedStore) throws IOException {
        this(nodeId, port, directory, gossipInterval, gossipTimeout, raftElectionTimeout, raftHeartbeat, localSearch,
                security, raftMetadataStore, raftLog, stateMachine, appliedStore, null, 0);
    }

    /**
     * Creates a fully configured, authenticated cluster node with explicit
     * Raft metadata store, replicated log, replicated key-value state machine,
     * and snapshot-driven log compaction.
     *
     * <p>Committed entries are applied to {@code stateMachine} in order, and
     * the apply watermark is durably recorded in {@code appliedStore}, so a
     * node restarted on the same stores rebuilds the key-value state from its
     * log without waiting for the next commit. Every {@code snapshotInterval}
     * committed entries, the applied state is captured into
     * {@code snapshotStore} and the log prefix is compacted; a follower that
     * falls below the compacted prefix is caught up with an InstallSnapshot
     * RPC. A compacted {@code raft-log.bin} tail is only interpretable with the
     * snapshot's base, so a node given a storage directory opens its log at the
     * snapshot's last included index.
     *
     * @param nodeId               The unique identifier for this node.
     * @param port                 The internal RPC port.
     * @param directory            Resolves peer node IDs to base URIs.
     * @param gossipInterval       Gossip round interval in milliseconds.
     * @param gossipTimeout        Failure detection timeout in milliseconds.
     * @param raftElectionTimeout  Raft election timeout in milliseconds.
     * @param raftHeartbeat        Raft heartbeat interval in milliseconds.
     * @param localSearch          Executor for local queries, or {@code null} to
     *                             disable the search dispatch endpoint.
     * @param security             The shared cluster security manager.
     * @param raftMetadataStore    Store for {@code currentTerm} and
     *                             {@code votedFor}, or {@code null} to keep the
     *                             metadata in memory only.
     * @param raftLog              The replicated log, or {@code null} for a
     *                             memory-only log.
     * @param stateMachine         The replicated key-value state machine, or
     *                             {@code null} to disable the client operations.
     * @param appliedStore         Store for the apply watermark, or {@code null}
     *                             to keep it in memory only.
     * @param snapshotStore        Store for state-machine snapshots, or
     *                             {@code null} to disable log compaction.
     * @param snapshotInterval     Entries between snapshots; ignored when
     *                             {@code snapshotStore} is {@code null}.
     */
    public ClusterNode(String nodeId, int port, NodeDirectory directory, long gossipInterval, long gossipTimeout,
                       long raftElectionTimeout, long raftHeartbeat, SearchExecutor localSearch,
                       ClusterSecurity security, RaftMetadataStore raftMetadataStore, RaftLog raftLog,
                       ReplicatedKeyValueStore stateMachine, RaftAppliedStore appliedStore,
                       RaftSnapshotStore snapshotStore, int snapshotInterval) throws IOException {
        this(nodeId, port, directory, gossipInterval, gossipTimeout, raftElectionTimeout, raftHeartbeat, localSearch,
                security, raftMetadataStore, raftLog, stateMachine, appliedStore, snapshotStore, snapshotInterval, null);
    }

    /**
     * Creates a fully configured, authenticated cluster node with explicit
     * Raft metadata store, replicated log, replicated key-value state machine,
     * snapshot-driven log compaction, and a durable committed configuration for
     * membership reconfiguration.
     *
     * <p>In addition to the {@code snapshotStore}/{@code snapshotInterval}
     * behavior, the committed configuration is persisted to {@code configStore}
     * so a node restarted on the same directory resumes with the same member
     * set (and quorum) before gossip converges. The {@link #initializeConfig(List)}
     * operation establishes the bootstrap configuration once; afterwards
     * membership is driven exclusively by {@link #addNode(String)} and
     * {@link #removeNode(String)}, which return only after the change commits.
     *
     * @param nodeId               The unique identifier for this node.
     * @param port                 The internal RPC port.
     * @param directory            Resolves peer node IDs to base URIs.
     * @param gossipInterval       Gossip round interval in milliseconds.
     * @param gossipTimeout        Failure detection timeout in milliseconds.
     * @param raftElectionTimeout  Raft election timeout in milliseconds.
     * @param raftHeartbeat        Raft heartbeat interval in milliseconds.
     * @param localSearch          Executor for local queries, or {@code null} to
     *                             disable the search dispatch endpoint.
     * @param security             The shared cluster security manager.
     * @param raftMetadataStore    Store for {@code currentTerm} and
     *                             {@code votedFor}, or {@code null} to keep the
     *                             metadata in memory only.
     * @param raftLog              The replicated log, or {@code null} for a
     *                             memory-only log.
     * @param stateMachine         The replicated key-value state machine, or
     *                             {@code null} to disable the client operations.
     * @param appliedStore         Store for the apply watermark, or {@code null}
     *                             to keep it in memory only.
     * @param snapshotStore        Store for state-machine snapshots, or
     *                             {@code null} to disable log compaction.
     * @param snapshotInterval     Entries between snapshots; ignored when
     *                             {@code snapshotStore} is {@code null}.
     * @param configStore          Store for the committed configuration, or
     *                             {@code null} to keep it in memory only.
     */
    public ClusterNode(String nodeId, int port, NodeDirectory directory, long gossipInterval, long gossipTimeout,
                       long raftElectionTimeout, long raftHeartbeat, SearchExecutor localSearch,
                       ClusterSecurity security, RaftMetadataStore raftMetadataStore, RaftLog raftLog,
                       ReplicatedKeyValueStore stateMachine, RaftAppliedStore appliedStore,
                       RaftSnapshotStore snapshotStore, int snapshotInterval,
                       RaftConfigurationStore configStore) throws IOException {
        this.nodeId = nodeId;
        this.kv = stateMachine;
        ObjectMapper mapper = new ObjectMapper();
        this.server = new InternalClusterServer(port, mapper);

        // Consistent hash ring — automatically updated by gossip via listener
        this.ring = new ConsistentHashRing();
        ring.addNode(nodeId); // Always include self

        String bearerToken = security.deriveToken(nodeId);
        HttpMembershipTransport membershipTransport = new HttpMembershipTransport(directory, mapper, nodeId, bearerToken);
        HttpRaftTransport raftTransport = new HttpRaftTransport(directory, mapper, nodeId, bearerToken);
        HttpSearchTransport searchTransport = new HttpSearchTransport(directory, mapper, nodeId, bearerToken);

        this.gossip = new GossipProtocol(nodeId, gossipInterval, gossipTimeout, membershipTransport);
        gossip.addListener(new RingMembershipListener(ring));

        // Raft resolves its peers from the gossip membership table, so it only
        // campaigns against nodes that gossip currently believes are alive.
        this.raft = new RaftConsensus(nodeId, raftElectionTimeout, raftHeartbeat, 3, raftTransport, gossip::getLiveNodes,
                raftMetadataStore == null ? RaftMetadataStore.inMemory() : raftMetadataStore,
                raftLog == null ? RaftLog.inMemory() : raftLog,
                stateMachine, appliedStore, snapshotStore, snapshotInterval, configStore);

        server.registerProtectedContext("/cluster/v1/gossip/exchange", new GossipHandler(gossip, mapper, nodeId), security);
        server.registerProtectedContext("/cluster/v1/raft/request-vote", new RaftHandler(raft, mapper, nodeId), security);
        server.registerProtectedContext("/cluster/v1/raft/append-entries", new RaftHandler(raft, mapper, nodeId), security);
        server.registerProtectedContext("/cluster/v1/raft/install-snapshot", new RaftHandler(raft, mapper, nodeId), security);
        server.registerProtectedContext("/cluster/v1/search/dispatch", new SearchHandler(localSearch, mapper, nodeId), security);

        this.transports = List.of(membershipTransport, raftTransport, searchTransport);
    }

    private static RaftMetadataStore createRaftMetadataStore(Path storageDirectory) {
        if (storageDirectory == null) {
            return RaftMetadataStore.inMemory();
        }
        return new RaftMetadataStore(new StorageLayout(storageDirectory).getRaftMetadataPath());
    }

    private static RaftLog createRaftLog(Path storageDirectory) {
        if (storageDirectory == null) {
            return RaftLog.inMemory();
        }
        try {
            StorageLayout layout = new StorageLayout(storageDirectory);
            WriteAheadLog wal = new WriteAheadLog(layout.getRaftLogPath());
            RaftSnapshot snapshot = new RaftSnapshotStore(layout.getRaftSnapshotPath()).load();
            if (snapshot == null) {
                return new RaftLog(wal);
            }
            // A compacted WAL tail is only interpretable with the snapshot's
            // base, so the log is replayed at the snapshot's last included index.
            return new RaftLog(wal, snapshot.lastIncludedIndex(), snapshot.lastIncludedTerm());
        } catch (IOException e) {
            throw new java.io.UncheckedIOException("Failed to load raft log; refusing to start", e);
        }
    }

    private static RaftSnapshotStore createRaftSnapshotStore(Path storageDirectory) {
        if (storageDirectory == null) {
            return RaftSnapshotStore.inMemory();
        }
        return new RaftSnapshotStore(new StorageLayout(storageDirectory).getRaftSnapshotPath());
    }

    private static RaftConfigurationStore createRaftConfigurationStore(Path storageDirectory) {
        if (storageDirectory == null) {
            return RaftConfigurationStore.inMemory();
        }
        return new RaftConfigurationStore(new StorageLayout(storageDirectory).getRaftConfigPath());
    }

    public void start() {
        server.start();
        for (ClusterTransport transport : transports) {
            transport.start();
        }
        gossip.start();
        raft.start();
    }

    public void stop() {
        gossip.stop();
        raft.stop();
        for (ClusterTransport transport : transports) {
            transport.stop();
        }
        server.stop();
    }

    public GossipProtocol getGossip() {
        return gossip;
    }

    public RaftConsensus getRaft() {
        return raft;
    }

    public ConsistentHashRing getRing() {
        return ring;
    }

    public InternalClusterServer getServer() {
        return server;
    }

    /**
     * Establishes the bootstrap configuration: the member set the cluster
     * starts with (self plus the seed peers). Persisted so a restart restores
     * it before gossip converges. Allowed only once, before any config change
     * has been committed; afterwards membership is driven exclusively by
     * {@link #addNode(String)} and {@link #removeNode(String)}.
     *
     * @param members The initial members; must include this node.
     * @throws IllegalStateException If a configuration is already established.
     */
    public void initializeConfig(List<String> members) {
        raft.initializeConfig(members);
    }

    /**
     * Adds a server to the cluster through the replicated log. Returns only
     * after the config-change entry is committed by a majority and applied, so
     * a successful return is durable. The joining server need not be running:
     * the change commits on the old configuration's majority, and the new
     * server catches up when it joins (via log replication or InstallSnapshot).
     *
     * @param nodeId The node ID to add.
     * @throws NotLeaderException If this node is not the leader, a change is
     *                            already pending, or commit is not reached
     *                            before the operation timeout.
     */
    public void addNode(String nodeId) {
        changeConfig(ConfigChange.ChangeType.ADD, nodeId);
    }

    /**
     * Removes a server from the cluster through the replicated log. Returns
     * only after the config-change entry is committed by a majority and
     * applied. The removed server stops counting toward the quorum once the
     * change commits; a leader that removes itself steps down so the survivors
     * re-elect.
     *
     * @param nodeId The node ID to remove.
     * @throws NotLeaderException If this node is not the leader, a change is
     *                            already pending, or commit is not reached
     *                            before the operation timeout.
     */
    public void removeNode(String nodeId) {
        changeConfig(ConfigChange.ChangeType.REMOVE, nodeId);
    }

    /**
     * @return The committed configuration, or the empty configuration before
     *         {@link #initializeConfig(List)} is called.
     */
    public ClusterConfiguration getCommittedConfig() {
        return raft.getCommittedConfig();
    }

    private void changeConfig(ConfigChange.ChangeType type, String nodeId) {
        int index;
        try {
            index = raft.appendConfigChange(new ConfigChange(type, nodeId));
        } catch (IllegalStateException e) {
            throw new NotLeaderException(raft.getCurrentLeader(),
                    "Node " + this.nodeId + " cannot apply " + type + " of " + nodeId + ": " + e.getMessage());
        }
        long deadline = System.currentTimeMillis() + OPERATION_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            ClusterConfiguration committed = raft.getCommittedConfig();
            if (type == ConfigChange.ChangeType.ADD ? committed.contains(nodeId) : !committed.contains(nodeId)) {
                return;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new NotLeaderException(raft.getCurrentLeader(),
                        "Interrupted waiting for config change to commit on " + this.nodeId);
            }
        }
        throw new NotLeaderException(raft.getCurrentLeader(),
                "Config change " + type + " of " + nodeId + " timed out on " + this.nodeId + " (entry " + index + " not committed)");
    }

    /**
     * Linearly puts a value for {@code key} into the replicated state machine.
     * Returns only after the entry is committed by a majority and applied, so
     * a successful return is durable.
     *
     * @throws NotLeaderException If this node is not the leader, or commit is
     *                            not reached before the operation timeout.
     */
    public void put(String key, byte[] value) {
        ensureStateMachine();
        appendAndWait(KvCommand.encodePut(key, value));
    }

    /**
     * Linearly deletes {@code key} from the replicated state machine. Returns
     * only after the entry is committed by a majority and applied.
     *
     * @throws NotLeaderException If this node is not the leader, or commit is
     *                            not reached before the operation timeout.
     */
    public void delete(String key) {
        ensureStateMachine();
        appendAndWait(KvCommand.encodeDelete(key));
    }

    /**
     * Linearly reads the value for {@code key}. The read is served only after
     * a read-index barrier confirms this node is still the leader for its term,
     * so a partitioned leader cannot return stale state.
     *
     * @return The stored value, or {@code null} if absent.
     * @throws NotLeaderException If this node is not the leader or cannot
     *                            establish a read barrier.
     */
    public byte[] get(String key) {
        ensureStateMachine();
        if (raft.getState() != RaftConsensus.RaftState.LEADER) {
            throw new NotLeaderException(raft.getCurrentLeader(),
                    "Node " + nodeId + " is not the leader; leader is " + raft.getCurrentLeader());
        }
        if (!raft.prepareReadBarrier()) {
            throw new NotLeaderException(raft.getCurrentLeader(),
                    "Node " + nodeId + " could not establish a read barrier (no quorum)");
        }
        return kv.get(key);
    }

    private void ensureStateMachine() {
        if (kv == null) {
            throw new IllegalStateException("Node " + nodeId + " has no replicated key-value state machine");
        }
    }

    private void appendAndWait(byte[] command) {
        int index;
        try {
            index = raft.appendEntry(command);
        } catch (IllegalStateException e) {
            throw new NotLeaderException(raft.getCurrentLeader(),
                    "Node " + nodeId + " is not the leader; leader is " + raft.getCurrentLeader());
        }
        try {
            kv.awaitCommit(index).get(OPERATION_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new NotLeaderException(raft.getCurrentLeader(),
                    "Write to " + nodeId + " timed out waiting for commit");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new NotLeaderException(raft.getCurrentLeader(),
                    "Interrupted waiting for commit on " + nodeId);
        } catch (ExecutionException e) {
            throw new NotLeaderException(raft.getCurrentLeader(),
                    "Failed waiting for commit on " + nodeId, e);
        }
    }
}
