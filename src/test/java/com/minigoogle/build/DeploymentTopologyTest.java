package com.minigoogle.build;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the deployment topology so a cluster cannot silently fail to form.
 *
 * <p>Both classes of defect this guards against were live in this repository
 * and were invisible at build time: Kubernetes Services selected {@code app:
 * minigoogle} while the workloads labelled their pods
 * {@code app.kubernetes.io/name}, so every Service resolved to zero endpoints;
 * and Docker Compose set a bare {@code CLUSTER_PEERS} variable that the
 * application never read, so each container started as an isolated single
 * node. Nothing failed loudly in either case — the cluster simply did not
 * exist.</p>
 *
 * <p>These are text-level checks on the manifests, not a substitute for
 * actually deploying. They catch the specific silent mismatches above.</p>
 */
class DeploymentTopologyTest {

    private static final Path ROOT = Paths.get(System.getProperty("user.dir"));
    private static final Path K8S = ROOT.resolve("k8s");

    private static final Pattern SELECTOR_BLOCK = Pattern.compile(
            "(?m)^  selector:\\n((?:    [^\\n]*\\n)+)");
    private static final Pattern LABEL_LINE = Pattern.compile("(?m)^\\s{4,}([\\w./-]+):\\s*(\\S+)\\s*$");

    /** Every label a workload manifest applies to its pods. */
    private static Set<String> podLabelsAcrossWorkloads() throws IOException {
        Set<String> labels = new LinkedHashSet<>();
        for (Path manifest : manifests()) {
            String text = Files.readString(manifest);
            if (!text.contains("kind: Deployment") && !text.contains("kind: StatefulSet")) {
                continue;
            }
            // Pod labels live under spec.template.metadata.labels.
            int templateIdx = text.indexOf("  template:");
            if (templateIdx < 0) {
                continue;
            }
            String afterTemplate = text.substring(templateIdx);
            int labelsIdx = afterTemplate.indexOf("labels:");
            if (labelsIdx < 0) {
                continue;
            }
            String labelBlock = afterTemplate.substring(labelsIdx);
            int specIdx = labelBlock.indexOf("spec:");
            if (specIdx > 0) {
                labelBlock = labelBlock.substring(0, specIdx);
            }
            Matcher m = LABEL_LINE.matcher(labelBlock);
            while (m.find()) {
                labels.add(m.group(1) + "=" + m.group(2));
            }
        }
        return labels;
    }

    private static List<Path> manifests() throws IOException {
        List<Path> files = new ArrayList<>();
        if (!Files.isDirectory(K8S)) {
            return files;
        }
        try (Stream<Path> walk = Files.walk(K8S)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".yaml") || p.toString().endsWith(".yml"))
                    .forEach(files::add);
        }
        return files;
    }

    @Test
    void everyServiceSelectorMatchesSomeWorkloadsPodLabels() throws IOException {
        Set<String> podLabels = podLabelsAcrossWorkloads();
        assertFalse(podLabels.isEmpty(), "expected at least one Deployment/StatefulSet with pod labels");

        List<String> unmatched = new ArrayList<>();
        for (Path manifest : manifests()) {
            String text = Files.readString(manifest);
            if (!text.contains("kind: Service")) {
                continue;
            }
            Matcher block = SELECTOR_BLOCK.matcher(text);
            while (block.find()) {
                Matcher label = LABEL_LINE.matcher(block.group(1));
                while (label.find()) {
                    String pair = label.group(1) + "=" + label.group(2);
                    if (!podLabels.contains(pair)) {
                        unmatched.add(K8S.relativize(manifest) + " selects '" + pair
                                + "', which no pod template applies");
                    }
                }
            }
        }
        assertTrue(unmatched.isEmpty(),
                "Service selectors that match no pod resolve to zero endpoints:\n"
                        + String.join("\n", unmatched));
    }

    @Test
    void raftWorkloadIsAStatefulSetWithPersistentStorage() throws IOException {
        Path cluster = K8S.resolve("statefulset-cluster.yaml");
        assertTrue(Files.exists(cluster), "expected a StatefulSet manifest for the Raft cluster");
        String text = Files.readString(cluster);

        assertTrue(text.contains("kind: StatefulSet"),
                "Raft needs stable identity; a Deployment cannot provide it");
        assertTrue(text.contains("volumeClaimTemplates"),
                "each Raft member needs durable per-pod storage for its term, log and config");
        assertTrue(text.contains("clusterIP: None"),
                "a headless Service is required for stable per-pod DNS names");
        assertTrue(text.contains("NODE_TYPE") && text.contains("CLUSTER"),
                "the cluster workload must start the app in CLUSTER mode");
    }

    @Test
    void composeStartsClusterNodesWithAPeerListTheApplicationReads() throws IOException {
        String compose = Files.readString(ROOT.resolve("docker-compose.yml"));

        assertTrue(compose.contains("NODE_TYPE: CLUSTER") || compose.contains("NODE_TYPE=CLUSTER"),
                "compose must start nodes in CLUSTER mode; unrecognized values silently "
                        + "fall back to a standalone single node");
        assertTrue(compose.contains("CLUSTER_PEERS"), "compose must supply a peer list");

        // Every peer entry must carry an explicit port, or StaticNodeDirectory
        // rejects it at startup.
        Matcher peers = Pattern.compile("CLUSTER_PEERS:\\s*\"?([^\"\\n]+)\"?").matcher(compose);
        assertTrue(peers.find(), "expected a CLUSTER_PEERS value in docker-compose.yml");
        for (String entry : peers.group(1).split(",")) {
            String trimmed = entry.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            assertTrue(trimmed.matches(".*:\\d+$"),
                    "peer entry '" + trimmed + "' must end in an explicit port");
        }
    }

    @Test
    void everyEnvironmentVariableTheDeploymentSetsIsActuallyRead() throws IOException {
        // The original compose set CLUSTER_PEERS while the loader mapped only
        // MINIGOGLE_CLUSTER_PEERS, so the peer list was silently discarded.
        String loader = Files.readString(ROOT.resolve(
                "src/main/java/com/minigoogle/core/config/ConfigurationLoader.java"));
        String compose = Files.readString(ROOT.resolve("docker-compose.yml"));

        List<String> unread = new ArrayList<>();
        Matcher env = Pattern.compile("(?m)^\\s+([A-Z][A-Z0-9_]{2,}):\\s").matcher(compose);
        while (env.find()) {
            String name = env.group(1);
            if (!loader.contains("\"" + name + "\"")) {
                unread.add(name);
            }
        }
        assertTrue(unread.isEmpty(),
                "docker-compose sets variables the application never reads: " + unread);
    }
}
