package com.minigoogle.cluster.transport.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minigoogle.cluster.GossipProtocol;
import com.minigoogle.cluster.GossipProtocol.GossipNodeState;
import com.minigoogle.cluster.GossipProtocol.NodeStatus;
import com.minigoogle.cluster.RaftConsensus;
import com.minigoogle.cluster.transport.NodeDirectory;
import com.minigoogle.cluster.transport.dto.RequestVoteRequest;
import com.minigoogle.monitoring.benchmark.BenchmarkReport;
import com.minigoogle.monitoring.benchmark.BenchmarkRunner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Smoke performance validation for the Phase 1 transport.
 *
 * <p>Measures end-to-end loopback latency (HTTP + Jackson + handler + validation)
 * for the two wired RPCs — gossip exchange and request-vote. Assertions are
 * deliberately generous (100ms avg) so they cannot flake on a loaded CI box,
 * yet they catch gross regressions such as a multi-second stall or a broken
 * response path. Comprehensive throughput/latency benchmarks are deferred to
 * Phases 5/6 when the end-to-end query path exists.
 */
class TransportLatencySmokeTest {

    private static final int WARMUP = 50;
    private static final int ITERATIONS = 500;

    private InternalClusterServer server;
    private HttpMembershipTransport membershipTransport;
    private HttpRaftTransport raftTransport;
    private String peerNodeId;

    @BeforeEach
    void setUp() throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        GossipProtocol gossip = new GossipProtocol("local-node");
        RaftConsensus raft = new RaftConsensus("local-node");

        server = new InternalClusterServer(0, mapper);
        server.getServer().createContext("/cluster/v1/gossip/exchange", new GossipHandler(gossip, mapper, "local-node"));
        server.getServer().createContext("/cluster/v1/raft/request-vote", new RaftHandler(raft, mapper, "local-node"));
        server.start();

        int port = server.getServer().getAddress().getPort();
        NodeDirectory directory = nodeId -> URI.create("http://127.0.0.1:" + port);
        peerNodeId = "peer-node";

        membershipTransport = new HttpMembershipTransport(directory, mapper, "local-node");
        raftTransport = new HttpRaftTransport(directory, mapper, "local-node");
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
        }
    }

    private void gossipExchange() throws Exception {
        Map<String, GossipNodeState> state = Map.of(
                "local-node", new GossipNodeState("local-node", 1, NodeStatus.ALIVE, System.currentTimeMillis()));
        membershipTransport.exchangeState(peerNodeId, state).get(5, TimeUnit.SECONDS);
    }

    private void requestVote() throws Exception {
        RequestVoteRequest request = new RequestVoteRequest(
                1, "req", "corr", "local-node", 0L, "local-node", 3, 0, 0);
        raftTransport.sendRequestVote(peerNodeId, request).get(5, TimeUnit.SECONDS);
    }

    @Test
    void testGossipExchangeLatency() throws Exception {
        for (int i = 0; i < WARMUP; i++) {
            gossipExchange();
        }

        BenchmarkReport report = new BenchmarkRunner("gossip-exchange", ITERATIONS)
                .run(() -> {
                    try {
                        gossipExchange();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });

        System.out.println(report.summary());
        assertEquals(ITERATIONS, report.iterations());
        assertTrue(report.averageLatencyMs() < 100,
                "Gossip exchange avg latency " + report.averageLatencyMs() + "ms exceeds sanity bound");
        assertTrue(report.p99LatencyMs() < 500,
                "Gossip exchange p99 latency " + report.p99LatencyMs() + "ms exceeds sanity bound");
    }

    @Test
    void testRequestVoteLatency() throws Exception {
        for (int i = 0; i < WARMUP; i++) {
            requestVote();
        }

        BenchmarkReport report = new BenchmarkRunner("request-vote", ITERATIONS)
                .run(() -> {
                    try {
                        requestVote();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });

        System.out.println(report.summary());
        assertEquals(ITERATIONS, report.iterations());
        assertTrue(report.averageLatencyMs() < 100,
                "Request-vote avg latency " + report.averageLatencyMs() + "ms exceeds sanity bound");
        assertTrue(report.p99LatencyMs() < 500,
                "Request-vote p99 latency " + report.p99LatencyMs() + "ms exceeds sanity bound");
    }
}
