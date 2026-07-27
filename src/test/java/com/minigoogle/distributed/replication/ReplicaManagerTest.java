package com.minigoogle.distributed.replication;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for ReplicaManager functionality. */
class ReplicaManagerTest {

    @Test
    void testAssignAndQuery() {
        ReplicaManager manager = new ReplicaManager();
        manager.assignReplicas(1, "node-A", List.of("node-B", "node-C"));

        assertEquals("node-A", manager.getPrimary(1));
        assertEquals(List.of("node-B", "node-C"), manager.getReplicas(1));
    }

    @Test
    void testPromoteReplica() {
        ReplicaManager manager = new ReplicaManager();
        manager.assignReplicas(1, "node-A", List.of("node-B", "node-C"));

        assertTrue(manager.promoteReplica(1));
        assertEquals("node-B", manager.getPrimary(1));
        assertEquals(List.of("node-C"), manager.getReplicas(1));
    }

    @Test
    void testPromoteWithNoReplicas() {
        ReplicaManager manager = new ReplicaManager();
        manager.assignReplicas(1, "node-A", List.of());

        assertFalse(manager.promoteReplica(1));
    }

    @Test
    void testRemoveNode() {
        ReplicaManager manager = new ReplicaManager();
        manager.assignReplicas(1, "node-A", List.of("node-B", "node-C"));

        List<Integer> affected = manager.removeNode("node-A");
        assertEquals(1, affected.size());
        assertEquals("node-B", manager.getPrimary(1));
    }

    @Test
    void testRemoveReplica() {
        ReplicaManager manager = new ReplicaManager();
        manager.assignReplicas(1, "node-A", List.of("node-B", "node-C"));

        manager.removeNode("node-B");
        assertEquals("node-A", manager.getPrimary(1));
        assertEquals(List.of("node-C"), manager.getReplicas(1));
    }

    @Test
    void testGetUnassignedShard() {
        ReplicaManager manager = new ReplicaManager();
        assertNull(manager.getPrimary(999));
        assertTrue(manager.getReplicas(999).isEmpty());
    }

    @Test
    void testSize() {
        ReplicaManager manager = new ReplicaManager();
        manager.assignReplicas(1, "A", List.of());
        manager.assignReplicas(2, "B", List.of());
        assertEquals(2, manager.size());
    }
}
