package com.minigoogle.semantic.rag;

import com.minigoogle.semantic.VectorIndex;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the reusable hybrid result merge in {@link RetrievalPipeline}.
 */
class RetrievalPipelineMergeTest {

    private VectorIndex.VectorResult result(int id, double score) {
        return new VectorIndex.VectorResult(id, score, "doc" + id);
    }

    @Test
    void testBlendNormalizesByMaxScore() {
        List<VectorIndex.VectorResult> lexical = List.of(result(1, 10.0), result(2, 5.0));
        List<VectorIndex.VectorResult> semantic = List.of(result(1, 20.0), result(3, 10.0));

        List<VectorIndex.VectorResult> merged = RetrievalPipeline.mergeResults(lexical, semantic, 10, 0.5);

        // id1: 0.5 * (10/10) + 0.5 * (20/20) = 1.0
        assertEquals(1, merged.get(0).id());
        assertEquals(1.0, merged.get(0).score(), 0.001);
        // id2: 0.5 * (5/10) = 0.25 ; id3: 0.5 * (10/20) = 0.25
        assertEquals(3, merged.size());
        assertEquals(0.25, merged.get(1).score(), 0.001);
        assertEquals(0.25, merged.get(2).score(), 0.001);
    }

    @Test
    void testDeduplicatesDocumentsInBothLists() {
        List<VectorIndex.VectorResult> lexical = List.of(result(1, 8.0));
        List<VectorIndex.VectorResult> semantic = List.of(result(1, 4.0));
        List<VectorIndex.VectorResult> merged = RetrievalPipeline.mergeResults(lexical, semantic, 10, 0.5);
        assertEquals(1, merged.size());
        assertEquals(1, merged.get(0).id());
        // 0.5 * (8/8) + 0.5 * (4/4) = 1.0
        assertEquals(1.0, merged.get(0).score(), 0.001);
    }

    @Test
    void testPureLexicalWeightIgnoresSemantic() {
        List<VectorIndex.VectorResult> lexical = List.of(result(1, 9.0));
        List<VectorIndex.VectorResult> semantic = List.of(result(2, 100.0));
        List<VectorIndex.VectorResult> merged = RetrievalPipeline.mergeResults(lexical, semantic, 10, 1.0);
        assertEquals(1, merged.get(0).id());
        assertEquals(1.0, merged.get(0).score(), 0.001);
    }

    @Test
    void testPureSemanticWeightIgnoresLexical() {
        List<VectorIndex.VectorResult> lexical = List.of(result(1, 100.0));
        List<VectorIndex.VectorResult> semantic = List.of(result(2, 3.0));
        List<VectorIndex.VectorResult> merged = RetrievalPipeline.mergeResults(lexical, semantic, 10, 0.0);
        assertEquals(2, merged.get(0).id());
        assertEquals(1.0, merged.get(0).score(), 0.001);
    }

    @Test
    void testTopKLimitsMergedResults() {
        List<VectorIndex.VectorResult> lexical = new ArrayList<>();
        List<VectorIndex.VectorResult> semantic = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            lexical.add(result(i, i * 10.0));
            semantic.add(result(i + 10, i * 5.0));
        }
        List<VectorIndex.VectorResult> merged = RetrievalPipeline.mergeResults(lexical, semantic, 5, 0.5);
        assertEquals(5, merged.size());
    }

    @Test
    void testSemanticOnlyResultsAreRetained() {
        List<VectorIndex.VectorResult> lexical = List.of(result(1, 10.0));
        List<VectorIndex.VectorResult> semantic = List.of(result(99, 12.0));
        // Semantic weight dominates (lexicalWeight 0.4), so the semantic-only doc
        // ranks first while the lexical doc is still retained.
        List<VectorIndex.VectorResult> merged = RetrievalPipeline.mergeResults(lexical, semantic, 10, 0.4);
        assertEquals(2, merged.size());
        assertEquals(99, merged.get(0).id());
        assertEquals(1, merged.get(1).id());
        // 0.4 * (10/10) = 0.4 for lexical doc; 0.6 * (12/12) = 0.6 for semantic-only doc.
        assertEquals(0.4, merged.get(1).score(), 0.001);
        assertEquals(0.6, merged.get(0).score(), 0.001);
    }

    @Test
    void testEmptyInputs() {
        assertTrue(RetrievalPipeline.mergeResults(List.of(), List.of(), 10, 0.5).isEmpty());
        List<VectorIndex.VectorResult> merged = RetrievalPipeline.mergeResults(List.of(result(1, 5.0)), List.of(), 10, 0.5);
        assertEquals(1, merged.size());
        assertEquals(0.5, merged.get(0).score(), 0.001);
    }

    @Test
    void testTiesBrokenByDocumentId() {
        List<VectorIndex.VectorResult> lexical = List.of(result(2, 10.0), result(1, 10.0));
        List<VectorIndex.VectorResult> merged = RetrievalPipeline.mergeResults(lexical, List.of(), 10, 0.5);
        assertEquals(1, merged.get(0).id());
        assertEquals(2, merged.get(1).id());
    }

    @Test
    void testMetadataPreservedFromFirstOccurrence() {
        List<VectorIndex.VectorResult> lexical = List.of(result(1, 8.0));
        List<VectorIndex.VectorResult> semantic = List.of(new VectorIndex.VectorResult(1, 4.0, "shadow"));
        List<VectorIndex.VectorResult> merged = RetrievalPipeline.mergeResults(lexical, semantic, 10, 0.5);
        assertEquals("doc1", merged.get(0).metadata());
    }
}
