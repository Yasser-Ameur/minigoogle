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

import java.io.IOException;
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
        this(nodeId, port, directory, 1000, 5000, null);
    }

    public ClusterNode(String nodeId, int port, NodeDirectory directory, long gossipInterval, long gossipTimeout) throws IOException {
        this(nodeId, port, directory, gossipInterval, gossipTimeout, null);
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
        this.nodeId = nodeId;
        ObjectMapper mapper = new ObjectMapper();
        this.server = new InternalClusterServer(port, mapper);

        // Consistent hash ring — automatically updated by gossip via listener
        this.ring = new ConsistentHashRing();
        ring.addNode(nodeId); // Always include self

        HttpMembershipTransport membershipTransport = new HttpMembershipTransport(directory, mapper, nodeId);
        HttpRaftTransport raftTransport = new HttpRaftTransport(directory, mapper, nodeId);
        HttpSearchTransport searchTransport = new HttpSearchTransport(directory, mapper, nodeId);

        this.gossip = new GossipProtocol(nodeId, gossipInterval, gossipTimeout, membershipTransport);
        gossip.addListener(new RingMembershipListener(ring));

        this.raft = new RaftConsensus(nodeId); // Will inject transport later in step 6

        server.getServer().createContext("/cluster/v1/gossip/exchange", new GossipHandler(gossip, mapper, nodeId));
        server.getServer().createContext("/cluster/v1/raft/request-vote", new RaftHandler(raft, mapper, nodeId));
        server.getServer().createContext("/cluster/v1/raft/append-entries", new RaftHandler(raft, mapper, nodeId));
        server.getServer().createContext("/cluster/v1/search/dispatch", new SearchHandler(localSearch, mapper, nodeId));

        this.transports = List.of(membershipTransport, raftTransport, searchTransport);
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
