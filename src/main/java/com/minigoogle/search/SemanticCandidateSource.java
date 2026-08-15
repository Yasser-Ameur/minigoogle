package com.minigoogle.search;

import java.util.List;

/**
 * The second retrieval channel, as the search engine sees it: a query in,
 * document ids out, best first.
 *
 * <p>Narrow on purpose. The engine needs an <em>ordering</em> and nothing else —
 * Reciprocal Rank Fusion consumes positions, never similarities — so exposing
 * scores here would invite exactly the score mixing this ranking path was built
 * to avoid. It also keeps ONNX Runtime out of the search package: the encoder is
 * an implementation detail behind this interface.</p>
 */
@FunctionalInterface
public interface SemanticCandidateSource {

    /**
     * @param k maximum number of ids to return
     * @return document ids ordered best first; empty if the source has nothing
     *         for this query
     */
    List<Integer> retrieve(String query, int k);
}
