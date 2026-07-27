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
import com.minigoogle.semantic.autocomplete.TrieAutocomplete;
import com.minigoogle.semantic.expansion.QueryExpander;
import com.minigoogle.semantic.reranking.CrossEncoderRanker;
import com.minigoogle.semantic.spell.SpellCorrector;
import com.minigoogle.storage.dictionary.DictionaryEntry;
import com.minigoogle.storage.dictionary.DictionaryReader;
import com.minigoogle.storage.documents.DocumentReader;
import com.minigoogle.storage.metadata.Metadata;
import com.minigoogle.storage.metadata.MetadataReader;
import com.minigoogle.storage.mmap.MemoryMappedIndex;
import com.minigoogle.monitoring.analytics.QueryAnalytics;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Main application entry point that wires the full search pipeline:
 * indexing, ranking, autocomplete, spell correction, query expansion,
 * caching, analytics, and the REST API.
 */
public class MiniGoogleApp {

    private static final int PORT = 8080;
    private static final String INDEX_DIR = "demo-index";

    private final List<ParsedDocument> allDocs = new ArrayList<>();
    private IndexBuilder builder;

    private QueryPlanner planner;
    private RankingPipeline ranking;
    private Lexer lexer;
    private MemoryMappedIndex mmapIndex;
    private TrieAutocomplete autocomplete;
    private SpellCorrector spellCorrector;
    private QueryExpander queryExpander;
    private CrossEncoderRanker reranker;
    private Metadata metadata;
    private final QueryAnalytics analytics = new QueryAnalytics();

    private Map<String, DictionaryEntry> dictionary;
    private Path indexPath;
    private final Map<String, List<com.minigoogle.network.dto.SearchResult>> queryCache = new ConcurrentHashMap<>();

    private final UnicodeNormalizer normalizer = new UnicodeNormalizer();
    private final CaseFolder caseFolder = new CaseFolder();
    private final PorterStemmer stemmer = new PorterStemmer();
    private final StopWordFilter stopWordFilter = new StopWordFilter();
    private final Tokenizer rawTokenizer = new Tokenizer();

    public void start() throws Exception {
        printBanner();

        indexPath = Path.of(INDEX_DIR);
        Files.createDirectories(indexPath);

        // Phase 1: Index demo documents
        System.out.print("Indexing documents... ");
        allDocs.addAll(DemoDocuments.all());
        reindex();
        System.out.println("done (" + allDocs.size() + " documents)");

        // Phase 2: Start server
        System.out.println("Starting server on http://localhost:" + PORT);
        System.out.println("============================================================");

        RestServer server = new RestServer(PORT);

        String html = loadResource("/demo/index.html");
        server.getHtml("/", req -> html);

        server.post("/api/v1/search", body -> {
            try {
                SearchRequest request = JsonSerializer.fromJson(body, SearchRequest.class);
                if (request == null || request.query() == null || request.query().trim().isEmpty()) {
                    return JsonSerializer.toJson(new SearchResponse(0, 0, List.of()));
                }
                long start = System.currentTimeMillis();
                SearchResponse response = executeSearch(request.query().trim(), request.pageSize());
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
        Thread.currentThread().join();
    }

    /**
     * Rebuilds the full index, autocomplete, ranking pipeline, and all
     * supporting structures from the current allDocs list.
     */
    private void reindex() throws IOException {
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

        queryExpander = new QueryExpander();
        reranker = new CrossEncoderRanker();
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
        Map<Integer, Double> pageRankScores = new PageRankCalculator().compute(graph);

        Map<Integer, String> docUrls = new HashMap<>();
        Map<Integer, String> docTitles = new HashMap<>();
        Map<Integer, String> docBodies = new HashMap<>();
        Map<Integer, Integer> docLengths = new HashMap<>();

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
        ranking = new RankingPipeline(bm25Params, pageRankScores, docUrls, docTitles, docBodies, docLengths);
    }

    private SearchResponse executeSearch(String query, int pageSize) {
        // Check cache
        List<com.minigoogle.network.dto.SearchResult> cachedResults = queryCache.get(query.toLowerCase().strip());
        if (cachedResults != null) {
            analytics.recordQuery(query, cachedResults.size(), 0);
            return new SearchResponse(0, cachedResults.size(), cachedResults);
        }

        long start = System.currentTimeMillis();

        // Expand query with synonyms
        List<String> expandedTerms = queryExpander.expand(query, 4);
        String expandedQuery = String.join(" ", expandedTerms);

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

        if (results.getPostings().isEmpty()) {
            analytics.recordQuery(query, 0, System.currentTimeMillis() - start);
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

        List<RankedDocument> ranked = ranking.rank(queryTerms, candidatePostings, documentFrequencies);

        // Re-rank with cross-encoder
        ranked = reranker.rerank(query, ranked);

        // Convert to DTOs
        List<com.minigoogle.network.dto.SearchResult> dtoResults = ranked.stream()
            .map(r -> new com.minigoogle.network.dto.SearchResult(
                r.url(), r.title(), r.snippet(), r.finalScore(),
                r.bm25Score(), r.pageRankScore()))
            .collect(Collectors.toList());

        long elapsed = System.currentTimeMillis() - start;

        // Cache and record analytics
        queryCache.put(query.toLowerCase().strip(), dtoResults);
        analytics.recordQuery(query, dtoResults.size(), elapsed);

        return new SearchResponse(elapsed, dtoResults.size(), dtoResults, didYouMean);
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
