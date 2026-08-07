package com.minigoogle.corpus;

import com.minigoogle.crawler.model.ParsedDocument;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * A fully loaded BEIR dataset: the corpus documents with their deterministic
 * internal ids, the original {@code _id}→internal-id mapping, the queries, and
 * the relevance judgments per split.
 *
 * <p>Internal document ids are 1-based ordinals of the corpus in the order the
 * corpus file lists them — the same convention {@code IndexBuilder} assigns — so
 * the qrels (which reference {@code _id}) can be resolved to the exact ids the
 * engine scores. Everything is deterministic: the same corpus file always
 * produces the same ids, which is what makes re-runs reproducible.</p>
 */
public record BeirCorpus(
        String dataset,
        List<ParsedDocument> docs,
        Map<String, Integer> beirIdToDocId,
        List<BeirQuery> queries,
        Map<String, Map<String, Map<String, Integer>>> qrels,
        BeirCorpusReader.Result stats,
        Path sourceDir) {

    /**
     * Resolves a qrels split (e.g. {@code test}) to the engine-internal
     * document ids: {@code qid → (docId → grade)}. Only judgments whose
     * documents were actually indexed are kept; grades of 0 are dropped.
     */
    public Map<String, Map<Integer, Integer>> resolveQrels(String split) {
        Map<String, Map<String, Integer>> raw = qrels.getOrDefault(split, Map.of());
        Map<String, Map<Integer, Integer>> resolved = new java.util.HashMap<>();
        for (Map.Entry<String, Map<String, Integer>> q : raw.entrySet()) {
            Map<Integer, Integer> docGrades = new java.util.HashMap<>();
            for (Map.Entry<String, Integer> d : q.getValue().entrySet()) {
                Integer docId = beirIdToDocId.get(d.getKey());
                if (docId != null && d.getValue() > 0) {
                    docGrades.put(docId, d.getValue());
                }
            }
            if (!docGrades.isEmpty()) {
                resolved.put(q.getKey(), docGrades);
            }
        }
        return resolved;
    }

    /**
     * @return Number of (query, relevant document) pairs that resolved to
     *         indexed documents for the given split, or 0 if the split is absent.
     */
    public long resolvedRelJudgments(String split) {
        return resolveQrels(split).values().stream().mapToLong(Map::size).sum();
    }

    public boolean hasSplit(String split) {
        return qrels.containsKey(split) && !qrels.get(split).isEmpty();
    }
}
