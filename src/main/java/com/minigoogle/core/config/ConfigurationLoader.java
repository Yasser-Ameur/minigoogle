package com.minigoogle.core.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public final class ConfigurationLoader {

    private static final Logger logger = LoggerFactory.getLogger(ConfigurationLoader.class);

    private ConfigurationLoader() {}

    public static Configuration load(String filePath) {
        Path path = Path.of(filePath);
        if (!Files.exists(path)) {
            logger.warn("Configuration file not found: {}, using defaults", filePath);
            return withDefaults();
        }
        try {
            Map<String, String> fileProps = parseSimpleYaml(Files.readString(path));
            Configuration fileConfig = new Configuration(fileProps);
            Configuration envConfig = fromEnvironmentVariables();
            Configuration defaults = withDefaults();
            return merge(merge(defaults, fileConfig), envConfig);
        } catch (IOException e) {
            logger.error("Failed to load configuration from {}: {}", filePath, e.getMessage());
            return withDefaults();
        }
    }

    public static Configuration fromEnvironmentVariables() {
        Map<String, String> props = new HashMap<>();
        putIfEnv(props, "MINIGOGLE_NODE_TYPE", "node.type");
        putIfEnv(props, "MINIGOGLE_NODE_PORT", "server.port");
        putIfEnv(props, "MINIGOGLE_NODE_HOST", "server.host");
        putIfEnv(props, "MINIGOGLE_CLUSTER_PEERS", "cluster.peers");
        putIfEnv(props, "MINIGOGLE_REPLICATION_FACTOR", "cluster.replicationFactor");
        putIfEnv(props, "MINIGOGLE_LOG_LEVEL", "logging.level");
        putIfEnv(props, "NODE_TYPE", "node.type");
        putIfEnv(props, "NODE_PORT", "server.port");
        return new Configuration(props);
    }

    public static Configuration withDefaults() {
        Map<String, String> defaults = new HashMap<>();
        defaults.put("server.port", "8080");
        defaults.put("server.host", "0.0.0.0");
        defaults.put("cluster.replicationFactor", "3");
        defaults.put("cluster.nodeTimeout", "30000");
        defaults.put("cluster.gossipInterval", "1000");
        defaults.put("crawler.workers", "32");
        defaults.put("crawler.maxDepth", "5");
        defaults.put("crawler.politenessDelay", "1000");
        defaults.put("search.topK", "20");
        defaults.put("search.maxResults", "100");
        defaults.put("search.timeoutMs", "5000");
        defaults.put("indexing.segmentSize", "10000");
        defaults.put("indexing.compactionThreshold", "5");
        defaults.put("logging.level", "INFO");
        defaults.put("logging.format", "json");
        return new Configuration(defaults);
    }

    private static void putIfEnv(Map<String, String> props, String envKey, String propKey) {
        String val = System.getenv(envKey);
        if (val != null && !val.isBlank()) {
            props.put(propKey, val);
        }
    }

    private static Configuration merge(Configuration primary, Configuration fallback) {
        Map<String, String> merged = new HashMap<>(fallback.toMap());
        merged.putAll(primary.toMap());
        return new Configuration(merged);
    }

    static Map<String, String> parseSimpleYaml(String yaml) {
        Map<String, String> result = new HashMap<>();
        String currentSection = null;
        for (String line : yaml.split("\n")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;

            int indent = line.length() - line.stripLeading().length();
            String stripped = trimmed;

            if (indent == 0 && stripped.endsWith(":")) {
                currentSection = stripped.substring(0, stripped.length() - 1).strip();
            } else if (indent > 0 && stripped.contains(":")) {
                int colonIdx = stripped.indexOf(':');
                String key = stripped.substring(0, colonIdx).strip();
                String value = stripped.substring(colonIdx + 1).strip();
                if (currentSection != null) {
                    key = currentSection + "." + key;
                }
                if (!value.isEmpty()) {
                    if ((value.startsWith("\"") && value.endsWith("\"")) ||
                        (value.startsWith("'") && value.endsWith("'"))) {
                        value = value.substring(1, value.length() - 1);
                    }
                    result.put(key, value);
                }
            }
        }
        return result;
    }
}
