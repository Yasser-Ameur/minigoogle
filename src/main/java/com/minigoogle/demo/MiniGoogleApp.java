package com.minigoogle.demo;

import com.minigoogle.crawler.downloader.HttpDownloader;
import com.minigoogle.crawler.model.DownloadedPage;
import com.minigoogle.crawler.model.ParsedDocument;
import com.minigoogle.crawler.model.UrlTask;
import com.minigoogle.crawler.parser.JSoupHtmlParser;
import com.minigoogle.network.http.RestServer;
import com.minigoogle.indexer.IndexBuilder;
import com.minigoogle.indexer.inverted.Posting;
import com.minigoogle.indexer.inverted.PostingList;
import com.minigoogle.indexer.model.IndexedDocument;
import com.minigoogle.indexer.normalization.CaseFolder;
import com.minigoogle.indexer.stemming.PorterStemmer;
import com.minigoogle.indexer.normalization.UnicodeNormalizer;
import com.minigoogle.indexer.stopwords.StopWordFilter;
import com.minigoogle.indexer.tokenizer.Tokenizer;
import com.minigoogle.network.dto.SearchRequest;
import com.minigoogle.network.dto.SearchResponse;
import com.minigoogle.network.serialization.JsonSerializer;
import com.minigoogle.query.ast.QueryNode;
import com.minigoogle.query.lexer.Lexer;
import com.minigoogle.query.lexer.Token;
import com.minigoogle.query.parser.Parser;
import com.minigoogle.query.planner.QueryPlanner;
import com.minigoogle.ranking.bm25.BM25Parameters;
import com.minigoogle.ranking.model.RankedDocument;
import com.minigoogle.ranking.pagerank.GraphBuilder;
import com.minigoogle.ranking.pagerank.PageRankCalculator;
import com.minigoogle.ranking.pipeline.RankingPipeline;
import com.minigoogle.semantic.EmbeddingGenerator;
import com.minigoogle.semantic.VectorIndex;
import com.minigoogle.semantic.autocomplete.TrieAutocomplete;
import com.minigoogle.semantic.expansion.PmiThesaurusBuilder;
import com.minigoogle.semantic.expansion.QueryExpander;
import com.minigoogle.semantic.knowledge.EntityExtractor;
import com.minigoogle.semantic.knowledge.KnowledgeGraph;
import com.minigoogle.semantic.rag.RetrievalPipeline;
import com.minigoogle.semantic.reranking.CrossEncoderRanker;
import com.minigoogle.semantic.spell.SpellCorrector;
import com.minigoogle.semantic.synonym.SynonymGraph;
import com.minigoogle.storage.dictionary.DictionaryEntry;
import com.minigoogle.storage.dictionary.DictionaryReader;
import com.minigoogle.storage.documents.DocumentReader;
import com.minigoogle.storage.metadata.Metadata;
import com.minigoogle.storage.metadata.MetadataReader;
import com.minigoogle.storage.mmap.MemoryMappedIndex;
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
    private IndexBuilder builder;
    private Configuration config;

    private QueryPlanner planner;
    private RankingPipeline ranking;
    private Lexer lexer;
    private MemoryMappedIndex mmapIndex;
    private TrieAutocomplete autocomplete;
    private SpellCorrector spellCorrector;
    private QueryExpander queryExpander;
    private CrossEncoderRanker reranker;
    private VectorIndex vectorIndex;
    private EmbeddingGenerator embeddingGenerator;
    private Metadata metadata;
    private final QueryAnalytics analytics = new QueryAnalytics();

    private Map<String, DictionaryEntry> dictionary;
    private Path indexPath;
    private final LRUCache<String, List<com.minigoogle.network.dto.SearchResult>> queryCache = new LRUCache<>(200);
    private final EventBus eventBus = new EventBus();

    private Map<Integer, String> docUrls = new HashMap<>();
    private Map<Integer, String> docTitles = new HashMap<>();
    private Map<Integer, String> docBodies = new HashMap<>();
    private Map<Integer, Integer> docLengths = new HashMap<>();
    private Map<Integer, Double> pageRankScores = new HashMap<>();
    private KnowledgeGraph knowledgeGraph;

    private FeatureExtractor featureExtractor;
    private LinearRankingModel rankingModel;
    private ClickTracker clickTracker;
    private ClickFeedbackTrainer clickFeedbackTrainer;

    private final UnicodeNormalizer normalizer = new UnicodeNormalizer();
    private final CaseFolder caseFolder = new CaseFolder();
    private final PorterStemmer stemmer = new PorterStemmer();
    private final StopWordFilter stopWordFilter = new StopWordFilter();
    private final Tokenizer rawTokenizer = new Tokenizer();

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
                SearchResponse response = executeSearch(request.query().trim(), pageSize);
                long elapsed = System.currentTimeMillis() - start;
                return JsonSerializer.toJson(new SearchResponse(elapsed, response.totalResults(), response.results(), response.didYouMean()));
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
        SearchCoordinator searchCoordinator = new SearchCoordinator(coordinatorUrl);

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
     * supporting structures from the current allDocs list.
     */
    private void reindex() throws IOException {
        // Release the previous memory-mapped postings file BEFORE rewriting it,
        // otherwise Windows refuses to truncate a file with a mapped section open.
        if (mmapIndex != null) {
            mmapIndex.close();
            mmapIndex = null;
            System.gc();
            System.runFinalization();
        }

        builder = new IndexBuilder();
        for (ParsedDocument doc : allDocs) {
            builder.processDocument(doc);
        }
        builder.flush(
            indexPath.resolve("dictionary.bin").toString(),
            indexPath.resolve("postings.bin").toString(),
            indexPath.resolve("documents.bin").toString()
        );

        DictionaryReader dictReader = new DictionaryReader();
        dictionary = dictReader.read(indexPath.resolve("dictionary.bin"));

        mmapIndex = new MemoryMappedIndex(indexPath.resolve("postings.bin"));
        planner = new QueryPlanner(mmapIndex, dictionary);
        lexer = new Lexer();

        // Build surface-form vocabulary from raw document text
        Map<String, Integer> surfaceFreqs = new HashMap<>();
        for (ParsedDocument doc : allDocs) {
            List<String> tokens = rawTokenizer.tokenize(normalizer.normalize(doc.text()));
            String prevWord = null;
            for (String token : tokens) {
                String cleaned = caseFolder.fold(token);
                if (!cleaned.isEmpty() && !stopWordFilter.isStopWord(cleaned)) {
                    surfaceFreqs.merge(cleaned, 1, Integer::sum);
                    if (prevWord != null) {
                        surfaceFreqs.merge(prevWord + " " + cleaned, 1, Integer::sum);
                    }
                    prevWord = cleaned;
                } else {
                    prevWord = null;
                }
            }
        }

        autocomplete = new TrieAutocomplete(surfaceFreqs);
        spellCorrector = new SpellCorrector(new HashSet<>(surfaceFreqs.keySet()));
        for (String word : surfaceFreqs.keySet()) {
            autocomplete.addWord(word);
        }

        queryExpander = buildQueryExpander();

        // Build the semantic vector index from real document content. Documents
        // that share vocabulary map to nearby vectors via feature hashing.
        boolean semanticEnabled = config.getBoolean("semantic.enabled", true);
        if (semanticEnabled) {
            int embeddingDim = config.getInt("semantic.dimension", 128);
            double semanticWeight = config.getDouble("semantic.weight", 0.3);
            embeddingGenerator = new EmbeddingGenerator(embeddingDim);
            vectorIndex = new VectorIndex(embeddingDim);
            for (int i = 0; i < allDocs.size(); i++) {
                ParsedDocument doc = allDocs.get(i);
                String content = doc.title() + " " + doc.text();
                vectorIndex.add(i + 1, embeddingGenerator.embed(content), doc.title());
            }
            reranker = new CrossEncoderRanker(vectorIndex, embeddingGenerator, semanticWeight);
        } else {
            vectorIndex = null;
            embeddingGenerator = null;
            reranker = new CrossEncoderRanker();
        }
        queryCache.clear();

        List<IndexedDocument> indexedDocs = new DocumentReader().read(indexPath.resolve("documents.bin"));
        metadata = new MetadataReader().read(indexPath.resolve("metadata.bin"));

        Map<Integer, IndexedDocument> docIdToIndexed = new HashMap<>();
        for (int i = 0; i < indexedDocs.size(); i++) {
            docIdToIndexed.put(i + 1, indexedDocs.get(i));
        }

        Map<Integer, ParsedDocument> docIdToParsed = new HashMap<>();
        for (int i = 0; i < allDocs.size(); i++) {
            docIdToParsed.put(i + 1, allDocs.get(i));
        }

        Map<String, Integer> urlToDocId = new HashMap<>();
        for (Map.Entry<Integer, IndexedDocument> e : docIdToIndexed.entrySet()) {
            urlToDocId.put(e.getValue().url().toString(), e.getKey());
        }

        GraphBuilder graph = new GraphBuilder();
        for (Map.Entry<Integer, ParsedDocument> e : docIdToParsed.entrySet()) {
            int docId = e.getKey();
            ParsedDocument parsed = e.getValue();
            graph.addNode(docId);
            for (URI link : parsed.outgoingLinks()) {
                Integer targetId = urlToDocId.get(link.toString());
                if (targetId != null && targetId != docId) {
                    graph.addEdge(docId, targetId);
                }
            }
        }
        Map<Integer, Double> pageRank = new PageRankCalculator().compute(graph);

        docUrls = new HashMap<>();
        docTitles = new HashMap<>();
        docBodies = new HashMap<>();
        docLengths = new HashMap<>();

        for (Map.Entry<Integer, IndexedDocument> e : docIdToIndexed.entrySet()) {
            int id = e.getKey();
            IndexedDocument idx = e.getValue();
            ParsedDocument parsed = docIdToParsed.get(id);
            docUrls.put(id, idx.url().toString());
            docTitles.put(id, idx.title());
            docBodies.put(id, parsed != null ? parsed.text() : "");
            docLengths.put(id, idx.length());
        }

        BM25Parameters bm25Params = BM25Parameters.withDefaults(
            metadata.documentCount(), metadata.averageDocumentLength()
        );
        pageRankScores = pageRank;
        ranking = new RankingPipeline(bm25Params, pageRankScores, docUrls, docTitles, docBodies, docLengths);

        // Learning-to-rank + click feedback: feature extractor shares the same
        // corpus data as the ranking pipeline so serve-time and train-time
        // feature vectors are identical.
        int ltrEpochs = config.getInt("ml.ltr.epochs", 3);
        double ltrLearningRate = config.getDouble("ml.ltr.learningRate", 0.05);
        int trainAfterClicks = config.getInt("ml.click.trainAfterClicks", 25);
        featureExtractor = new FeatureExtractor(docUrls, docTitles, docBodies, docLengths,
                pageRankScores, vectorIndex, embeddingGenerator);
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

    /**
     * Builds the query expander, preferring a corpus-derived PMI thesaurus when
     * corpus-based expansion is enabled.
     */
    private QueryExpander buildQueryExpander() {
        boolean expansionEnabled = config.getBoolean("semantic.expansion.enabled", true);
        if (!expansionEnabled) {
            return new QueryExpander();
        }
        int windowSize = config.getInt("semantic.expansion.windowSize", 10);
        double pmiThreshold = config.getDouble("semantic.expansion.pmiThreshold", 1.0);
        int maxNeighbors = config.getInt("semantic.expansion.maxNeighbors", 5);
        SynonymGraph thesaurus = new PmiThesaurusBuilder(windowSize, pmiThreshold, maxNeighbors).build(allDocs);
        return new QueryExpander(thesaurus);
    }

    private SearchResponse executeSearch(String query, int pageSize) {
        // Check cache
        List<com.minigoogle.network.dto.SearchResult> cachedResults = queryCache.get(query.toLowerCase().strip());
        if (cachedResults != null) {
            eventBus.publish(new QueryExecutedEvent(query, cachedResults.size(), 0, true));
            return new SearchResponse(0, cachedResults.size(), cachedResults);
        }

        long start = System.currentTimeMillis();

        // Expand query with synonyms. Expansions are OR-ed with the original
        // terms so a single-word query (e.g. "text") is not turned into an
        // AND query that requires every expanded term to match.
        List<String> expandedTerms = queryExpander.expand(query, 4);
        String expandedQuery = String.join(" OR ", expandedTerms);

        // Parse and execute query
        List<Token> tokens = lexer.tokenize(expandedQuery);
        Parser parser = new Parser(tokens);
        QueryNode ast = parser.parse();
        PostingList results = planner.execute(ast);

        // Spell correction fallback
        String didYouMean = null;
        if (results.getPostings().isEmpty()) {
            List<String> corrected = new ArrayList<>();
            List<Token> origTokens = lexer.tokenize(query);
            for (Token t : origTokens) {
                String stemmed = stemmer.stem(caseFolder.fold(normalizer.normalize(t.value())));
                if (stemmed.isEmpty()) continue;
                if (dictionary.containsKey(stemmed)) {
                    corrected.add(t.value());
                } else {
                    String fix = spellCorrector.correct(stemmed);
                    if (!fix.equals(stemmed)) {
                        corrected.add(fix);
                    } else {
                        corrected.add(t.value());
                    }
                }
            }
            if (!corrected.equals(origTokens.stream().map(Token::value).collect(Collectors.toList()))) {
                didYouMean = String.join(" ", corrected);
                String correctedQuery = String.join(" ", corrected);
                List<String> correctedExpanded = queryExpander.expand(correctedQuery, 4);
                tokens = lexer.tokenize(String.join(" ", correctedExpanded));
                ast = new Parser(tokens).parse();
                results = planner.execute(ast);
            }
        }

        boolean hybridEnabled = config.getBoolean("semantic.hybrid.enabled", true)
                && vectorIndex != null && embeddingGenerator != null;

        if (results.getPostings().isEmpty() && !hybridEnabled) {
            eventBus.publish(new QueryExecutedEvent(query, 0, System.currentTimeMillis() - start, false));
            return new SearchResponse(0, 0, List.of(), didYouMean);
        }

        // Build per-term posting lists for ranking
        Map<String, PostingList> candidatePostings = new HashMap<>();
        Map<String, Integer> documentFrequencies = new HashMap<>();

        for (Token token : tokens) {
            String processed = stemmer.stem(caseFolder.fold(normalizer.normalize(token.value())));
            if (!processed.isEmpty()) {
                QueryNode termNode = new com.minigoogle.query.ast.WordNode(processed);
                PostingList termResults = planner.execute(termNode);
                if (!termResults.getPostings().isEmpty()) {
                    candidatePostings.put(processed, termResults);
                    documentFrequencies.put(processed, termResults.getPostings().size());
                }
            }
        }

        if (candidatePostings.isEmpty()) {
            candidatePostings.put(query, results);
            documentFrequencies.put(query, results.getPostings().size());
        }

        // Rank with BM25 + PageRank
        List<String> queryTerms = tokens.stream()
            .map(t -> t.value())
            .map(v -> stemmer.stem(caseFolder.fold(normalizer.normalize(v))))
            .filter(t -> !t.isEmpty())
            .collect(Collectors.toList());

        List<RankedDocument> ranked;
        if (results.getPostings().isEmpty()) {
            // No lexical matches; rely on semantic recall below.
            ranked = new ArrayList<>();
        } else {
            ranked = ranking.rank(queryTerms, candidatePostings, documentFrequencies);
        }

        // Hybrid recall: merge lexical candidates with semantically-similar
        // documents (which may share no lexical terms with the query) using the
        // normalized score blend from the retrieval pipeline.
        if (hybridEnabled) {
            int fetchK = config.getInt("semantic.hybrid.fetchK", 60);
            double lexicalWeight = config.getDouble("semantic.hybrid.lexicalWeight", 0.5);

            List<VectorIndex.VectorResult> lexical = ranked.stream()
                    .map(r -> new VectorIndex.VectorResult(r.documentId(), r.finalScore(), r.title()))
                    .collect(Collectors.toList());

            double[] queryVector = embeddingGenerator.embed(query);
            List<VectorIndex.VectorResult> semantic = vectorIndex.search(queryVector, fetchK);

            int topK = Math.max(pageSize, ranked.size());
            List<VectorIndex.VectorResult> merged = RetrievalPipeline.mergeResults(
                    lexical, semantic, topK, lexicalWeight);

            Map<Integer, RankedDocument> byId = ranked.stream()
                    .collect(Collectors.toMap(RankedDocument::documentId, r -> r));
            ranked = new ArrayList<>();
            for (VectorIndex.VectorResult r : merged) {
                RankedDocument existing = byId.get(r.id());
                if (existing != null) {
                    ranked.add(new RankedDocument(
                            existing.documentId(), existing.url(), existing.title(),
                            existing.bm25Score(), existing.pageRankScore(), r.score(), existing.snippet()));
                } else {
                    String url = docUrls.getOrDefault(r.id(), "");
                    String title = docTitles.getOrDefault(r.id(), r.metadata());
                    ranked.add(new RankedDocument(
                            r.id(), url, title, 0.0, 0.0, r.score(), snippetFor(r.id())));
                }
            }
        }

        // Re-rank with cross-encoder
        ranked = reranker.rerank(query, ranked);

        // Learning-to-rank re-rank: score each candidate with the ranking model
        // using the pre-rank position as the POSITION feature, then re-sort.
        boolean ltrEnabled = config.getBoolean("ml.ltr.enabled", true)
                && rankingModel != null && featureExtractor != null;
        if (ltrEnabled && !ranked.isEmpty()) {
            List<RankedDocument> ltrRanked = new ArrayList<>(ranked.size());
            for (int i = 0; i < ranked.size(); i++) {
                RankedDocument doc = ranked.get(i);
                double ltrScore = rankingModel.score(featureExtractor.extract(query, doc, i));
                ltrRanked.add(new RankedDocument(doc.documentId(), doc.url(), doc.title(),
                        doc.bm25Score(), doc.pageRankScore(), ltrScore, doc.snippet()));
            }
            ltrRanked.sort(Comparator.comparingDouble(RankedDocument::finalScore).reversed());
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

    private String loadResource(String resourcePath) throws IOException {
        try (InputStream is = getClass().getResourceAsStream(resourcePath)) {
            if (is == null) throw new IOException("Resource not found: " + resourcePath);
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private String snippetFor(int docId) {
        String body = docBodies.getOrDefault(docId, "");
        if (body == null || body.isEmpty()) {
            return docTitles.getOrDefault(docId, "");
        }
        String cleaned = body.replaceAll("\\s+", " ").trim();
        return cleaned.length() > 160 ? cleaned.substring(0, 160) : cleaned;
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
