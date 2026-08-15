package com.minigoogle.demo;

import com.minigoogle.crawler.downloader.HttpDownloader;
import com.minigoogle.crawler.model.DownloadedPage;
import com.minigoogle.crawler.model.ParsedDocument;
import com.minigoogle.crawler.model.UrlTask;
import com.minigoogle.crawler.parser.JSoupHtmlParser;
import com.minigoogle.network.http.RestServer;
import com.minigoogle.indexer.model.IndexedDocument;
import com.minigoogle.network.dto.SearchRequest;
import com.minigoogle.network.dto.SearchResponse;
import com.minigoogle.network.serialization.JsonSerializer;
import com.minigoogle.ranking.model.RankedDocument;
import com.minigoogle.ranking.pipeline.GlobalRankingPipeline;
import com.minigoogle.ranking.pipeline.RankedCandidate;
import com.minigoogle.ranking.pipeline.RankedResult;
import com.minigoogle.semantic.autocomplete.TrieAutocomplete;
import com.minigoogle.semantic.knowledge.EntityExtractor;
import com.minigoogle.semantic.knowledge.KnowledgeGraph;
import com.minigoogle.semantic.spell.SpellCorrector;
import com.minigoogle.storage.metadata.Metadata;
import com.minigoogle.storage.mmap.MemoryMappedIndex;
import com.minigoogle.search.SearchEngine;
import com.minigoogle.search.SearchEngineBuild;
import com.minigoogle.search.SearchEngineBuilder;
import com.minigoogle.search.RetrievalResult;
import com.minigoogle.core.cache.LRUCache;
import com.minigoogle.core.concurrent.ConcurrentIndex;
import com.minigoogle.core.config.Configuration;
import com.minigoogle.core.config.ConfigurationLoader;
import com.minigoogle.core.event.EventBus;
import com.minigoogle.core.event.QueryExecutedEvent;
import com.minigoogle.cluster.ClusterNode;
import com.minigoogle.cluster.ClusterSecurity;
import com.minigoogle.cluster.NotLeaderException;
import com.minigoogle.cluster.RaftConsensus;
import com.minigoogle.cluster.transport.StaticNodeDirectory;
import com.minigoogle.distributed.coordinator.ClusterCoordinator;
import com.minigoogle.distributed.coordinator.SearchCoordinator;
import com.minigoogle.distributed.query.execution.LocalSearchExecutor;
import com.minigoogle.distributed.query.execution.SearchExecutor;
import com.minigoogle.distributed.heartbeat.HeartbeatManager;
import com.minigoogle.distributed.model.NodeInfo;
import com.minigoogle.distributed.model.NodeRole;
import com.minigoogle.distributed.model.NodeStatus;
import com.minigoogle.ml.click.ClickEvent;
import com.minigoogle.ml.click.ClickFeedbackTrainer;
import com.minigoogle.ml.click.ClickTracker;
import com.minigoogle.ml.features.FeatureExtractor;
import com.minigoogle.ml.features.FeatureName;
import com.minigoogle.ml.features.NormalizationContext;
import com.minigoogle.ml.ltr.LinearRankingModel;
import com.minigoogle.monitoring.analytics.QueryAnalytics;
import com.minigoogle.network.dto.ClickRequest;
import com.minigoogle.network.http.RestClient;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Main application entry point that wires the full search pipeline:
 * indexing, ranking, autocomplete, spell correction, query expansion,
 * caching, analytics, and the REST API.
 */
public class MiniGoogleApp {

    private static final int DEFAULT_PORT = 8080;
    private static final String DEFAULT_INDEX_DIR = "demo-index";

    private final List<ParsedDocument> allDocs = new ArrayList<>();
    private Configuration config;

    private final QueryAnalytics analytics = new QueryAnalytics();

    private Path indexPath;
    private final LRUCache<String, List<com.minigoogle.network.dto.SearchResult>> queryCache = new LRUCache<>(200);
    private final EventBus eventBus = new EventBus();

    /**
     * Ref-counted snapshot of the full index pipeline, published atomically so
     * concurrent REST handler threads always observe either the complete
     * previous index or the complete new index. The previous generation is
     * closed (memory-mapped postings released, build directory removed) only
     * once no handler still holds a lease on it, so an active search never sees
     * a closed index and a reindex can run while other requests are in flight.
     */
    private final ConcurrentIndex<IndexState> currentIndex = new ConcurrentIndex<>();
    private final Object indexLock = new Object();

    /** Monotonically increasing build counter used to name versioned build directories. */
    private final java.util.concurrent.atomic.AtomicLong buildSeq = new java.util.concurrent.atomic.AtomicLong();

    /**
     * The consensus runtime, non-null only when {@code node.type=CLUSTER}. It
     * owns gossip, Raft, the hash ring and the internal RPC server.
     */
    private ClusterNode clusterNode;
    private String clusterNodeId;

    public void start() throws Exception {
        printBanner();

        config = ConfigurationLoader.load("config/application.yaml");
        int port = config.getInt("server.port", DEFAULT_PORT);
        String indexDir = config.get("indexing.indexDir", DEFAULT_INDEX_DIR);
        String nodeType = config.get("node.type", "STANDALONE").trim().toUpperCase(Locale.ROOT);

        if ("COORDINATOR".equals(nodeType)) {
            startCoordinatorNode(port);
            return;
        }

        eventBus.subscribe(QueryExecutedEvent.class,
            e -> analytics.recordQuery(e.query(), e.resultCount(), e.durationMs()));

        indexPath = Path.of(indexDir);
        Files.createDirectories(indexPath);

        // Phase 1: Index demo documents
        System.out.print("Indexing documents... ");
        allDocs.addAll(DemoDocuments.all());
        reindex();
        System.out.println("done (" + allDocs.size() + " documents)");

        // Phase 2: Start server
        System.out.println("Starting server on http://localhost:" + port);
        System.out.println("============================================================");

        RestServer server = new RestServer(port);

        String html = loadResource("/demo/index.html");
        server.getHtml("/", req -> html);

        server.post("/api/v1/search", body -> {
            try {
                SearchRequest request = JsonSerializer.fromJson(body, SearchRequest.class);
                if (request == null || request.query() == null || request.query().trim().isEmpty()) {
                    return JsonSerializer.toJson(new SearchResponse(0, 0, List.of()));
                }
                long start = System.currentTimeMillis();
                int topK = config.getInt("search.topK", 20);
                int pageSize = request.pageSize() > 0 ? request.pageSize() : topK;
                // A SEARCH-mode node returns its candidate set with raw
                // features for coordinator-side global ranking; a standalone
                // node returns fully ranked results.
                SearchResponse response = "SEARCH".equals(nodeType)
                        ? gatherCandidateResults(request.query().trim(), pageSize)
                        : executeSearch(request.query().trim(), pageSize);
                long elapsed = System.currentTimeMillis() - start;
                return JsonSerializer.toJson(new SearchResponse(elapsed, response.totalResults(),
                        response.results(), response.didYouMean(),
                        response.maxPageRank(), response.maxDocLength()));
            } catch (Exception e) {
                return "{\"error\":\"" + e.getMessage().replace("\"", "'") + "\"}";
            }
        });

        server.getWithContentType("/api/v1/health", "application/json", req -> "{\"status\":\"ok\"}");

        // Autocomplete with spell correction fallback
        server.getWithContentType("/api/v1/suggest", "application/json", req -> {
            try (ConcurrentIndex.Lease<IndexState> lease = currentIndex.lease()) {
                IndexState state = lease.value();
                if (state == null) {
                    return "[]";
                }
                String prefix = req.replaceAll("^.*[?&]q=", "").replaceAll("&.*$", "");
                prefix = java.net.URLDecoder.decode(prefix, StandardCharsets.UTF_8).trim().toLowerCase();
                List<String> suggestions = state.autocomplete().autocomplete(prefix, 8);
                if (suggestions.isEmpty() && !prefix.isEmpty()) {
                    String corrected = state.spellCorrector().correct(prefix);
                    if (!corrected.equals(prefix)) {
                        suggestions = state.autocomplete().autocomplete(corrected, 8);
                    }
                }
                StringBuilder sb = new StringBuilder("[");
                for (int i = 0; i < suggestions.size(); i++) {
                    if (i > 0) sb.append(",");
                    sb.append("\"").append(suggestions.get(i).replace("\"", "\\\"")).append("\"");
                }
                sb.append("]");
                return sb.toString();
            } catch (Exception e) {
                return "[]";
            }
        });

        // Index statistics
        server.getWithContentType("/api/v1/stats", "application/json", req -> {
            try (ConcurrentIndex.Lease<IndexState> lease = currentIndex.lease()) {
                IndexState state = lease.value();
                if (state == null) {
                    return "{\"documentCount\":0,\"vocabularySize\":0,\"averageDocumentLength\":0,\"version\":\"\"}";
                }
                return "{" +
                    "\"documentCount\":" + state.metadata().documentCount() + "," +
                    "\"vocabularySize\":" + state.metadata().vocabularySize() + "," +
                    "\"averageDocumentLength\":" + state.metadata().averageDocumentLength() + "," +
                    "\"version\":\"" + state.metadata().version() + "\"" +
                    "}";
            } catch (Exception e) {
                return "{\"error\":\"" + e.getMessage() + "\"}";
            }
        });

        // Query analytics
        server.getWithContentType("/api/v1/analytics", "application/json", req -> {
            try {
                var top = analytics.getTopQueries(5);
                StringBuilder topJson = new StringBuilder("[");
                for (int i = 0; i < top.size(); i++) {
                    if (i > 0) topJson.append(",");
                    topJson.append("{\"query\":\"").append(top.get(i).getKey().replace("\"", "\\\""))
                           .append("\",\"count\":").append(top.get(i).getValue()).append("}");
                }
                topJson.append("]");
                return "{" +
                    "\"totalQueries\":" + analytics.getTotalQueries() + "," +
                    "\"averageLatencyMs\":" + analytics.getAverageLatencyMs() + "," +
                    "\"zeroResultRate\":" + analytics.getZeroResultRate() + "," +
                    "\"uniqueQueryCount\":" + analytics.uniqueQueryCount() + "," +
                    "\"topQueries\":" + topJson +
                    "}";
            } catch (Exception e) {
                return "{\"error\":\"" + e.getMessage() + "\"}";
            }
        });

        // Click feedback: record a user click for learning-to-rank training.
        // The frontend reports the clicked URL (no documentId), so the document
        // is resolved from the local corpus when documentId is absent.
        server.post("/api/v1/click", body -> {
            try (ConcurrentIndex.Lease<IndexState> lease = currentIndex.lease()) {
                ClickRequest request = JsonSerializer.fromJson(body, ClickRequest.class);
                if (request == null || request.query() == null || request.query().trim().isEmpty()) {
                    return "{\"success\":false,\"error\":\"Missing query\"}";
                }
                IndexState state = lease.value();
                if (state == null) {
                    return "{\"success\":false,\"error\":\"No index available\"}";
                }
                int documentId = request.documentId();
                String url = request.url() != null ? request.url().trim() : "";
                if (documentId <= 0 && !url.isEmpty()) {
                    Integer resolved = state.urlToDocId().get(url);
                    if (resolved != null) {
                        documentId = resolved;
                    }
                }
                if (documentId <= 0) {
                    return "{\"success\":false,\"error\":\"Click does not match a local document\"}";
                }
                if (url.isEmpty()) {
                    url = state.docUrls().getOrDefault(documentId, "");
                }
                int position = request.position() > 0 ? request.position() : 1;
                ClickEvent event = new ClickEvent(request.query().trim(), documentId,
                        url, position, null, request.sessionId());
                int trainedPairs = 0;
                if (state.clickFeedbackTrainer() != null) {
                    trainedPairs = state.clickFeedbackTrainer().onClick(event);
                } else if (state.clickTracker() != null) {
                    state.clickTracker().recordClick(event);
                }
                return "{\"success\":true,\"documentId\":" + documentId
                        + ",\"position\":" + position + ",\"trainedPairs\":" + trainedPairs + "}";
            } catch (Exception e) {
                return "{\"success\":false,\"error\":\"" + e.getMessage().replace("\"", "'") + "\"}";
            }
        });

        // Learning-to-rank model and click statistics
        server.getWithContentType("/api/v1/ml/stats", "application/json", req -> {
            try (ConcurrentIndex.Lease<IndexState> lease = currentIndex.lease()) {
                IndexState state = lease.value();
                FeatureName[] names = FeatureName.values();
                StringBuilder featuresJson = new StringBuilder("[");
                for (int i = 0; i < names.length; i++) {
                    if (i > 0) featuresJson.append(",");
                    featuresJson.append("\"").append(names[i].name()).append("\"");
                }
                featuresJson.append("]");
                double[] weights = state != null && state.rankingModel() != null ? state.rankingModel().weights() : new double[0];
                StringBuilder weightsJson = new StringBuilder("[");
                for (int i = 0; i < weights.length; i++) {
                    if (i > 0) weightsJson.append(",");
                    weightsJson.append(weights[i]);
                }
                weightsJson.append("]");
                return "{" +
                    "\"ltrEnabled\":" + (state != null && state.rankingModel() != null) + "," +
                    "\"features\":" + featuresJson + "," +
                    "\"weights\":" + weightsJson + "," +
                    "\"clicks\":" + (state != null && state.clickTracker() != null ? state.clickTracker().clickCount() : 0) + "," +
                    "\"impressions\":" + (state != null && state.clickTracker() != null ? state.clickTracker().impressionCount() : 0) +
                    "}";
            } catch (Exception e) {
                return "{\"error\":\"" + e.getMessage() + "\"}";
            }
        });

        // Knowledge graph entities
        server.getWithContentType("/api/v1/entities", "application/json", req -> {
            try (ConcurrentIndex.Lease<IndexState> lease = currentIndex.lease()) {
                IndexState state = lease.value();
                String q = req.replaceAll("^.*[?&]q=", "").replaceAll("&.*$", "");
                q = java.net.URLDecoder.decode(q, StandardCharsets.UTF_8).trim();
                KnowledgeGraph knowledgeGraph = state != null ? state.knowledgeGraph() : null;
                if (knowledgeGraph == null) {
                    return "[]";
                }
                List<Map.Entry<String, Integer>> rankedEntities = new ArrayList<>();
                if (q.isEmpty()) {
                    for (String entity : knowledgeGraph.entities()) {
                        rankedEntities.add(Map.entry(entity, knowledgeGraph.documentCount(entity)));
                    }
                    rankedEntities.sort(Map.Entry.<String, Integer>comparingByValue().reversed()
                            .thenComparing(Map.Entry.comparingByKey()));
                    if (rankedEntities.size() > 20) {
                        rankedEntities = rankedEntities.subList(0, 20);
                    }
                } else {
                    String lower = q.toLowerCase(Locale.ROOT);
                    for (String entity : knowledgeGraph.entities()) {
                        if (entity.toLowerCase(Locale.ROOT).contains(lower)) {
                            rankedEntities.add(Map.entry(entity, knowledgeGraph.documentCount(entity)));
                        }
                    }
                    rankedEntities.sort(Map.Entry.<String, Integer>comparingByValue().reversed()
                            .thenComparing(Map.Entry.comparingByKey()));
                    if (rankedEntities.size() > 5) {
                        rankedEntities = rankedEntities.subList(0, 5);
                    }
                }
                StringBuilder sb = new StringBuilder("[");
                for (int i = 0; i < rankedEntities.size(); i++) {
                    if (i > 0) sb.append(",");
                    String entity = rankedEntities.get(i).getKey().replace("\"", "\\\"");
                    sb.append("{\"entity\":\"").append(entity)
                      .append("\",\"count\":").append(rankedEntities.get(i).getValue())
                      .append(",\"related\":[");
                    List<KnowledgeGraph.RelatedEntity> related = knowledgeGraph.relatedEntities(rankedEntities.get(i).getKey());
                    for (int j = 0; j < related.size(); j++) {
                        if (j > 0) sb.append(",");
                        KnowledgeGraph.RelatedEntity rel = related.get(j);
                        sb.append("{\"entity\":\"").append(rel.entity().replace("\"", "\\\""))
                          .append("\",\"score\":").append(rel.weight()).append("}");
                    }
                    sb.append("]}");
                }
                sb.append("]");
                return sb.toString();
            } catch (Exception e) {
                return "[]";
            }
        });

        // Crawl endpoint: fetch a URL and add to index
        server.post("/api/v1/crawl", body -> {
            try {
                var req = JsonSerializer.fromJson(body, Map.class);
                String url = req != null ? (String) req.get("url") : null;
                if (url == null || url.trim().isEmpty()) {
                    return "{\"success\":false,\"error\":\"Missing URL\"}";
                }
                url = url.trim();
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    url = "https://" + url;
                }

                HttpDownloader downloader = new HttpDownloader();
                JSoupHtmlParser parser = new JSoupHtmlParser();
                UrlTask task = new UrlTask(url, URI.create(url).getHost(), 0, java.time.Instant.now());
                DownloadedPage page = downloader.download(task);
                if (page == null) {
                    return "{\"success\":false,\"error\":\"Failed to fetch URL\"}";
                }

                Optional<ParsedDocument> parsed = parser.parse(page);
                if (parsed.isEmpty()) {
                    return "{\"success\":false,\"error\":\"Failed to parse page\"}";
                }

                ParsedDocument doc = parsed.get();
                synchronized (indexLock) {
                    allDocs.add(doc);
                    reindex();
                }

                String title = doc.title() != null && !doc.title().isEmpty() ? doc.title() : url;
                return "{\"success\":true,\"title\":\"" + title.replace("\"", "\\\"") + "\",\"url\":\"" + url.replace("\"", "\\\"") + "\"}";
            } catch (Exception e) {
                return "{\"success\":false,\"error\":\"" + e.getMessage().replace("\"", "'") + "\"}";
            }
        });

        server.start();

        if ("SEARCH".equals(nodeType)) {
            registerWithCluster(port);
        }

        if ("CLUSTER".equals(nodeType)) {
            startClusterRuntime(server);
        }

        Thread.currentThread().join();
    }

    /**
     * Brings up the real consensus stack alongside this node's local index and
     * REST API: gossip membership, Raft, the consistent-hash ring and the
     * internal RPC server, all durable under {@code indexing.indexDir/raft}.
     *
     * <p>A CLUSTER node is a full search node that additionally participates in
     * the cluster, rather than a separate mode. Its public REST API keeps
     * serving search from the local index while {@code /api/v1/cluster/*}
     * exposes the replicated state machine and membership.</p>
     *
     * <p>Peers are addressed through {@link StaticNodeDirectory} from
     * {@code cluster.peers}. The bootstrap configuration is established once,
     * from this node plus its configured peers, and is persisted by Raft; on
     * restart the committed configuration wins and this call is a no-op.</p>
     */
    private void startClusterRuntime(RestServer server) throws Exception {
        String nodeId = config.get("cluster.nodeId", "").trim();
        if (nodeId.isEmpty()) {
            nodeId = java.net.InetAddress.getLocalHost().getHostName();
        }
        int clusterPort = config.getInt("cluster.port", 8081);
        String advertisedHost = config.get("cluster.advertisedHost", "localhost").trim();

        java.net.URI selfUri = java.net.URI.create("http://" + advertisedHost + ":" + clusterPort);
        StaticNodeDirectory directory = StaticNodeDirectory
                .parse(config.get("cluster.peers", ""))
                .withSelf(nodeId, selfUri);

        // The cluster secret authenticates every internal RPC. Nodes must share
        // it; a random per-node secret would make peers reject each other.
        String secret = config.get("cluster.secret", "").trim();
        if (secret.isEmpty()) {
            System.out.println("WARN: cluster.secret is not set; using a fixed development secret. "
                    + "Set MINIGOGLE_CLUSTER_SECRET before running outside a trusted network.");
            secret = "minigoogle-development-cluster-secret";
        }
        ClusterSecurity security = new ClusterSecurity(secret);

        // A shard executor backed by this node's live index, so a peer's
        // /cluster/v1/search/dispatch runs a real query against real postings.
        this.clusterNodeId = nodeId;
        final String executorNodeId = nodeId;
        SearchExecutor localSearch = new LocalSearchExecutor(
                Math.abs(nodeId.hashCode() % 1024),
                (query, topK) -> gatherCandidateResults(query, topK).results());

        Path raftDir = indexPath.resolve("raft");
        Files.createDirectories(raftDir);

        clusterNode = new ClusterNode(
                nodeId,
                clusterPort,
                directory,
                config.getLong("cluster.gossipInterval", 1000),
                config.getLong("cluster.nodeTimeout", 30000),
                config.getLong("cluster.raft.electionTimeoutMs", 1500),
                config.getLong("cluster.raft.heartbeatMs", 300),
                localSearch,
                security,
                raftDir);
        clusterNode.start();

        // Seed gossip with the configured peers so membership converges without
        // an external registry; Raft then campaigns only against live members.
        for (String peerId : directory.nodeIds()) {
            if (!peerId.equals(executorNodeId)) {
                clusterNode.getGossip().seedPeer(peerId);
            }
        }

        try {
            clusterNode.initializeConfig(List.copyOf(directory.nodeIds()));
            System.out.println("Bootstrapped Raft configuration with " + directory.nodeIds());
        } catch (IllegalStateException alreadyConfigured) {
            System.out.println("Raft configuration already established; keeping committed membership");
        }

        registerClusterEndpoints(server);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                clusterNode.stop();
            } catch (RuntimeException e) {
                System.err.println("Cluster node shutdown failed: " + e.getMessage());
            }
        }, "cluster-shutdown"));

        System.out.println("Cluster node " + nodeId + " participating on port " + clusterPort
                + " with peers " + directory.nodeIds());
    }

    /**
     * Exposes the replicated state machine and cluster membership over the
     * public REST API, so the consensus layer is reachable by a user request
     * rather than only by internal RPC.
     */
    private void registerClusterEndpoints(RestServer server) {
        server.getWithContentType("/api/v1/cluster/status", "application/json", req -> {
            RaftConsensus raft = clusterNode.getRaft();
            return "{\"nodeId\":\"" + clusterNodeId
                    + "\",\"state\":\"" + raft.getState()
                    + "\",\"term\":" + raft.getCurrentTerm()
                    + ",\"leader\":\"" + String.valueOf(raft.getCurrentLeader())
                    + "\",\"commitIndex\":" + raft.getCommitIndex()
                    + ",\"members\":" + toJsonArray(clusterNode.getCommittedConfig().members())
                    + ",\"liveNodes\":" + toJsonArray(clusterNode.getGossip().getLiveNodes())
                    + "}";
        });

        // Linearizable write through Raft: returns only once a majority has
        // committed and applied the entry.
        server.post("/api/v1/cluster/kv", body -> {
            try {
                KvRequest request = JsonSerializer.fromJson(body, KvRequest.class);
                if (request == null || request.key() == null || request.key().isBlank()) {
                    return "{\"success\":false,\"error\":\"key is required\"}";
                }
                clusterNode.put(request.key(),
                        String.valueOf(request.value()).getBytes(StandardCharsets.UTF_8));
                return "{\"success\":true,\"key\":\"" + request.key().replace("\"", "'") + "\"}";
            } catch (NotLeaderException e) {
                return "{\"success\":false,\"error\":\"not leader\",\"leader\":\""
                        + String.valueOf(e.getLeaderId()) + "\"}";
            } catch (Exception e) {
                return "{\"success\":false,\"error\":\"" + sanitize(e.getMessage()) + "\"}";
            }
        });

        server.getWithContentType("/api/v1/cluster/kv", "application/json", req -> {
            try {
                String key = java.net.URLDecoder.decode(
                        req.replaceAll("^.*[?&]key=", "").replaceAll("&.*$", ""),
                        StandardCharsets.UTF_8).trim();
                if (key.isEmpty()) {
                    return "{\"found\":false,\"error\":\"key is required\"}";
                }
                byte[] value = clusterNode.get(key);
                if (value == null) {
                    return "{\"found\":false,\"key\":\"" + key.replace("\"", "'") + "\"}";
                }
                return "{\"found\":true,\"key\":\"" + key.replace("\"", "'")
                        + "\",\"value\":\"" + new String(value, StandardCharsets.UTF_8).replace("\"", "'") + "\"}";
            } catch (NotLeaderException e) {
                return "{\"found\":false,\"error\":\"no leader\",\"leader\":\""
                        + String.valueOf(e.getLeaderId()) + "\"}";
            } catch (Exception e) {
                return "{\"found\":false,\"error\":\"" + sanitize(e.getMessage()) + "\"}";
            }
        });
    }

    private static String toJsonArray(java.util.Collection<String> values) {
        return values.stream()
                .map(v -> "\"" + v.replace("\"", "'") + "\"")
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }

    private static String sanitize(String message) {
        return message == null ? "unknown" : message.replace("\"", "'");
    }

    /** Request body for the replicated key-value endpoint. */
    public record KvRequest(String key, String value) {
    }

    /**
     * Starts the node as a cluster coordinator (search gateway). It does not
     * build a local index; instead it fans search queries out to the online
     * index nodes tracked by the cluster registry and merges their results.
     */
    private void startCoordinatorNode(int port) throws Exception {
        int clusterPort = config.getInt("cluster.port", 8081);
        String coordinatorUrl = config.get("cluster.coordinatorUrl", "");
        if (coordinatorUrl.isBlank()) {
            coordinatorUrl = "http://localhost:" + clusterPort;
        }

        ClusterCoordinator clusterCoordinator = new ClusterCoordinator(clusterPort);
        clusterCoordinator.start();
        // Coordinator trains the shared ranking model on click feedback, using
        // the served-impression log as its feature source (RFC 0001 §6.4).
        boolean clickEnabled = config.getBoolean("ml.click.enabled", true);
        SearchCoordinator searchCoordinator = clickEnabled
                ? new SearchCoordinator(coordinatorUrl, 3,
                        config.getInt("ml.click.trainAfterClicks", 25),
                        config.getInt("ml.ltr.epochs", 3),
                        config.getDouble("ml.ltr.learningRate", 0.05))
                : new SearchCoordinator(coordinatorUrl);

        QueryAnalytics coordinatorAnalytics = new QueryAnalytics();
        RestServer server = new RestServer(port);
        String html = loadResource("/demo/index.html");
        server.getHtml("/", req -> html);

        server.post("/api/v1/search", body -> {
            try {
                SearchRequest request = JsonSerializer.fromJson(body, SearchRequest.class);
                if (request == null || request.query() == null || request.query().trim().isEmpty()) {
                    return JsonSerializer.toJson(new SearchResponse(0, 0, List.of()));
                }
                int topK = config.getInt("search.topK", 20);
                int pageSize = request.pageSize() > 0 ? request.pageSize() : topK;
                long start = System.currentTimeMillis();
                long timeoutMs = config.getLong("search.timeoutMs", 5000);
                List<com.minigoogle.network.dto.SearchResult> results =
                        searchCoordinator.search(request.query().trim(), pageSize, timeoutMs);
                long elapsed = System.currentTimeMillis() - start;
                coordinatorAnalytics.recordQuery(request.query().trim(), results.size(), elapsed);
                return JsonSerializer.toJson(new SearchResponse(elapsed, results.size(), results));
            } catch (Exception e) {
                return "{\"error\":\"" + e.getMessage().replace("\"", "'") + "\"}";
            }
        });

        server.getWithContentType("/api/v1/health", "application/json", req -> "{\"status\":\"ok\"}");

        server.getWithContentType("/api/v1/cluster/state", "application/json", req -> {
            try {
                return JsonSerializer.toJson(clusterCoordinator.getState());
            } catch (Exception e) {
                return "{\"error\":\"" + e.getMessage() + "\"}";
            }
        });

        // The shared frontend is served from every node type, so the coordinator
        // must register the routes the UI calls. Suggest/stats have no local
        // index to answer from, so they reply with empty/zero payloads; analytics
        // is tracked locally; crawl is rejected with a clear message.
        server.getWithContentType("/api/v1/suggest", "application/json", req -> "[]");

        server.getWithContentType("/api/v1/stats", "application/json", req ->
            "{\"documentCount\":0,\"vocabularySize\":0,\"averageDocumentLength\":0,\"version\":\"\"}");

        server.getWithContentType("/api/v1/analytics", "application/json", req -> {
            try {
                var top = coordinatorAnalytics.getTopQueries(5);
                StringBuilder topJson = new StringBuilder("[");
                for (int i = 0; i < top.size(); i++) {
                    if (i > 0) topJson.append(",");
                    topJson.append("{\"query\":\"").append(top.get(i).getKey().replace("\"", "\\\""))
                           .append("\",\"count\":").append(top.get(i).getValue()).append("}");
                }
                topJson.append("]");
                return "{" +
                    "\"totalQueries\":" + coordinatorAnalytics.getTotalQueries() + "," +
                    "\"averageLatencyMs\":" + coordinatorAnalytics.getAverageLatencyMs() + "," +
                    "\"zeroResultRate\":" + coordinatorAnalytics.getZeroResultRate() + "," +
                    "\"uniqueQueryCount\":" + coordinatorAnalytics.uniqueQueryCount() + "," +
                    "\"topQueries\":" + topJson +
                    "}";
            } catch (Exception e) {
                return "{\"error\":\"" + e.getMessage() + "\"}";
            }
        });

        server.post("/api/v1/crawl", body ->
            "{\"success\":false,\"error\":\"Coordinator node does not host a local index; add URLs on a standalone or SEARCH-mode node\"}");

        // Click feedback: the coordinator attributes the click to its served
        // impression and retrains the shared ranking model when enough new
        // clicks have accumulated.
        server.post("/api/v1/click", body -> {
            try {
                ClickRequest request = JsonSerializer.fromJson(body, ClickRequest.class);
                if (request == null || request.query() == null || request.query().trim().isEmpty()) {
                    return "{\"success\":false,\"error\":\"Missing query\"}";
                }
                String url = request.url() != null && !request.url().isEmpty()
                        ? request.url() : null;
                int position = request.position() > 0 ? request.position() : 1;
                int trainedPairs = searchCoordinator.recordClick(
                        request.query().trim(), url, position, request.sessionId());
                int docId = searchCoordinator.resolveDocId(url);
                return "{\"success\":true,\"documentId\":" + docId
                        + ",\"position\":" + position + ",\"trainedPairs\":" + trainedPairs + "}";
            } catch (Exception e) {
                return "{\"success\":false,\"error\":\"" + e.getMessage().replace("\"", "'") + "\"}";
            }
        });

        // Learning-to-rank model and click statistics for the coordinator.
        server.getWithContentType("/api/v1/ml/stats", "application/json", req -> {
            try {
                FeatureName[] names = FeatureName.values();
                StringBuilder featuresJson = new StringBuilder("[");
                for (int i = 0; i < names.length; i++) {
                    if (i > 0) featuresJson.append(",");
                    featuresJson.append("\"").append(names[i].name()).append("\"");
                }
                featuresJson.append("]");
                double[] weights = searchCoordinator.modelWeights();
                StringBuilder weightsJson = new StringBuilder("[");
                for (int i = 0; i < weights.length; i++) {
                    if (i > 0) weightsJson.append(",");
                    weightsJson.append(weights[i]);
                }
                weightsJson.append("]");
                return "{" +
                    "\"ltrEnabled\":" + clickEnabled + "," +
                    "\"features\":" + featuresJson + "," +
                    "\"weights\":" + weightsJson + "," +
                    "\"clicks\":" + searchCoordinator.clickCount() + "," +
                    "\"impressions\":" + searchCoordinator.impressionCount() +
                    "}";
            } catch (Exception e) {
                return "{\"error\":\"" + e.getMessage() + "\"}";
            }
        });

        server.start();
        System.out.println("Cluster registry listening on http://localhost:" + clusterCoordinator.getPort());
        System.out.println("Coordinator gateway on http://localhost:" + port);
    }

    /**
     * Registers this index node with the cluster coordinator and starts
     * sending heartbeats so it stays marked ONLINE.
     */
    private void registerWithCluster(int port) {
        String coordinatorUrl = config.get("cluster.coordinatorUrl", "");
        if (coordinatorUrl.isBlank()) {
            coordinatorUrl = "http://localhost:" + config.getInt("cluster.port", 8081);
        }
        String nodeId = config.get("cluster.nodeId", "node-" + port);
        String advertisedHost = config.get("cluster.advertisedHost", "localhost");

        RestClient client = new RestClient();
        NodeInfo info = new NodeInfo(nodeId, advertisedHost, port, NodeRole.INDEX, NodeStatus.ONLINE,
                System.currentTimeMillis());
        try {
            client.post(coordinatorUrl + "/register", JsonSerializer.toJson(info));
            System.out.println("[SearchNode] Registered with coordinator at " + coordinatorUrl
                    + " as " + nodeId + " (" + advertisedHost + ":" + port + ")");
        } catch (Exception e) {
            System.err.println("[SearchNode] Failed to register with coordinator: " + e.getMessage());
        }

        HeartbeatManager heartbeats = new HeartbeatManager(coordinatorUrl, nodeId, client);
        heartbeats.start(5000);
    }

    /**
     * Rebuilds the full index, autocomplete, ranking pipeline, and all
     * supporting structures from the current allDocs list via the shared
     * {@link SearchEngineBuilder}. The composition root only keeps what the
     * REST endpoints need; retrieval itself lives in {@link SearchEngine}.
     *
     * <p>Each build writes into its own versioned sub-directory, so the previous
     * build's memory-mapped postings file is never rewritten or truncated while
     * it may still be mapped (Windows refuses that with "a user-mapped section
     * open"). The new state is published atomically through the ref-counted
     * {@link ConcurrentIndex}; the previous state's mmap is closed and its build
     * directory removed only once no handler still holds a lease. A failed build
     * leaves the current index untouched.</p>
     */
    private void reindex() throws IOException {
        synchronized (indexLock) {
            Path buildDir = indexPath.resolve("builds").resolve("build-" + buildSeq.incrementAndGet());
            Files.createDirectories(buildDir);
            try {
                SearchEngineBuild build = SearchEngineBuilder.build(allDocs, config, buildDir);

                Map<Integer, ParsedDocument> docIdToParsed = new HashMap<>();
                for (int i = 0; i < allDocs.size(); i++) {
                    docIdToParsed.put(i + 1, allDocs.get(i));
                }

                // Learning-to-rank + click feedback: feature extractor shares the same
                // corpus data as the ranking pipeline so serve-time and train-time
                // feature vectors are identical.
                int ltrEpochs = config.getInt("ml.ltr.epochs", 3);
                double ltrLearningRate = config.getDouble("ml.ltr.learningRate", 0.05);
                int trainAfterClicks = config.getInt("ml.click.trainAfterClicks", 25);
                FeatureExtractor featureExtractor = build.featureExtractor();
                LinearRankingModel rankingModel = new LinearRankingModel();
                ClickTracker clickTracker = new ClickTracker();
                ClickFeedbackTrainer clickFeedbackTrainer = new ClickFeedbackTrainer(featureExtractor, rankingModel,
                        clickTracker, trainAfterClicks, ltrEpochs, ltrLearningRate);

                boolean knowledgeEnabled = config.getBoolean("semantic.knowledge.enabled", true);
                KnowledgeGraph knowledgeGraph;
                if (knowledgeEnabled) {
                    int maxEntitiesPerDoc = config.getInt("semantic.knowledge.maxEntitiesPerDoc", 10);
                    int maxRelated = config.getInt("semantic.knowledge.maxRelated", 8);
                    EntityExtractor extractor = new EntityExtractor(maxEntitiesPerDoc);
                    KnowledgeGraph kg = new KnowledgeGraph(maxRelated);
                    for (Map.Entry<Integer, ParsedDocument> e : docIdToParsed.entrySet()) {
                        ParsedDocument parsed = e.getValue();
                        kg.addDocument(e.getKey(), extractor.extract(parsed.title(), parsed.text()));
                    }
                    knowledgeGraph = kg;
                } else {
                    knowledgeGraph = null;
                }

                IndexState newState = new IndexState(build, knowledgeGraph, rankingModel,
                        clickTracker, clickFeedbackTrainer);

                currentIndex.publish(ConcurrentIndex.Entry.of(newState, () -> releaseBuild(buildDir, build)));
                queryCache.clear();
            } catch (Exception e) {
                // Failed build must not destroy the active index: never publish,
                // and best-effort remove the partial build directory.
                deleteRecursively(buildDir);
                throw e;
            }
        }
    }

    /**
     * Closes a published build's memory-mapped index and removes its build
     * directory. Runs exactly once, after the last reader lease on that build
     * has been released.
     */
    private static void releaseBuild(Path buildDir, SearchEngineBuild build) {
        try {
            if (build.mmapIndex() != null) {
                build.mmapIndex().close();
            }
        } finally {
            deleteRecursively(buildDir);
        }
    }

    /**
     * Best-effort recursive delete. On Windows a build directory whose postings
     * file is still mapped cannot be removed; the entry's mmap is closed first,
     * so this succeeds in practice. Failures are ignored so they never affect
     * serving.
     */
    private static void deleteRecursively(Path dir) {
        try (java.util.stream.Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder())
                .forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                        // Best effort.
                    }
                });
        } catch (IOException ignored) {
            // Best effort.
        }
    }

    /**
     * Normalized query-cache key derived from the lexical token stream: words
     * are lowercased and whitespace variants collapse, while boolean operators
     * (AND/OR/NOT) keep their operator identity so {@code cat AND dog} (boolean
     * AND) and {@code cat and dog} (implicit AND) never collide.
     */
    private static String cacheKey(String query) {
        return com.minigoogle.query.lexer.QueryKey.canonicalize(query);
    }

    private SearchResponse executeSearch(String query, int pageSize) {
        // Check cache
        List<com.minigoogle.network.dto.SearchResult> cachedResults = queryCache.get(cacheKey(query));
        if (cachedResults != null) {
            eventBus.publish(new QueryExecutedEvent(query, cachedResults.size(), 0, true));
            return new SearchResponse(0, cachedResults.size(), cachedResults);
        }

        long start = System.currentTimeMillis();

        // Hold a lease on the current index generation for the duration of the
        // search so a concurrent reindex can never close the memory-mapped
        // postings file under us.
        try (ConcurrentIndex.Lease<IndexState> lease = currentIndex.lease()) {
            IndexState state = lease.value();
            if (state == null) {
                eventBus.publish(new QueryExecutedEvent(query, 0, System.currentTimeMillis() - start, false));
                return new SearchResponse(0, 0, List.of());
            }
            SearchEngine searchEngine = state.engine();

            RetrievalResult retrieval = searchEngine.retrieveCandidates(query, pageSize);
            List<RankedDocument> ranked = retrieval.ranked();
            String didYouMean = retrieval.didYouMean();

            if (ranked.isEmpty()) {
                eventBus.publish(new QueryExecutedEvent(query, 0, System.currentTimeMillis() - start, false));
                return new SearchResponse(0, 0, List.of(), didYouMean);
            }

            // Learning-to-rank re-rank: score each candidate with the shared
            // ranking pipeline using the pre-rank position as the POSITION
            // feature, then re-sort. Standalone and distributed execution share
            // GlobalRankingPipeline, so the served ordering is produced by the
            // exact same code in both modes.
            boolean ltrEnabled = config.getBoolean("ml.ltr.enabled", true)
                    && state.rankingModel() != null && state.featureExtractor() != null;
            if (ltrEnabled && !ranked.isEmpty()) {
                List<RankedCandidate> candidates = new ArrayList<>(ranked.size());
                for (RankedDocument doc : ranked) {
                    candidates.add(new RankedCandidate(
                            String.valueOf(doc.documentId()), doc.url(), doc.title(), doc.snippet(),
                            doc.bm25Score(), doc.pageRankScore(),
                            searchEngine.rawFeatures(query, doc.documentId())));
                }
                List<RankedResult> ltrResults = GlobalRankingPipeline.rank(
                        query, candidates, searchEngine.normalizationContext(), state.rankingModel());
                List<RankedDocument> ltrRanked = new ArrayList<>(ltrResults.size());
                for (RankedResult result : ltrResults) {
                    RankedCandidate candidate = result.candidate();
                    ltrRanked.add(new RankedDocument(
                            Integer.parseInt(candidate.documentId()), candidate.url(), candidate.title(),
                            candidate.bm25Score(), candidate.pageRankScore(),
                            result.score(), candidate.snippet()));
                }
                ranked = ltrRanked;
            }

            // Record the served result order as an impression for click training.
            if (state.clickTracker() != null && !ranked.isEmpty()) {
                state.clickTracker().recordImpression(query, ranked.stream()
                        .map(RankedDocument::documentId)
                        .collect(Collectors.toList()));
            }

            // Convert to DTOs
            List<com.minigoogle.network.dto.SearchResult> dtoResults = ranked.stream()
                .map(r -> new com.minigoogle.network.dto.SearchResult(
                    r.url(), r.title(), r.snippet(), r.finalScore(),
                    r.bm25Score(), r.pageRankScore()))
                .collect(Collectors.toList());

            long elapsed = System.currentTimeMillis() - start;

            // Cache and record analytics
            queryCache.put(cacheKey(query), dtoResults);
            eventBus.publish(new QueryExecutedEvent(query, dtoResults.size(), elapsed, false));

            return new SearchResponse(elapsed, dtoResults.size(), dtoResults, didYouMean);
        }
    }

    /**
     * Shard-mode query execution: runs the shared retrieval stage and returns
     * the candidate set with raw feature vectors and the node's corpus
     * statistics, so the coordinator can perform global ranking.
     */
    private SearchResponse gatherCandidateResults(String query, int pageSize) {
        long start = System.currentTimeMillis();

        try (ConcurrentIndex.Lease<IndexState> lease = currentIndex.lease()) {
            IndexState state = lease.value();
            if (state == null) {
                return new SearchResponse(0, 0, List.of());
            }
            SearchEngine searchEngine = state.engine();

            RetrievalResult retrieval = searchEngine.retrieveCandidates(query, pageSize);
            List<RankedDocument> ranked = retrieval.ranked();

            NormalizationContext context = searchEngine.normalizationContext();

            List<com.minigoogle.network.dto.SearchResult> results =
                    new ArrayList<>(ranked.size());
            for (RankedDocument doc : ranked) {
                var rawFeatures = searchEngine.rawFeatures(query, doc.documentId());
                double[] raw = rawFeatures != null ? rawFeatures.toArray() : null;
                results.add(new com.minigoogle.network.dto.SearchResult(
                        doc.url(), doc.title(), doc.snippet(), doc.finalScore(),
                        doc.bm25Score(), doc.pageRankScore(), raw));
            }

            long elapsed = System.currentTimeMillis() - start;
            return new SearchResponse(elapsed, results.size(), results,
                    retrieval.didYouMean(), context.maxPageRank(), context.maxDocLength());
        }
    }

    private String loadResource(String resourcePath) throws IOException {
        try (InputStream is = getClass().getResourceAsStream(resourcePath)) {
            if (is == null) throw new IOException("Resource not found: " + resourcePath);
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private void printBanner() {
        System.out.println();
        System.out.println("============================================================");
        System.out.println("  __  __ ____  ____    ____   __  __ _    _    ____ _   _");
        System.out.println(" |  \\/  |  _ \\/ ___|  |  _ \\ / _|/ _| |  / \\  / ___| | | |");
        System.out.println(" | |\\/| | |_) \\___ \\  | |_) | |_| |_| | / _ \\| |   | |_| |");
        System.out.println(" | |  | |  _ < ___) | |  __/|  _|  _  |/ ___ \\ |___|  _  |");
        System.out.println(" |_|  |_|_| \\_\\____/  |_|   |_| |_| |_/_/   \\_\\____|_| |_|");
        System.out.println();
        System.out.println("  Distributed Search Engine - Demo Mode");
        System.out.println("============================================================");
        System.out.println();
    }

    /**
     * Immutable bundle of one full index build plus its app-level supporting
     * structures. Published atomically via {@link #currentIndex}; handlers grab
     * one reference and read a self-consistent view of a single build.
     */
    private record IndexState(
            SearchEngineBuild build,
            KnowledgeGraph knowledgeGraph,
            LinearRankingModel rankingModel,
            ClickTracker clickTracker,
            ClickFeedbackTrainer clickFeedbackTrainer) {

        SearchEngine engine() {
            return build.engine();
        }

        TrieAutocomplete autocomplete() {
            return build.autocomplete();
        }

        SpellCorrector spellCorrector() {
            return build.spellCorrector();
        }

        Metadata metadata() {
            return build.metadata();
        }

        Map<Integer, String> docUrls() {
            return build.docUrls();
        }

        Map<String, Integer> urlToDocId() {
            return build.urlToDocId();
        }

        FeatureExtractor featureExtractor() {
            return build.featureExtractor();
        }

        MemoryMappedIndex mmapIndex() {
            return build.mmapIndex();
        }
    }

    public static void main(String[] args) throws Exception {
        MiniGoogleApp app = new MiniGoogleApp();
        app.start();
    }
}
