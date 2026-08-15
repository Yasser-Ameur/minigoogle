package com.minigoogle.ranking.fusion;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reciprocal Rank Fusion: combines several rankings using only the position a
 * document occupies in each, never its score.
 *
 * <pre>
 *   RRF(d) = SUM over rankings i of  1 / (k + rank_i(d))
 * </pre>
 *
 * with {@code rank = 1} for the first result. A document absent from a ranking
 * contributes nothing from it — no sentinel rank, no imputed score. Higher fused
 * values rank first.
 *
 * <h2>Why rank fusion rather than score fusion</h2>
 * BM25 is an unbounded sum of IDF-weighted term contributions; cosine similarity
 * is bounded in [-1, 1]. Adding them, even after min-max normalization, makes the
 * result depend on the score <em>spread</em> within each candidate set, which
 * varies per query. This project has already measured what that costs: an
 * uncalibrated semantic score replacing the lexical ordering dropped scifact
 * NDCG@10 from 0.6015 to 0.3611. RRF sidesteps calibration entirely by discarding
 * magnitudes and keeping only order.
 *
 * <h2>The constant k</h2>
 * {@code k} damps the influence of top ranks. Small k makes rank 1 overwhelming
 * (1/(k+1) is large relative to deeper ranks); large k flattens the curve so
 * deep agreement between rankings matters more. The literature's customary
 * default is 60, which this class uses, but the value is a tunable and is swept
 * in the benchmarks rather than assumed.
 *
 * <h2>Ties</h2>
 * Documents with equal fused scores are ordered by first appearance across the
 * input rankings (input order, then ranking order). That makes the output a
 * deterministic function of the inputs — important because a benchmark that
 * reorders ties arbitrarily is not reproducible.
 */
public final class ReciprocalRankFusion {

    /** The customary default from the original RRF paper. */
    public static final int DEFAULT_K = 60;

    private final int k;

    public ReciprocalRankFusion() {
        this(DEFAULT_K);
    }

    /**
     * @param k the reciprocal-rank constant; must be positive, since k = 0 makes
     *          the top-ranked document's contribution 1/1 and k &lt; 0 can divide
     *          by zero at rank {@code -k}
     */
    public ReciprocalRankFusion(int k) {
        if (k <= 0) {
            throw new IllegalArgumentException("RRF k must be positive, got " + k);
        }
        this.k = k;
    }

    /** A fused result: the document, its combined score, and its input ranks. */
    public record Fused(int documentId, double score, List<Integer> ranks) {
    }

    /**
     * Fuses the given rankings.
     *
     * @param rankings each an ordered list of document ids, best first; may
     *                 contain duplicates, which are ignored after the first
     *                 occurrence (a document's rank in a ranking is its best
     *                 position in it)
     * @return every document appearing in any ranking, ordered by fused score
     */
    public List<Fused> fuse(List<List<Integer>> rankings) {
        Map<Integer, double[]> scores = new LinkedHashMap<>();
        Map<Integer, List<Integer>> perDocRanks = new LinkedHashMap<>();

        for (int r = 0; r < rankings.size(); r++) {
            List<Integer> ranking = rankings.get(r);
            if (ranking == null) {
                continue;
            }
            for (int position = 0; position < ranking.size(); position++) {
                int docId = ranking.get(position);
                int rank = position + 1;

                List<Integer> ranks = perDocRanks.computeIfAbsent(docId, id -> {
                    List<Integer> fresh = new ArrayList<>(rankings.size());
                    for (int i = 0; i < rankings.size(); i++) {
                        fresh.add(0);   // 0 means "absent from this ranking"
                    }
                    return fresh;
                });
                if (ranks.get(r) != 0) {
                    // Already seen in this ranking: keep the better (earlier) rank.
                    continue;
                }
                ranks.set(r, rank);
                scores.computeIfAbsent(docId, id -> new double[1])[0] += 1.0 / (k + rank);
            }
        }

        List<Fused> fused = new ArrayList<>(scores.size());
        for (Map.Entry<Integer, double[]> e : scores.entrySet()) {
            fused.add(new Fused(e.getKey(), e.getValue()[0], List.copyOf(perDocRanks.get(e.getKey()))));
        }
        // Descending score; ties keep insertion order, which is first-appearance
        // order across the inputs. List.sort is stable, so this is deterministic.
        fused.sort(Comparator.comparingDouble(Fused::score).reversed());
        return fused;
    }

    /** Convenience for the two-ranking case, which is the hybrid retrieval shape. */
    public List<Fused> fuse(List<Integer> first, List<Integer> second) {
        return fuse(List.of(first == null ? List.of() : first,
                second == null ? List.of() : second));
    }

    /** @return just the fused document ids, best first. */
    public List<Integer> fuseToIds(List<Integer> first, List<Integer> second) {
        List<Fused> fused = fuse(first, second);
        List<Integer> ids = new ArrayList<>(fused.size());
        for (Fused f : fused) {
            ids.add(f.documentId());
        }
        return ids;
    }

    public int k() {
        return k;
    }
}
