package com.minigoogle.search;

import com.minigoogle.core.config.Configuration;
import com.minigoogle.crawler.model.ParsedDocument;
import com.minigoogle.ranking.fusion.ReciprocalRankFusion;
import com.minigoogle.ranking.model.RankedDocument;
import com.minigoogle.semantic.encoder.SemanticRetriever;
import com.minigoogle.semantic.encoder.SentenceEncoder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * End-to-end proof that {@code ranking.mode=rrf} does in production what the
 * BEIR diagnostic measured offline: surface a relevant document that shares no
 * term with the query.
 *
 * <p>The corpus is four lexically disjoint documents. The query "automotive"
 * appears in none of them, so BM25 cannot return anything at all — its candidate
 * map is built from query-term posting lists. If RRF returns the car document
 * first, the semantic channel is genuinely reaching the final ranking rather
 * than merely sitting in the candidate pool.</p>
 */
class RrfRankingModeIntegrationTest {

    private static final Path MODEL_DIR = Path.of("models", "all-MiniLM-L6-v2");

    private static final String[][] CORPUS = {
            {"Feline behaviour", "Cats purr loudly and hunt mice throughout the night."},
            {"Automobile maintenance", "Changing the oil in your vehicle keeps its engine healthy."},
            {"Baking bread", "Flour, water, salt and yeast are enough to make a loaf."},
            {"Gardening basics", "Tomato plants need sunlight and regular watering."},
    };
    private static final int CAR_DOC_ID = 2;   // ids are assigned in corpus order, 1-based
    private static final int BREAD_DOC_ID = 3;

    private static List<ParsedDocument> corpus() {
        List<ParsedDocument> docs = new ArrayList<>();
        for (int i = 0; i < CORPUS.length; i++) {
            docs.add(new ParsedDocument(UUID.randomUUID(),
                    URI.create("https://example.test/doc" + (i + 1)),
                    CORPUS[i][0], CORPUS[i][1], List.of(), Instant.EPOCH));
        }
        return docs;
    }

    /** Embeds the corpus exactly as the builder assumes: {@code title + " " + text}, ids 1-based. */
    private static Path buildVectors(Path dir) throws Exception {
        Path vectorFile = dir.resolve("vectors.bin");
        List<String> texts = new ArrayList<>();
        for (String[] doc : CORPUS) {
            texts.add(doc[0] + " " + doc[1]);
        }
        try (SentenceEncoder encoder = SentenceEncoder.load(MODEL_DIR,
                SentenceEncoder.DEFAULT_MAX_TOKENS, SentenceEncoder.MINILM_DIMENSION, 2)) {
            SemanticRetriever.buildVectorStore(encoder, texts, vectorFile, null);
        }
        return vectorFile;
    }

    private static Map<String, String> baseProps() {
        Map<String, String> props = new HashMap<>();
        props.put("ranking.pagerank.enabled", "false");
        props.put("ranking.diversify.enabled", "false");
        props.put("ranking.rerank.enabled", "false");
        props.put("ranking.topK", "10");
        props.put("search.topK", "10");
        return props;
    }

    private static List<Integer> ids(List<RankedDocument> ranked) {
        return ranked.stream().map(RankedDocument::documentId).toList();
    }

    @Test
    void rrfSurfacesADocumentThatSharesNoTermWithTheQuery(@TempDir Path tmp) throws Exception {
        assumeTrue(SentenceEncoder.isAvailable(MODEL_DIR), "encoder model not present");
        Path vectorFile = buildVectors(tmp);

        // BM25: the word is absent from the corpus, so there is nothing to score.
        SearchEngine lexical = SearchEngineBuilder.build(
                corpus(), new Configuration(baseProps()), tmp.resolve("index-bm25")).engine();
        List<Integer> bm25Ids = ids(lexical.retrieveCandidates("automotive", 10).ranked());
        assertFalse(bm25Ids.contains(CAR_DOC_ID),
                "BM25 cannot reach a document with zero query-term overlap, got " + bm25Ids);

        // RRF: the semantic channel supplies the ranking BM25 has no basis for.
        Map<String, String> rrfProps = baseProps();
        rrfProps.put("ranking.mode", "rrf");
        rrfProps.put("ranking.semantic.vectors", vectorFile.toString());
        rrfProps.put("ranking.semantic.modelDir", MODEL_DIR.toString());

        SearchEngine fused = SearchEngineBuilder.build(
                corpus(), new Configuration(rrfProps), tmp.resolve("index-rrf")).engine();
        List<Integer> rrfIds = ids(fused.retrieveCandidates("automotive", 10).ranked());

        assertFalse(rrfIds.isEmpty(), "RRF must answer a query BM25 cannot");
        assertEquals(CAR_DOC_ID, rrfIds.get(0),
                "the car document must lead for 'automotive', got order " + rrfIds);
    }

    @Test
    void rrfKeepsAnsweringOrdinaryLexicalQueries(@TempDir Path tmp) throws Exception {
        assumeTrue(SentenceEncoder.isAvailable(MODEL_DIR), "encoder model not present");
        Path vectorFile = buildVectors(tmp);

        Map<String, String> rrfProps = baseProps();
        rrfProps.put("ranking.mode", "rrf");
        rrfProps.put("ranking.semantic.vectors", vectorFile.toString());
        rrfProps.put("ranking.semantic.modelDir", MODEL_DIR.toString());

        SearchEngine fused = SearchEngineBuilder.build(
                corpus(), new Configuration(rrfProps), tmp.resolve("index-rrf")).engine();

        // Fusion must not damage a query the lexical side answers well: "yeast"
        // occurs in exactly one document.
        List<Integer> ranked = ids(fused.retrieveCandidates("yeast", 10).ranked());
        assertEquals(BREAD_DOC_ID, ranked.get(0),
                "an unambiguous lexical hit must survive fusion, got " + ranked);
    }

    @Test
    void bm25ModeIgnoresTheSemanticChannelEntirely(@TempDir Path tmp) throws Exception {
        assumeTrue(SentenceEncoder.isAvailable(MODEL_DIR), "encoder model not present");
        Path vectorFile = buildVectors(tmp);

        // Vectors configured but the mode left at its default: the results must be
        // byte-for-byte the lexical ones, so the default path is provably untouched.
        Map<String, String> props = baseProps();
        props.put("ranking.semantic.vectors", vectorFile.toString());

        SearchEngine engine = SearchEngineBuilder.build(
                corpus(), new Configuration(props), tmp.resolve("index")).engine();
        SearchEngine plain = SearchEngineBuilder.build(
                corpus(), new Configuration(baseProps()), tmp.resolve("index-plain")).engine();

        for (String query : List.of("automotive", "yeast", "engine oil", "tomato")) {
            assertEquals(ids(plain.retrieveCandidates(query, 10).ranked()),
                    ids(engine.retrieveCandidates(query, 10).ranked()),
                    "default mode must not consult the semantic channel for: " + query);
        }
    }

    /**
     * The full promotion chain in one test, on the real {@link SearchEngine}:
     * BM25 cannot retrieve the document, semantic can, the union contains it but
     * cannot rank it, and RRF promotes it into the final results.
     */
    @Test
    void theFullPromotionChainForASemanticOnlyDocument(@TempDir Path tmp) throws Exception {
        assumeTrue(SentenceEncoder.isAvailable(MODEL_DIR), "encoder model not present");
        Path vectorFile = buildVectors(tmp);

        String query = "automotive";

        // 1. BM25 cannot retrieve it — no query term occurs in the document.
        SearchEngine lexical = SearchEngineBuilder.build(
                corpus(), new Configuration(baseProps()), tmp.resolve("index-bm25")).engine();
        List<Integer> bm25Ids = ids(lexical.retrieveCandidates(query, 10).ranked());
        assertFalse(bm25Ids.contains(CAR_DOC_ID), "step 1: BM25 must not retrieve it");

        // 2. Semantic retrieval can.
        List<Integer> semanticIds;
        // SemanticRetriever.close() closes the encoder it was handed, so the
        // encoder must NOT also be a try-with-resources subject.
        SentenceEncoder encoder = SentenceEncoder.load(MODEL_DIR,
                SentenceEncoder.DEFAULT_MAX_TOKENS, SentenceEncoder.MINILM_DIMENSION, 2);
        try (SemanticRetriever retriever = SemanticRetriever.load(encoder, vectorFile)) {
            semanticIds = retriever.retrieve(query, 10).stream()
                    .map(SemanticRetriever.Candidate::documentId).toList();
        }
        assertTrue(semanticIds.contains(CAR_DOC_ID), "step 2: semantic must retrieve it");

        // 3. The union contains it, but only by appending — its position is behind
        //    every lexical result, which is exactly what the BEIR union measured.
        LinkedHashSet<Integer> union = new LinkedHashSet<>(bm25Ids);
        union.addAll(semanticIds);
        assertTrue(union.contains(CAR_DOC_ID), "step 3: the union must contain it");

        // 4. RRF promotes it into the final production result.
        Map<String, String> rrfProps = baseProps();
        rrfProps.put("ranking.mode", "rrf");
        rrfProps.put("ranking.semantic.vectors", vectorFile.toString());
        rrfProps.put("ranking.semantic.modelDir", MODEL_DIR.toString());
        SearchEngine fused = SearchEngineBuilder.build(
                corpus(), new Configuration(rrfProps), tmp.resolve("index-rrf")).engine();

        List<Integer> rrfIds = ids(fused.retrieveCandidates(query, 10).ranked());
        assertEquals(CAR_DOC_ID, rrfIds.get(0), "step 4: RRF must promote it, got " + rrfIds);
    }

    /**
     * Guards the failure mode the mission names explicitly: production must not
     * quietly do "BM25 ranking, then append semantic documents". It must compute
     * the same fusion the benchmark computes.
     *
     * <p>Asserts equality against an independently computed RRF over the two
     * rankings, and inequality against the append strategy — so a regression to
     * appending fails loudly instead of looking approximately right.</p>
     */
    @Test
    void productionFusionEqualsAnIndependentlyComputedRrf(@TempDir Path tmp) throws Exception {
        assumeTrue(SentenceEncoder.isAvailable(MODEL_DIR), "encoder model not present");
        Path vectorFile = buildVectors(tmp);

        int lexicalDepth = 10;
        int semanticDepth = 4;
        int k = 60;

        Map<String, String> rrfProps = baseProps();
        rrfProps.put("ranking.mode", "rrf");
        rrfProps.put("ranking.semantic.vectors", vectorFile.toString());
        rrfProps.put("ranking.semantic.modelDir", MODEL_DIR.toString());
        rrfProps.put("ranking.rrf.k", String.valueOf(k));
        rrfProps.put("ranking.semantic.depth", String.valueOf(semanticDepth));
        rrfProps.put("ranking.topK", String.valueOf(lexicalDepth));

        SearchEngine production = SearchEngineBuilder.build(
                corpus(), new Configuration(rrfProps), tmp.resolve("index-rrf")).engine();

        // The same lexical ranking production fuses: identical config, mode left
        // at bm25 so no fusion is applied.
        Map<String, String> lexProps = baseProps();
        lexProps.put("ranking.topK", String.valueOf(lexicalDepth));
        SearchEngine lexicalOnly = SearchEngineBuilder.build(
                corpus(), new Configuration(lexProps), tmp.resolve("index-bm25")).engine();

        SentenceEncoder encoder = SentenceEncoder.load(MODEL_DIR,
                SentenceEncoder.DEFAULT_MAX_TOKENS, SentenceEncoder.MINILM_DIMENSION, 2);
        try (SemanticRetriever retriever = SemanticRetriever.load(encoder, vectorFile)) {

            for (String query : List.of("automotive", "engine oil", "yeast", "sunlight", "purring")) {
                List<Integer> lexicalIds = ids(lexicalOnly.retrieveCandidates(query, lexicalDepth).ranked());
                List<Integer> semanticIds = retriever.retrieve(query, semanticDepth).stream()
                        .map(SemanticRetriever.Candidate::documentId).toList();

                List<Integer> expected = new ReciprocalRankFusion(k).fuseToIds(lexicalIds, semanticIds);
                List<Integer> actual = ids(production.retrieveCandidates(query, lexicalDepth).ranked());

                assertEquals(expected, actual,
                        "production must compute RRF over the same two rankings, query: " + query);

                // And must NOT be the append strategy, whenever the two differ.
                LinkedHashSet<Integer> appended = new LinkedHashSet<>(lexicalIds);
                appended.addAll(semanticIds);
                List<Integer> appendOrder = new ArrayList<>(appended);
                if (!appendOrder.equals(expected)) {
                    assertNotEquals(appendOrder, actual,
                            "production is appending semantic results, not fusing, query: " + query);
                }
            }
        }
    }

    @Test
    void aSemanticModeWithoutItsVectorStoreFailsLoudly(@TempDir Path tmp) {
        assumeTrue(SentenceEncoder.isAvailable(MODEL_DIR), "encoder model not present");
        Map<String, String> props = baseProps();
        props.put("ranking.mode", "rrf");
        // ranking.semantic.vectors deliberately unset.

        IOException e = assertThrows(IOException.class, () -> SearchEngineBuilder.build(
                corpus(), new Configuration(props), tmp.resolve("index")));
        assertTrue(e.getMessage().contains("vector store"),
                "the failure must name the missing artifact, got: " + e.getMessage());
    }
}
