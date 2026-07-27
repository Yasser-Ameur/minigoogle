package com.minigoogle.cluster.balancing;

import com.minigoogle.distributed.sharding.ShardManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class Rebalancer {

    private final ShardManager shardManager;

    public Rebalancer(ShardManager shardManager) {
        this.shardManager = shardManager;
    }

    public List<MigrationPlan> computeRebalancePlan() {
        List<MigrationPlan> plans = new ArrayList<>();

        Map<Integer, Set<String>> shardToNodes = getShardAssignments();
        Map<String, Integer> nodeLoad = getNodeLoad();

        if (nodeLoad.isEmpty()) {
            return plans;
        }

        int totalShards = 0;
        for (int load : nodeLoad.values()) {
            totalShards += load;
        }
        double averageLoad = (double) totalShards / nodeLoad.size();

        List<String> overloaded = new ArrayList<>();
        List<String> underloaded = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : nodeLoad.entrySet()) {
            if (entry.getValue() > averageLoad * 2) {
                overloaded.add(entry.getKey());
            } else if (entry.getValue() < averageLoad) {
                underloaded.add(entry.getKey());
            }
        }

        overloaded.sort((a, b) -> Integer.compare(nodeLoad.get(b), nodeLoad.get(a)));
        underloaded.sort((a, b) -> Integer.compare(nodeLoad.get(a), nodeLoad.get(b)));

        for (String source : overloaded) {
            Set<Integer> sourceShards = shardManager.getShardsForNode(source);
            List<Integer> movable = new ArrayList<>(sourceShards);

            while (!movable.isEmpty() && !underloaded.isEmpty()) {
                String target = underloaded.get(0);
                int targetLoad = nodeLoad.getOrDefault(target, 0);
                double targetAvg = averageLoad;

                if (targetLoad >= targetAvg) {
                    underloaded.remove(0);
                    if (underloaded.isEmpty()) break;
                    continue;
                }

                int shardId = movable.remove(movable.size() - 1);
                plans.add(new MigrationPlan(shardId, source, target));

                nodeLoad.put(source, nodeLoad.get(source) - 1);
                nodeLoad.put(target, nodeLoad.getOrDefault(target, 0) + 1);
            }
        }

        return Collections.unmodifiableList(plans);
    }

    public boolean isBalanced() {
        return computeRebalancePlan().isEmpty();
    }

    private Map<Integer, Set<String>> getShardAssignments() {
        Map<Integer, Set<String>> result = new java.util.HashMap<>();
        for (int shardId : getAllShardIds()) {
            result.put(shardId, shardManager.getNodesForShard(shardId));
        }
        return result;
    }

    private Map<String, Integer> getNodeLoad() {
        Map<String, Integer> load = new java.util.HashMap<>();
        for (int shardId : getAllShardIds()) {
            for (String nodeId : shardManager.getNodesForShard(shardId)) {
                load.merge(nodeId, 1, Integer::sum);
            }
        }
        return load;
    }

    private java.util.Set<Integer> getAllShardIds() {
        return shardManager.getAllShardIds();
    }

    public record MigrationPlan(int shardId, String fromNodeId, String toNodeId) {
    }
}
