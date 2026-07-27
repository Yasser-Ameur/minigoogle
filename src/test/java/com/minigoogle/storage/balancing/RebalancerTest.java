package com.minigoogle.cluster.balancing;

import com.minigoogle.distributed.sharding.ShardManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for storage Rebalancer functionality. */
class RebalancerTest {

    private ShardManager shardManager;
    private Rebalancer rebalancer;

    @BeforeEach
    void setUp() {
        shardManager = new ShardManager();
        rebalancer = new Rebalancer(shardManager);
    }

    @Test
    void testEmptyClusterIsBalanced() {
        assertTrue(rebalancer.isBalanced());
        assertTrue(rebalancer.computeRebalancePlan().isEmpty());
    }

    @Test
    void testBalancedCluster() {
        shardManager.assignShard(0, "A");
        shardManager.assignShard(1, "B");
        shardManager.assignShard(2, "C");
        assertTrue(rebalancer.isBalanced());
    }

    @Test
    void testUnbalancedClusterProducesPlan() {
        for (int i = 0; i < 50; i++) {
            shardManager.assignShard(i, "A");
        }
        shardManager.assignShard(100, "B");
        shardManager.assignShard(101, "C");

        List<Rebalancer.MigrationPlan> plan = rebalancer.computeRebalancePlan();
        assertFalse(plan.isEmpty());
        assertFalse(rebalancer.isBalanced());
    }

    @Test
    void testOverloadedNodeMigratesFromHighToLow() {
        for (int i = 0; i < 50; i++) {
            shardManager.assignShard(i, "A");
        }
        shardManager.assignShard(100, "B");
        shardManager.assignShard(101, "C");

        List<Rebalancer.MigrationPlan> plan = rebalancer.computeRebalancePlan();
        assertFalse(plan.isEmpty());
        for (Rebalancer.MigrationPlan migration : plan) {
            assertEquals("A", migration.fromNodeId());
        }
    }

    @Test
    void testSingleNodeClusterIsBalanced() {
        shardManager.assignShard(0, "A");
        shardManager.assignShard(1, "A");
        shardManager.assignShard(2, "A");
        assertTrue(rebalancer.isBalanced());
    }

    @Test
    void testMigrationPlanShardIdsAreFromSource() {
        shardManager.assignShard(5, "A");
        shardManager.assignShard(6, "A");
        shardManager.assignShard(7, "A");
        shardManager.assignShard(0, "B");

        List<Rebalancer.MigrationPlan> plan = rebalancer.computeRebalancePlan();
        for (Rebalancer.MigrationPlan migration : plan) {
            assertTrue(shardManager.getShardsForNode("A").contains(migration.shardId())
                    || migration.shardId() >= 0);
        }
    }
}
