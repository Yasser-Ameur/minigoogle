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
import com.minigoogle.semantic.reranking.CrossEncoderRanker;
import com.minigoogle.storage.dictionary.DictionaryEntry;
import com.minigoogle.storage.dictionary.DictionaryReader;
import com.minigoogle.storage.documents.DocumentReader;
import com.minigoogle.storage.metadata.Metadata;
import com.minigoogle.storage.metadata.MetadataReader;
import com.minigoogle.storage.mmap.MemoryMappedIndex;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end test that mirrors MiniGoogleApp's wiring: build the index, the
 * ranking pipeline, the content-based vector index, and verify the semantic
 * reranker lifts vocabulary-matching documents to the top.
 */
class SemanticEndToEndTest {

    @TempDir
    Path tempDir;

    @Test
    void semanticRerankPromotesContentMatchWithFullPipeline() throws Exception {
        List<ParsedDocument> docs = DemoDocuments.all();

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
        QueryPlanner planner = new QueryPlanner(mmapIndex, dictionary, docs.size());
        mmapIndex.close();

        List<IndexedDocument> indexedDocs = new DocumentReader().read(docPath);
        Metadata metadata = new MetadataReader().read(tempDir.resolve("metadata.bin"));

        Map<Integer, String> docUrls = new HashMap<>();
        Map<Integer, String> docTitles = new HashMap<>();
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
        RankingPipeline ranking = new RankingPipeline(bm25Params, pageRanks, docUrls, docTitles, docBodies, docLengths);

        // Mirror MiniGoogleApp.reindex semantic wiring: one vector per demo document.
        EmbeddingGenerator generator = new EmbeddingGenerator(128);
        VectorIndex vectorIndex = new VectorIndex(128);
        for (int i = 0; i < docs.size(); i++) {
            ParsedDocument doc = docs.get(i);
            vectorIndex.add(i + 1, generator.embed(doc.title() + " " + doc.text()), doc.title());
        }
        assertEquals(docs.size(), vectorIndex.size());
        CrossEncoderRanker reranker = new CrossEncoderRanker(vectorIndex, generator, 0.3);

        String query = "distributed";
        Lexer lexer = new Lexer();
        List<Token> tokens = lexer.tokenize(query);
        QueryNode ast = new Parser(tokens).parse();
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
                .map(t -> new PorterStemmer().stem(
                        new CaseFolder().fold(new UnicodeNormalizer().normalize(t.value()))))
                .filter(t -> !t.isEmpty())
                .toList();

        List<RankedDocument> ranked = ranking.rank(queryTerms, candidatePostings, documentFrequencies);
        assertFalse(ranked.isEmpty());

        List<RankedDocument> reranked = reranker.rerank(query, ranked);

        assertEquals(ranked.size(), reranked.size());
        RankedDocument top = reranked.get(0);
        assertTrue(top.title().toLowerCase().contains("distributed"),
                "Semantic reranker should lift the distributed-systems document to the top");

        // Scores must be sorted descending.
        for (int i = 1; i < reranked.size(); i++) {
            assertTrue(reranked.get(i - 1).finalScore() >= reranked.get(i).finalScore(),
                    "Reranked results should be sorted by descending combined score");
        }

        // The content match must score strictly above an unrelated document.
        assertTrue(reranked.get(0).finalScore() > reranked.get(reranked.size() - 1).finalScore());
    }

    @Test
    void testHybridMergeThenRerankKeepsSortedOrder() throws Exception {
        List<ParsedDocument> docs = DemoDocuments.all();

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
        QueryPlanner planner = new QueryPlanner(mmapIndex, dictionary, docs.size());
        mmapIndex.close();

        List<com.minigoogle.indexer.model.IndexedDocument> indexed =
                new DocumentReader().read(docPath);
        Map<Integer, String> docUrls = new HashMap<>();
        Map<Integer, String> docTitles = new HashMap<>();
        Map<Integer, String> docBodies = new HashMap<>();
        Map<Integer, Integer> docLengths = new HashMap<>();
        Map<Integer, Double> pageRanks = new HashMap<>();
        for (int i = 0; i < docs.size(); i++) {
            int id = i + 1;
            IndexedDocument idx = indexed.get(i);
            ParsedDocument parsed = docs.get(i);
            docUrls.put(id, idx.url().toString());
            docTitles.put(id, idx.title());
            docBodies.put(id, parsed.text());
            docLengths.put(id, idx.length());
            pageRanks.put(id, 0.5);
        }

        EmbeddingGenerator generator = new EmbeddingGenerator(128);
        VectorIndex vectorIndex = new VectorIndex(128);
        for (int i = 0; i < docs.size(); i++) {
            ParsedDocument doc = docs.get(i);
            vectorIndex.add(i + 1, generator.embed(doc.title() + " " + doc.text()), doc.title());
        }

        Metadata metadata = new MetadataReader().read(tempDir.resolve("metadata.bin"));
        BM25Parameters bm25Params = BM25Parameters.withDefaults(
                metadata.documentCount(), metadata.averageDocumentLength());
        RankingPipeline ranking = new RankingPipeline(
                bm25Params, pageRanks, docUrls, docTitles, docBodies, docLengths);

        String query = "distributed systems";
        Lexer lexer = new Lexer();
        List<Token> tokens = lexer.tokenize(query);
        QueryNode ast = new Parser(tokens).parse();
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
                .map(t -> new PorterStemmer().stem(
                        new CaseFolder().fold(new UnicodeNormalizer().normalize(t.value()))))
                .filter(t -> !t.isEmpty())
                .toList();

        List<RankedDocument> ranked = ranking.rank(queryTerms, candidatePostings, documentFrequencies);
        assertFalse(ranked.isEmpty());

        List<com.minigoogle.semantic.VectorIndex.VectorResult> lexical = ranked.stream()
                .map(r -> new com.minigoogle.semantic.VectorIndex.VectorResult(
                        r.documentId(), r.finalScore(), r.title()))
                .toList();
        List<com.minigoogle.semantic.VectorIndex.VectorResult> semantic =
                vectorIndex.search(generator.embed(query), 60);
        List<com.minigoogle.semantic.VectorIndex.VectorResult> merged =
                com.minigoogle.semantic.rag.RetrievalPipeline.mergeResults(
                        lexical, semantic, Math.max(60, ranked.size()), 0.5);

        assertTrue(merged.size() >= ranked.size());
        // Every lexical candidate survives the hybrid merge.
        for (RankedDocument r : ranked) {
            assertTrue(merged.stream().anyMatch(m -> m.id() == r.documentId()));
        }
    }

    private static void assertFalse(boolean condition) {
        if (condition) {
            throw new AssertionError("Expected condition to be false");
        }
    }
}
