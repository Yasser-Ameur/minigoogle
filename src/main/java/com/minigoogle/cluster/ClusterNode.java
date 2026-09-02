package com.minigoogle.cluster;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minigoogle.cluster.placement.DocumentIngest;
import com.minigoogle.cluster.placement.DocumentPlacement;
import com.minigoogle.cluster.placement.HttpIngestTransport;
import com.minigoogle.cluster.placement.IngestHandler;
import com.minigoogle.cluster.placement.IngestedDocument;
import com.minigoogle.cluster.placement.LocalDocuments;
import com.minigoogle.cluster.placement.PlacementRepairListener;
import com.minigoogle.cluster.placement.PlacementResult;
import com.minigoogle.cluster.routing.BroadcastQueryRouter;
import com.minigoogle.cluster.state.KvCommand;
import com.minigoogle.cluster.state.ReplicatedKeyValueStore;
import com.minigoogle.cluster.transport.ClusterTransport;
import com.minigoogle.cluster.transport.NodeDirectory;
import com.minigoogle.cluster.transport.RaftTransport;
import com.minigoogle.cluster.transport.SearchTransport;
import com.minigoogle.cluster.transport.dto.ReadIndexRequest;
import com.minigoogle.cluster.transport.dto.ReadIndexResponse;
import com.minigoogle.cluster.transport.http.GossipHandler;
import com.minigoogle.cluster.transport.http.HttpMembershipTransport;
import com.minigoogle.cluster.transport.http.HttpRaftTransport;
import com.minigoogle.cluster.transport.http.HttpSearchTransport;
import com.minigoogle.cluster.transport.http.InternalClusterServer;
import com.minigoogle.cluster.transport.http.RaftHandler;
import com.minigoogle.cluster.transport.http.SearchHandler;
import com.minigoogle.distributed.query.coordinator.DistributedSearchCoordinator;
import com.minigoogle.distributed.query.execution.LocalSearchExecutor;
import com.minigoogle.distributed.query.execution.SearchExecutor;
import com.minigoogle.distributed.query.model.LocalSearchResponse;
import com.minigoogle.distributed.query.model.QueryContext;
import com.minigoogle.network.dto.SearchResponse;
import com.minigoogle.network.dto.SearchResult;
import com.minigoogle.storage.filesystem.StorageLayout;
import com.minigoogle.storage.metadata.RaftAppliedStore;
import com.minigoogle.storage.metadata.RaftConfigurationStore;
import com.minigoogle.storage.metadata.RaftMetadataStore;
import com.minigoogle.storage.metadata.RaftSnapshotStore;
import com.minigoogle.storage.wal.WriteAheadLog;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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

    /**
     * Default document replication factor for the constructor overloads that
     * do not take an explicit one.
     */
    private static final int DEFAULT_REPLICATION_FACTOR = 2;

    private final String nodeId;
    private final InternalClusterServer server;
    private final GossipProtocol gossip;
    private final RaftConsensus raft;
    private final ConsistentHashRing ring;
    private final List<ClusterTransport> transports;
    private final RaftTransport raftTransport;
    private final ReplicatedKeyValueStore kv;
    private final SearchExecutor localSearch;
    private final HttpSearchTransport searchTransport;
    private final DocumentPlacement placement;
    private final HttpIngestTransport ingestTransport;
    private final PlacementRepairListener placementRepairListener;

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
        // A node configured with durable storage gets a complete durable stack:
        // metadata, log, snapshot and configuration stores AND a replicated
        // state machine with its applied-index store. Previously this
        // constructor passed null for the state machine, so the most
        // production-shaped way to build a node produced one that threw on
        // every put/get - durable consensus with nothing to apply it to.
        this(nodeId, port, directory, gossipInterval, gossipTimeout, raftElectionTimeout, raftHeartbeat, localSearch,
                security, createRaftMetadataStore(storageDirectory), createRaftLog(storageDirectory),
                new ReplicatedKeyValueStore(), createRaftAppliedStore(storageDirectory),
                createRaftSnapshotStore(storageDirectory), SNAPSHOT_INTERVAL,
                createRaftConfigurationStore(storageDirectory), DEFAULT_REPLICATION_FACTOR, null, null);
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
                security, raftMetadataStore, raftLog, stateMachine, appliedStore, snapshotStore, snapshotInterval, null,
                DEFAULT_REPLICATION_FACTOR, null, null);
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
     * @param replicationFactor    How many nodes each document is placed on;
     *                             see {@link #getPlacement()}.
     * @param documentIngest       Accepts documents placed or repaired onto
     *                             this node by a peer, or {@code null} to
     *                             disable placement delivery (the ingest
     *                             endpoint still exists but answers 503, and
     *                             {@link #place} throws).
     * @param localDocuments       Enumerates this node's own documents for
     *                             {@link com.minigoogle.cluster.placement.PlacementRepairListener},
     *                             or {@code null} to skip repair.
     */
    public ClusterNode(String nodeId, int port, NodeDirectory directory, long gossipInterval, long gossipTimeout,
                       long raftElectionTimeout, long raftHeartbeat, SearchExecutor localSearch,
                       ClusterSecurity security, RaftMetadataStore raftMetadataStore, RaftLog raftLog,
                       ReplicatedKeyValueStore stateMachine, RaftAppliedStore appliedStore,
                       RaftSnapshotStore snapshotStore, int snapshotInterval,
                       RaftConfigurationStore configStore) throws IOException {
        this(nodeId, port, directory, gossipInterval, gossipTimeout, raftElectionTimeout, raftHeartbeat, localSearch,
                security, raftMetadataStore, raftLog, stateMachine, appliedStore, snapshotStore, snapshotInterval,
                configStore, DEFAULT_REPLICATION_FACTOR, null, null);
    }

    /**
     * Creates a fully configured, authenticated cluster node with explicit
     * Raft metadata store, replicated log, replicated key-value state machine,
     * snapshot-driven log compaction, a durable committed configuration for
     * membership reconfiguration, and document placement on the consistent
     * hash ring.
     *
     * <p>Document placement is layered on top of everything the {@code
     * configStore} overload provides: every document is placed on {@code
     * replicationFactor} ring owners (see {@link #getPlacement()} and {@link
     * #place}), incoming placements and repairs from peers are handed to
     * {@code documentIngest}, and {@code localDocuments} lets a {@link
     * com.minigoogle.cluster.placement.PlacementRepairListener} re-deliver
     * this node's documents to any owner a membership change adds.</p>
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
     * @param replicationFactor    How many nodes each document is placed on;
     *                             see {@link #getPlacement()}.
     * @param documentIngest       Accepts documents placed or repaired onto
     *                             this node by a peer, or {@code null} to
     *                             disable placement delivery (the ingest
     *                             endpoint still exists but answers 503).
     * @param localDocuments       Enumerates this node's own documents for
     *                             {@link com.minigoogle.cluster.placement.PlacementRepairListener},
     *                             or {@code null} to skip repair.
     */
    public ClusterNode(String nodeId, int port, NodeDirectory directory, long gossipInterval, long gossipTimeout,
                       long raftElectionTimeout, long raftHeartbeat, SearchExecutor localSearch,
                       ClusterSecurity security, RaftMetadataStore raftMetadataStore, RaftLog raftLog,
                       ReplicatedKeyValueStore stateMachine, RaftAppliedStore appliedStore,
                       RaftSnapshotStore snapshotStore, int snapshotInterval,
                       RaftConfigurationStore configStore, int replicationFactor,
                       DocumentIngest documentIngest, LocalDocuments localDocuments) throws IOException {
        this.nodeId = nodeId;
        this.kv = stateMachine;
        this.localSearch = localSearch;
        ObjectMapper mapper = new ObjectMapper();
        this.server = new InternalClusterServer(port, mapper);

        // Consistent hash ring — automatically updated by gossip via listener
        this.ring = new ConsistentHashRing();
        ring.addNode(nodeId); // Always include self

        String bearerToken = security.deriveToken(nodeId);
        HttpMembershipTransport membershipTransport = new HttpMembershipTransport(directory, mapper, nodeId, bearerToken);
        HttpRaftTransport raftTransport = new HttpRaftTransport(directory, mapper, nodeId, bearerToken);
        HttpSearchTransport searchTransport = new HttpSearchTransport(directory, mapper, nodeId, bearerToken);
        this.searchTransport = searchTransport;

        this.gossip = new GossipProtocol(nodeId, gossipInterval, gossipTimeout, membershipTransport);
        gossip.addListener(new RingMembershipListener(ring));

        // Raft resolves its peers from the gossip membership table, so it only
        // campaigns against nodes that gossip currently believes are alive.
        this.raft = new RaftConsensus(nodeId, raftElectionTimeout, raftHeartbeat, 3, raftTransport, gossip::getLiveNodes,
                raftMetadataStore == null ? RaftMetadataStore.inMemory() : raftMetadataStore,
                raftLog == null ? RaftLog.inMemory() : raftLog,
                stateMachine, appliedStore, snapshotStore, snapshotInterval, configStore);

        this.placement = new DocumentPlacement(ring, replicationFactor);
        HttpIngestTransport ingestTransport = new HttpIngestTransport(directory, mapper, nodeId, bearerToken);
        this.ingestTransport = ingestTransport;

        server.registerProtectedContext("/cluster/v1/gossip/exchange", new GossipHandler(gossip, mapper, nodeId), security);
        server.registerProtectedContext("/cluster/v1/raft/request-vote", new RaftHandler(raft, mapper, nodeId), security);
        server.registerProtectedContext("/cluster/v1/raft/append-entries", new RaftHandler(raft, mapper, nodeId), security);
        server.registerProtectedContext("/cluster/v1/raft/install-snapshot", new RaftHandler(raft, mapper, nodeId), security);
        server.registerProtectedContext("/cluster/v1/raft/read-index", new RaftHandler(raft, mapper, nodeId), security);
        server.registerProtectedContext("/cluster/v1/search/dispatch", new SearchHandler(localSearch, mapper, nodeId), security);
        server.registerProtectedContext("/cluster/v1/documents/ingest", new IngestHandler(documentIngest, mapper, nodeId), security);

        if (localDocuments != null) {
            this.placementRepairListener = new PlacementRepairListener(placement, localDocuments, ingestTransport, nodeId);
            gossip.addListener(placementRepairListener);
        } else {
            this.placementRepairListener = null;
        }

        this.raftTransport = raftTransport;
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

    private static RaftAppliedStore createRaftAppliedStore(Path storageDirectory) {
        if (storageDirectory == null) {
            return RaftAppliedStore.inMemory();
        }
        return new RaftAppliedStore(new StorageLayout(storageDirectory).getRaftAppliedPath());
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
        if (placementRepairListener != null) {
            placementRepairListener.shutdown();
        }
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
     * @return The ring-backed placement policy for this node's replication
     *         factor. Always available, independent of whether a {@link
     *         DocumentIngest} was configured.
     */
    public DocumentPlacement getPlacement() {
        return placement;
    }

    /**
     * Places {@code doc} on its ring owners, delivering it to every owner
     * other than this node over HTTP. The caller (this node, having just
     * crawled the document) is expected to keep its own local copy outside of
     * placement — {@code place} only forwards to remote owners and reports
     * whether this node is itself one of them. Delivery to a remote owner that
     * currently has no {@link DocumentIngest} configured (placement disabled
     * there) counts as a failure, the same as an unreachable node.
     */
    public PlacementResult place(IngestedDocument doc) {
        List<String> owners = placement.owners(doc.url().toString());
        boolean selfIsOwner = owners.contains(nodeId);
        List<String> delivered = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        for (String owner : owners) {
            if (owner.equals(nodeId)) {
                continue;
            }
            try {
                // A response of either true or false is a successful delivery —
                // false only means the owner already had the document (idempotent
                // by URL), which is not a failure.
                ingestTransport.ingest(owner, doc).get(OPERATION_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                delivered.add(owner);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                failed.add(owner);
            } catch (java.util.concurrent.ExecutionException | TimeoutException e) {
                failed.add(owner);
            }
        }
        return new PlacementResult(owners, selfIsOwner, delivered, failed);
    }

    /**
     * Runs a search across every live cluster member (including this node)
     * via {@link BroadcastQueryRouter} and {@link
     * DistributedSearchCoordinator}, deduplicating merged results by URL —
     * replication can place the same document on more than one shard that
     * answers the same query, and neither the coordinator nor {@link
     * com.minigoogle.distributed.query.merge.KWayMerger} it delegates to
     * dedupes.
     *
     * @param query The raw query string.
     * @param topK  The number of top results to return.
     * @return The globally ranked, URL-deduplicated Top-K results.
     */
    public List<SearchResult> distributedSearch(String query, int topK) {
        // The coordinator only ever routes its own node ID through the given
        // local executors (see DistributedSearchCoordinator#search), so this
        // node's own shard is served in-process rather than over its own HTTP
        // loopback.
        List<LocalSearchExecutor> localExecutors = localSearch == null ? List.of() : List.of(
                new LocalSearchExecutor(0, (q, k) -> {
                    QueryContext context = new QueryContext(q, k, Duration.ofSeconds(5), UUID.randomUUID());
                    LocalSearchResponse response = localSearch.execute(context);
                    return response.results();
                }));

        BroadcastQueryRouter router = new BroadcastQueryRouter(gossip);
        DistributedSearchCoordinator coordinator = new DistributedSearchCoordinator(
                router, searchTransport, localExecutors, nodeId, 4, Duration.ofSeconds(5), 16);
        try {
            SearchResponse response = coordinator.search(query, topK);
            Map<String, SearchResult> deduplicated = new LinkedHashMap<>();
            for (SearchResult result : response.results()) {
                deduplicated.putIfAbsent(result.url(), result);
            }
            return new ArrayList<>(deduplicated.values());
        } finally {
            coordinator.shutdown();
        }
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
     * Linearly applies {@code entries} as a single atomic transaction: every
     * put commits together or none of them do. Returns only after the
     * transaction entry is committed by a majority and applied. A transaction
     * is one log entry, so it cannot partially apply even if a leader changes
     * mid-commit.
     *
     * @param entries The key/value pairs to write.
     * @throws NotLeaderException If this node is not the leader, or commit is
     *                            not reached before the operation timeout.
     */
    public void putAll(Map<String, byte[]> entries) {
        ensureStateMachine();
        if (entries.isEmpty()) {
            return;
        }
        List<KvCommand.TxnOp> ops = new ArrayList<>(entries.size());
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
            ops.add(new KvCommand.TxnOp(KvCommand.OP_PUT, entry.getKey(), entry.getValue()));
        }
        appendAndWait(KvCommand.encodeTxn(ops));
    }

    /**
     * Linearly deletes {@code keys} as a single atomic transaction: every key
     * is removed together or none of them are.
     *
     * @param keys The keys to delete.
     * @throws NotLeaderException If this node is not the leader, or commit is
     *                            not reached before the operation timeout.
     */
    public void deleteAll(Collection<String> keys) {
        ensureStateMachine();
        if (keys.isEmpty()) {
            return;
        }
        List<KvCommand.TxnOp> ops = new ArrayList<>(keys.size());
        for (String key : keys) {
            ops.add(new KvCommand.TxnOp(KvCommand.OP_DELETE, key, null));
        }
        appendAndWait(KvCommand.encodeTxn(ops));
    }

    /**
     * Linearly reads the value for {@code key}. On the leader, the read is
     * served only after a read-index barrier confirms the node is still the
     * leader for its term, so a partitioned leader cannot return stale state.
     * On a follower, the read is served locally once the leader hands out a
     * linearizable read index and the follower has applied through it — the
     * follower serves from its own committed log instead of redirecting.
     *
     * @return The stored value, or {@code null} if absent.
     * @throws NotLeaderException If no leader is reachable or a linearizable
     *                            read index cannot be established.
     */
    public byte[] get(String key) {
        ensureStateMachine();
        if (raft.getState() == RaftConsensus.RaftState.LEADER) {
            if (!raft.prepareReadBarrier()) {
                throw new NotLeaderException(raft.getCurrentLeader(),
                        "Node " + nodeId + " could not establish a read barrier (no quorum)");
            }
            return kv.get(key);
        }
        return getFromLeaderReadIndex(key);
    }

    /**
     * Serves a follower read: ask the leader for its current commit index
     * (confirmed linearizable by its own read barrier), then wait until this
     * node has applied through that index and read the local state machine.
     * Because the index is quorum-confirmed, reading a committed index locally
     * is as safe as a leader read.
     */
    private byte[] getFromLeaderReadIndex(String key) {
        String leaderId = raft.getCurrentLeader();
        if (leaderId == null) {
            throw new NotLeaderException(null,
                    "Node " + nodeId + " has no known leader to serve a read from");
        }
        ReadIndexResponse response;
        try {
            response = raftTransport.sendReadIndex(leaderId, new ReadIndexRequest(
                    com.minigoogle.cluster.transport.ClusterProtocol.PROTOCOL_VERSION,
                    com.minigoogle.cluster.transport.ClusterProtocol.newId(),
                    com.minigoogle.cluster.transport.ClusterProtocol.newId(),
                    nodeId,
                    com.minigoogle.cluster.transport.ClusterProtocol.now()
            )).get(OPERATION_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new NotLeaderException(leaderId,
                    "Interrupted obtaining a read index from leader " + leaderId, e);
        } catch (java.util.concurrent.ExecutionException | TimeoutException e) {
            throw new NotLeaderException(leaderId,
                    "Failed to obtain a read index from leader " + leaderId, e);
        }
        if (!response.success()) {
            throw new NotLeaderException(leaderId,
                    "Leader " + leaderId + " could not establish a read barrier");
        }
        long deadline = System.currentTimeMillis() + OPERATION_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (raft.getLastApplied() >= response.commitIndex()) {
                return kv.get(key);
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new NotLeaderException(leaderId,
                        "Interrupted waiting to apply read index " + response.commitIndex(), e);
            }
        }
        throw new NotLeaderException(leaderId,
                "Node " + nodeId + " timed out applying to read index " + response.commitIndex());
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
