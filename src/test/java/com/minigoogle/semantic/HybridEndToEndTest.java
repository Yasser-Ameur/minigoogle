package com.minigoogle.semantic;

import com.minigoogle.crawler.model.ParsedDocument;
import com.minigoogle.demo.DemoDocuments;
import com.minigoogle.indexer.IndexBuilder;
import com.minigoogle.indexer.model.IndexedDocument;
import com.minigoogle.indexer.normalization.CaseFolder;
import com.minigoogle.indexer.stemming.PorterStemmer;
import com.minigoogle.indexer.normalization.UnicodeNormalizer;
import com.minigoogle.query.ast.QueryNode;
import com.minigoogle.query.lexer.Lexer;
import com.minigoogle.query.lexer.Token;
import com.minigoogle.query.parser.Parser;
import com.minigoogle.query.planner.QueryPlanner;
import com.minigoogle.indexer.inverted.PostingList;
import com.minigoogle.ranking.bm25.BM25Parameters;
import com.minigoogle.ranking.model.RankedDocument;
import com.minigoogle.ranking.pipeline.RankingPipeline;
import com.minigoogle.semantic.rag.RetrievalPipeline;
import com.minigoogle.storage.dictionary.DictionaryEntry;
import com.minigoogle.storage.dictionary.DictionaryReader;
import com.minigoogle.storage.documents.DocumentReader;
import com.minigoogle.storage.metadata.Metadata;
import com.minigoogle.storage.metadata.MetadataReader;
import com.minigoogle.storage.mmap.MemoryMappedIndex;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end test for hybrid recall: merging lexical candidates with
 * semantically-similar documents that may share no lexical terms with the
 * query. Mirrors MiniGoogleApp.executeSearch's hybrid wiring.
 */
class HybridEndToEndTest {

    @TempDir
    Path tempDir;

    private List<ParsedDocument> docs;
    private QueryPlanner planner;
    private Map<Integer, String> docUrls;
    private Map<Integer, String> docTitles;
    private Map<Integer, String> docBodies;
    private RankingPipeline ranking;
    private EmbeddingGenerator generator;
    private VectorIndex vectorIndex;

    @BeforeEach
    void setup() throws Exception {
        docs = DemoDocuments.all();

        IndexBuilder builder = new IndexBuilder();
        for (ParsedDocument doc : docs) {
            builder.processDocument(doc);
        }
        Path dictPath = tempDir.resolve("dictionary.bin");
        Path postPath = tempDir.resolve("postings.bin");
        Path docPath = tempDir.resolve("documents.bin");
        builder.flush(dictPath.toString(), postPath.toString(), docPath.toString());

        Map<String, DictionaryEntry> dictionary = new DictionaryReader().read(dictPath);
        MemoryMappedIndex mmapIndex = new MemoryMappedIndex(postPath);
        planner = new QueryPlanner(mmapIndex, dictionary, docs.size());

        List<IndexedDocument> indexedDocs = new DocumentReader().read(docPath);
        Metadata metadata = new MetadataReader().read(tempDir.resolve("metadata.bin"));

        docUrls = new HashMap<>();
        docTitles = new HashMap<>();
        docBodies = new HashMap<>();
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
        mmapIndex.close();

        BM25Parameters bm25Params = BM25Parameters.withDefaults(
                metadata.documentCount(), metadata.averageDocumentLength());
        ranking = new RankingPipeline(bm25Params, pageRanks, docUrls, docTitles, docBodies, docLengths);

        generator = new EmbeddingGenerator(128);
        vectorIndex = new VectorIndex(128);
        for (int i = 0; i < docs.size(); i++) {
            ParsedDocument doc = docs.get(i);
            vectorIndex.add(i + 1, generator.embed(doc.title() + " " + doc.text()), doc.title());
        }
    }

    private List<RankedDocument> lexicalRank(String query) throws Exception {
        Lexer lexer = new Lexer();
        List<Token> tokens = lexer.tokenize(query);
        Parser parser = new Parser(tokens);
        QueryNode ast = parser.parse();
        PostingList results = planner.execute(ast);
        if (results.getPostings().isEmpty()) {
            return new ArrayList<>();
        }

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
                .map(t -> new PorterStemmer().stem(
                        new CaseFolder().fold(new UnicodeNormalizer().normalize(t.value()))))
                .filter(t -> !t.isEmpty())
                .toList();

        return ranking.rank(queryTerms, candidatePostings, documentFrequencies);
    }

    @Test
    void testSemanticOnlyDocumentSurfacesAndHydrates() throws Exception {
        String phantomText = "xylophone zephyr aurora brambles";
        int phantomId = docs.size() + 1;
        vectorIndex.add(phantomId, generator.embed(phantomText), "Phantom Doc");
        docTitles.put(phantomId, "Phantom Doc");
        docUrls.put(phantomId, "http://example.com/phantom");

        String query = phantomText;
        List<RankedDocument> ranked = lexicalRank(query);
        assertTrue(ranked.isEmpty(), "Phantom text is not lexically indexed");

        List<VectorIndex.VectorResult> lexical = ranked.stream()
                .map(r -> new VectorIndex.VectorResult(r.documentId(), r.finalScore(), r.title()))
                .collect(Collectors.toList());
        double[] queryVector = generator.embed(query);
        List<VectorIndex.VectorResult> semantic = vectorIndex.search(queryVector, 60);

        int topK = Math.max(60, ranked.size());
        List<VectorIndex.VectorResult> merged =
                RetrievalPipeline.mergeResults(lexical, semantic, topK, 0.5);

        assertFalse(merged.isEmpty(), "Semantic recall should surface candidates for a zero-lexical query");
        assertTrue(merged.stream().anyMatch(r -> r.id() == phantomId),
                "Vector-only document should appear in the merged results");
    }

    @Test
    void testHybridMergeKeepsLexicalCandidates() throws Exception {
        String query = "distributed systems";
        List<RankedDocument> ranked = lexicalRank(query);
        assertFalse(ranked.isEmpty(), "Lexical search should match demo documents");

        List<VectorIndex.VectorResult> lexical = ranked.stream()
                .map(r -> new VectorIndex.VectorResult(r.documentId(), r.finalScore(), r.title()))
                .collect(Collectors.toList());
        double[] queryVector = generator.embed(query);
        List<VectorIndex.VectorResult> semantic = vectorIndex.search(queryVector, 60);

        int topK = Math.max(60, ranked.size());
        List<VectorIndex.VectorResult> merged =
                RetrievalPipeline.mergeResults(lexical, semantic, topK, 0.5);

        assertFalse(merged.isEmpty());
        assertTrue(merged.size() >= ranked.size(),
                "Merged results should keep every lexical candidate plus any semantic additions");
        // Every lexical candidate must survive the merge.
        for (RankedDocument r : ranked) {
            assertTrue(merged.stream().anyMatch(m -> m.id() == r.documentId()),
                    "Lexical candidate " + r.documentId() + " should be retained");
        }
    }

    @Test
    void testTopSemanticCandidatePresentInMerge() throws Exception {
        String query = "distributed systems";
        List<RankedDocument> ranked = lexicalRank(query);

        List<VectorIndex.VectorResult> lexical = ranked.stream()
                .map(r -> new VectorIndex.VectorResult(r.documentId(), r.finalScore(), r.title()))
                .collect(Collectors.toList());
        double[] queryVector = generator.embed(query);
        List<VectorIndex.VectorResult> semantic = vectorIndex.search(queryVector, 60);

        int topK = Math.max(60, ranked.size());
        List<VectorIndex.VectorResult> merged =
                RetrievalPipeline.mergeResults(lexical, semantic, topK, 0.5);

        assertFalse(semantic.isEmpty());
        assertTrue(merged.stream().anyMatch(m -> m.id() == semantic.get(0).id()),
                "Top semantic candidate should be present after merging");
    }
}
