package com.minigoogle.cluster.routing;

import com.minigoogle.cluster.GossipProtocol;
import com.minigoogle.distributed.query.routing.QueryRouter;

import java.util.List;

/**
 * A routing strategy that simply broadcasts queries to all currently live nodes
 * as determined by the Gossip protocol.
 */
public class BroadcastQueryRouter implements QueryRouter {

    private final GossipProtocol gossip;

    public BroadcastQueryRouter(GossipProtocol gossip) {
        this.gossip = gossip;
    }

    @Override
    public List<String> resolveTargets(String query) {
        // For keyword search: scatter to all live nodes
        return gossip.getLiveNodes();
    }
}
