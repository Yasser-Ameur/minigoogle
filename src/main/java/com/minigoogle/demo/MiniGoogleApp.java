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
import com.minigoogle.search.SearchEngine;
import com.minigoogle.search.SearchEngineBuild;
import com.minigoogle.search.SearchEngineBuilder;
import com.minigoogle.search.RetrievalResult;
import com.minigoogle.core.cache.LRUCache;
import com.minigoogle.core.config.Configuration;
import com.minigoogle.core.config.ConfigurationLoader;
import com.minigoogle.core.event.EventBus;
import com.minigoogle.core.event.QueryExecutedEvent;
import com.minigoogle.distributed.coordinator.ClusterCoordinator;
import com.minigoogle.distributed.coordinator.SearchCoordinator;
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

    private SearchEngine searchEngine;
    private SearchEngineBuild indexBuild;
    private TrieAutocomplete autocomplete;
    private SpellCorrector spellCorrector;
    private Metadata metadata;
    private final QueryAnalytics analytics = new QueryAnalytics();

    private Path indexPath;
    private final LRUCache<String, List<com.minigoogle.network.dto.SearchResult>> queryCache = new LRUCache<>(200);
    private final EventBus eventBus = new EventBus();

    private Map<Integer, String> docUrls = new HashMap<>();
    private KnowledgeGraph knowledgeGraph;

    private FeatureExtractor featureExtractor;
    private LinearRankingModel rankingModel;
    private ClickTracker clickTracker;
    private ClickFeedbackTrainer clickFeedbackTrainer;

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
            try {
                String prefix = req.replaceAll("^.*[?&]q=", "").replaceAll("&.*$", "");
                prefix = java.net.URLDecoder.decode(prefix, StandardCharsets.UTF_8).trim().toLowerCase();
                List<String> suggestions = autocomplete.autocomplete(prefix, 8);
                if (suggestions.isEmpty() && !prefix.isEmpty()) {
                    String corrected = spellCorrector.correct(prefix);
                    if (!corrected.equals(prefix)) {
                        suggestions = autocomplete.autocomplete(corrected, 8);
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
            try {
                return "{" +
                    "\"documentCount\":" + metadata.documentCount() + "," +
                    "\"vocabularySize\":" + metadata.vocabularySize() + "," +
                    "\"averageDocumentLength\":" + metadata.averageDocumentLength() + "," +
                    "\"version\":\"" + metadata.version() + "\"" +
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

        // Click feedback: record a user click for learning-to-rank training
        server.post("/api/v1/click", body -> {
            try {
                ClickRequest request = JsonSerializer.fromJson(body, ClickRequest.class);
                if (request == null || request.query() == null || request.query().trim().isEmpty()
                        || request.documentId() <= 0) {
                    return "{\"success\":false,\"error\":\"Missing query or documentId\"}";
                }
                int position = request.position() > 0 ? request.position() : 1;
                String url = request.url() != null && !request.url().isEmpty()
                        ? request.url() : docUrls.getOrDefault(request.documentId(), "");
                ClickEvent event = new ClickEvent(request.query().trim(), request.documentId(),
                        url, position, null, request.sessionId());
                int trainedPairs = 0;
                if (clickFeedbackTrainer != null) {
                    trainedPairs = clickFeedbackTrainer.onClick(event);
                } else if (clickTracker != null) {
                    clickTracker.recordClick(event);
                }
                return "{\"success\":true,\"documentId\":" + request.documentId()
                        + ",\"position\":" + position + ",\"trainedPairs\":" + trainedPairs + "}";
            } catch (Exception e) {
                return "{\"success\":false,\"error\":\"" + e.getMessage().replace("\"", "'") + "\"}";
            }
        });

        // Learning-to-rank model and click statistics
        server.getWithContentType("/api/v1/ml/stats", "application/json", req -> {
            try {
                FeatureName[] names = FeatureName.values();
                StringBuilder featuresJson = new StringBuilder("[");
                for (int i = 0; i < names.length; i++) {
                    if (i > 0) featuresJson.append(",");
                    featuresJson.append("\"").append(names[i].name()).append("\"");
                }
                featuresJson.append("]");
                double[] weights = rankingModel != null ? rankingModel.weights() : new double[0];
                StringBuilder weightsJson = new StringBuilder("[");
                for (int i = 0; i < weights.length; i++) {
                    if (i > 0) weightsJson.append(",");
                    weightsJson.append(weights[i]);
                }
                weightsJson.append("]");
                return "{" +
                    "\"ltrEnabled\":" + (rankingModel != null) + "," +
                    "\"features\":" + featuresJson + "," +
                    "\"weights\":" + weightsJson + "," +
                    "\"clicks\":" + (clickTracker != null ? clickTracker.clickCount() : 0) + "," +
                    "\"impressions\":" + (clickTracker != null ? clickTracker.impressionCount() : 0) +
                    "}";
            } catch (Exception e) {
                return "{\"error\":\"" + e.getMessage() + "\"}";
            }
        });

        // Knowledge graph entities
        server.getWithContentType("/api/v1/entities", "application/json", req -> {
            try {
                String q = req.replaceAll("^.*[?&]q=", "").replaceAll("&.*$", "");
                q = java.net.URLDecoder.decode(q, StandardCharsets.UTF_8).trim();
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
                allDocs.add(doc);
                reindex();

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

        Thread.currentThread().join();
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
                List<com.minigoogle.network.dto.SearchResult> results =
                        searchCoordinator.search(request.query().trim(), pageSize);
                long elapsed = System.currentTimeMillis() - start;
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
     */
    private void reindex() throws IOException {
        // Release the previous memory-mapped postings file BEFORE rewriting it,
        // otherwise Windows refuses to truncate a file with a mapped section open.
        if (indexBuild != null && indexBuild.mmapIndex() != null) {
            indexBuild.mmapIndex().close();
            System.gc();
            System.runFinalization();
        }

        indexBuild = SearchEngineBuilder.build(allDocs, config, indexPath);
        searchEngine = indexBuild.engine();
        autocomplete = indexBuild.autocomplete();
        spellCorrector = indexBuild.spellCorrector();
        metadata = indexBuild.metadata();
        docUrls = indexBuild.docUrls();
        queryCache.clear();

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
        featureExtractor = indexBuild.featureExtractor();
        rankingModel = new LinearRankingModel();
        clickTracker = new ClickTracker();
        clickFeedbackTrainer = new ClickFeedbackTrainer(featureExtractor, rankingModel,
                clickTracker, trainAfterClicks, ltrEpochs, ltrLearningRate);

        boolean knowledgeEnabled = config.getBoolean("semantic.knowledge.enabled", true);
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
    }

    private SearchResponse executeSearch(String query, int pageSize) {
        // Check cache
        List<com.minigoogle.network.dto.SearchResult> cachedResults = queryCache.get(query.toLowerCase().strip());
        if (cachedResults != null) {
            eventBus.publish(new QueryExecutedEvent(query, cachedResults.size(), 0, true));
            return new SearchResponse(0, cachedResults.size(), cachedResults);
        }

        long start = System.currentTimeMillis();

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
                && rankingModel != null && featureExtractor != null;
        if (ltrEnabled && !ranked.isEmpty()) {
            List<RankedCandidate> candidates = new ArrayList<>(ranked.size());
            for (RankedDocument doc : ranked) {
                candidates.add(new RankedCandidate(
                        String.valueOf(doc.documentId()), doc.url(), doc.title(), doc.snippet(),
                        doc.bm25Score(), doc.pageRankScore(),
                        searchEngine.rawFeatures(query, doc.documentId())));
            }
            List<RankedResult> ltrResults = GlobalRankingPipeline.rank(
                    query, candidates, searchEngine.normalizationContext(), rankingModel);
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
        if (clickTracker != null && !ranked.isEmpty()) {
            clickTracker.recordImpression(query, ranked.stream()
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
        queryCache.put(query.toLowerCase().strip(), dtoResults);
        eventBus.publish(new QueryExecutedEvent(query, dtoResults.size(), elapsed, false));

        return new SearchResponse(elapsed, dtoResults.size(), dtoResults, didYouMean);
    }

    /**
     * Shard-mode query execution: runs the shared retrieval stage and returns
     * the candidate set with raw feature vectors and the node's corpus
     * statistics, so the coordinator can perform global ranking.
     */
    private SearchResponse gatherCandidateResults(String query, int pageSize) {
        long start = System.currentTimeMillis();

        RetrievalResult retrieval = searchEngine.retrieveCandidates(query, pageSize);
        List<RankedDocument> ranked = retrieval.ranked();

        NormalizationContext context = searchEngine.normalizationContext();

        List<com.minigoogle.network.dto.SearchResult> results =
                new ArrayList<>(ranked.size());
        for (RankedDocument doc : ranked) {
            double[] raw = searchEngine.rawFeatures(query, doc.documentId()) != null
                    ? searchEngine.rawFeatures(query, doc.documentId()).toArray()
                    : null;
            results.add(new com.minigoogle.network.dto.SearchResult(
                    doc.url(), doc.title(), doc.snippet(), doc.finalScore(),
                    doc.bm25Score(), doc.pageRankScore(), raw));
        }

        long elapsed = System.currentTimeMillis() - start;
        return new SearchResponse(elapsed, results.size(), results,
                retrieval.didYouMean(), context.maxPageRank(), context.maxDocLength());
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

    public static void main(String[] args) throws Exception {
        MiniGoogleApp app = new MiniGoogleApp();
        app.start();
    }
}
