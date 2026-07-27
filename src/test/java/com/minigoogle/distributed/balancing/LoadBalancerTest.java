package com.minigoogle.distributed.balancing;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for LoadBalancer functionality. */
class LoadBalancerTest {

    @Test
    void testRoundRobin() {
        LoadBalancer balancer = new LoadBalancer();
        List<String> nodes = List.of("node1", "node2", "node3");

        assertEquals("node1", balancer.nextNode(nodes));
        assertEquals("node2", balancer.nextNode(nodes));
        assertEquals("node3", balancer.nextNode(nodes));
        
        // Wraps around
        assertEquals("node1", balancer.nextNode(nodes));
    }

    @Test
    void testEmptyList() {
        LoadBalancer balancer = new LoadBalancer();
        assertNull(balancer.nextNode(List.of()));
        assertNull(balancer.nextNode(null));
    }
}
