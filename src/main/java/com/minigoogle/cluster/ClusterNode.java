package com.minigoogle.cluster;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import com.minigoogle.storage.metadata.RaftMetadataStore;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

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
    private final String nodeId;
    private final InternalClusterServer server;
    private final GossipProtocol gossip;
    private final RaftConsensus raft;
    private final ConsistentHashRing ring;
    private final List<ClusterTransport> transports;

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
                security, createRaftMetadataStore(storageDirectory));
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
        this.nodeId = nodeId;
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
                raftMetadataStore == null ? RaftMetadataStore.inMemory() : raftMetadataStore);

        server.registerProtectedContext("/cluster/v1/gossip/exchange", new GossipHandler(gossip, mapper, nodeId), security);
        server.registerProtectedContext("/cluster/v1/raft/request-vote", new RaftHandler(raft, mapper, nodeId), security);
        server.registerProtectedContext("/cluster/v1/raft/append-entries", new RaftHandler(raft, mapper, nodeId), security);
        server.registerProtectedContext("/cluster/v1/search/dispatch", new SearchHandler(localSearch, mapper, nodeId), security);

        this.transports = List.of(membershipTransport, raftTransport, searchTransport);
    }

    private static RaftMetadataStore createRaftMetadataStore(Path storageDirectory) {
        if (storageDirectory == null) {
            return RaftMetadataStore.inMemory();
        }
        return new RaftMetadataStore(new StorageLayout(storageDirectory).getRaftMetadataPath());
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
}
