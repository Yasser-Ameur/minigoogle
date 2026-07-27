package com.minigoogle.demo;

import com.minigoogle.crawler.model.ParsedDocument;
import com.minigoogle.indexer.IndexBuilder;
import com.minigoogle.indexer.model.IndexedDocument;
import com.minigoogle.indexer.normalization.CaseFolder;
import com.minigoogle.indexer.stemming.PorterStemmer;
import com.minigoogle.indexer.normalization.UnicodeNormalizer;
import com.minigoogle.network.dto.SearchResponse;
import com.minigoogle.query.ast.QueryNode;
import com.minigoogle.query.lexer.Lexer;
import com.minigoogle.query.lexer.Token;
import com.minigoogle.query.parser.Parser;
import com.minigoogle.query.planner.QueryPlanner;
import com.minigoogle.indexer.inverted.PostingList;
import com.minigoogle.ranking.bm25.BM25Parameters;
import com.minigoogle.ranking.model.RankedDocument;
import com.minigoogle.ranking.pipeline.RankingPipeline;
import com.minigoogle.storage.dictionary.DictionaryEntry;
import com.minigoogle.storage.dictionary.DictionaryReader;
import com.minigoogle.storage.documents.DocumentReader;
import com.minigoogle.storage.metadata.Metadata;
import com.minigoogle.storage.metadata.MetadataReader;
import com.minigoogle.storage.mmap.MemoryMappedIndex;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for end-to-end MiniGoogle application workflow. */
class MiniGoogleAppTest {

    @TempDir
    Path tempDir;

    private List<ParsedDocument> docs;
    private QueryPlanner planner;
    private RankingPipeline ranking;
    private Map<Integer, String> docUrls;
    private Map<Integer, String> docTitles;

    @BeforeEach
    void setup() throws Exception {
        docs = DemoDocuments.all();
        assertFalse(docs.isEmpty(), "DemoDocuments should not be empty");

        IndexBuilder builder = new IndexBuilder();
        for (ParsedDocument doc : docs) {
            builder.processDocument(doc);
        }

        Path dictPath = tempDir.resolve("dictionary.bin");
        Path postPath = tempDir.resolve("postings.bin");
        Path docPath = tempDir.resolve("documents.bin");
        builder.flush(dictPath.toString(), postPath.toString(), docPath.toString());

        DictionaryReader dictReader = new DictionaryReader();
        Map<String, DictionaryEntry> dictionary = dictReader.read(dictPath);
        MemoryMappedIndex mmapIndex = new MemoryMappedIndex(postPath);
        planner = new QueryPlanner(mmapIndex, dictionary);

        List<IndexedDocument> indexedDocs = new DocumentReader().read(docPath);
        Metadata metadata = new MetadataReader().read(tempDir.resolve("metadata.bin"));

        docUrls = new HashMap<>();
        docTitles = new HashMap<>();
        Map<Integer, String> docBodies = new HashMap<>();
        Map<Integer, Integer> docLengths = new HashMap<>();
        Map<Integer, Double> pageRanks = new HashMap<>();

        for (int i = 0; i < indexedDocs.size(); i++) {
            int id = i + 1;
            IndexedDocument idx = indexedDocs.get(i);
            ParsedDocument parsed = docs.get(i);
            docUrls.put(id, idx.url().toString());
            docTitles.put(id, idx.title());
            docBodies.put(id, parsed.text());
            docLengths.put(id, idx.length());
            pageRanks.put(id, 0.5);
        }

        BM25Parameters bm25Params = BM25Parameters.withDefaults(
                metadata.documentCount(), metadata.averageDocumentLength());
        ranking = new RankingPipeline(bm25Params, pageRanks, docUrls, docTitles, docBodies, docLengths);
    }

    private SearchResponse search(String query) {
        Lexer lexer = new Lexer();
        List<Token> tokens = lexer.tokenize(query);
        Parser parser = new Parser(tokens);
        QueryNode ast = parser.parse();
        PostingList results = planner.execute(ast);

        Map<String, PostingList> candidatePostings = new HashMap<>();
        Map<String, Integer> documentFrequencies = new HashMap<>();

        for (Token token : tokens) {
            String processed = new PorterStemmer().stem(
                    new CaseFolder().fold(new UnicodeNormalizer().normalize(token.value())));
            if (!processed.isEmpty()) {
                QueryNode termNode = new com.minigoogle.query.ast.WordNode(processed);
                PostingList termResults = planner.execute(termNode);
                if (!termResults.getPostings().isEmpty()) {
                    candidatePostings.put(processed, termResults);
                    documentFrequencies.put(processed, termResults.getPostings().size());
                }
            }
        }

        List<String> queryTerms = tokens.stream()
                .map(t -> t.value())
                .map(v -> new PorterStemmer().stem(
                        new CaseFolder().fold(new UnicodeNormalizer().normalize(v))))
                .filter(t -> !t.isEmpty())
                .toList();

        List<RankedDocument> ranked = ranking.rank(queryTerms, candidatePostings, documentFrequencies);

        List<com.minigoogle.network.dto.SearchResult> dtoResults = ranked.stream()
                .map(r -> new com.minigoogle.network.dto.SearchResult(r.url(), r.title(), r.snippet(), r.finalScore(), r.bm25Score(), r.pageRankScore()))
                .toList();

        return new SearchResponse(0, dtoResults.size(), dtoResults);
    }

    @Test
    void testDemoDocumentsAreNonEmpty() {
        assertFalse(docs.isEmpty());
        assertTrue(docs.size() >= 15, "Should have at least 15 demo documents");
    }

    @Test
    void testIndexBuildsAllDocuments() throws Exception {
        Metadata metadata = new MetadataReader().read(tempDir.resolve("metadata.bin"));
        assertTrue(metadata.documentCount() >= 15, "Index should contain all demo documents");
    }

    @Test
    void testSearchForJava() {
        SearchResponse resp = search("java");
        assertTrue(resp.totalResults() > 0, "Should find results for 'java'");
        assertTrue(resp.results().stream().anyMatch(r -> r.title().toLowerCase().contains("java")),
                "At least one result should mention Java in title");
    }

    @Test
    void testSearchForPython() {
        SearchResponse resp = search("python");
        assertTrue(resp.totalResults() > 0, "Should find results for 'python'");
    }

    @Test
    void testSearchForDistributedSystems() {
        SearchResponse resp = search("distributed systems");
        assertTrue(resp.totalResults() > 0, "Should find results for 'distributed systems'");
    }

    @Test
    void testSearchForWebCrawler() {
        SearchResponse resp = search("web crawler");
        assertTrue(resp.totalResults() > 0, "Should find results for 'web crawler'");
    }

    @Test
    void testSearchRankingScoresDecreasing() {
        SearchResponse resp = search("search engine");
        if (resp.totalResults() > 1) {
            double prev = resp.results().get(0).score();
            for (int i = 1; i < resp.results().size(); i++) {
                assertTrue(prev >= resp.results().get(i).score(),
                        "Results should be sorted by decreasing score");
                prev = resp.results().get(i).score();
            }
        }
    }

    @Test
    void testSearchResultsHaveUrlTitleSnippet() {
        SearchResponse resp = search("database");
        assertFalse(resp.results().isEmpty());
        for (com.minigoogle.network.dto.SearchResult r : resp.results()) {
            assertNotNull(r.url(), "Result should have a URL");
            assertNotNull(r.title(), "Result should have a title");
            assertFalse(r.url().isEmpty(), "URL should not be empty");
            assertFalse(r.title().isEmpty(), "Title should not be empty");
        }
    }

    @Test
    void testNonsenseQueryReturnsNoResults() {
        SearchResponse resp = search("xyzzyflurbo");
        assertEquals(0, resp.totalResults(), "Nonsense query should return 0 results");
    }

    @Test
    void testMultipleQueryTerms() {
        SearchResponse single = search("machine");
        SearchResponse multi = search("machine learning");
        assertTrue(single.totalResults() > 0, "Should find results for 'machine'");
        assertTrue(multi.totalResults() > 0, "Should find results for 'machine learning'");
    }

    @Test
    void testDemoDocumentsContainExpectedTopics() {
        Set<String> titles = docs.stream().map(ParsedDocument::title).map(String::toLowerCase).collect(java.util.stream.Collectors.toSet());
        assertTrue(titles.stream().anyMatch(t -> t.contains("search")), "Should have a search-related document");
        assertTrue(titles.stream().anyMatch(t -> t.contains("machine")), "Should have a machine learning document");
        assertTrue(titles.stream().anyMatch(t -> t.contains("distribut")), "Should have a distributed systems document");
    }
}
