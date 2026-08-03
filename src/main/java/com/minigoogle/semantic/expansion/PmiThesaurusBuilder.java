package com.minigoogle.semantic.expansion;

import com.minigoogle.crawler.model.ParsedDocument;
import com.minigoogle.indexer.normalization.CaseFolder;
import com.minigoogle.indexer.normalization.UnicodeNormalizer;
import com.minigoogle.indexer.stopwords.StopWordFilter;
import com.minigoogle.indexer.tokenizer.Tokenizer;
import com.minigoogle.semantic.synonym.SynonymGraph;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds a corpus-derived synonym graph using pointwise mutual information.
 *
 * <p>Terms that co-occur within a sliding window across the corpus are scored
 * with PMI: {@code log(P(a,b) / (P(a) * P(b)))}. The top {@code maxNeighbors}
 * terms per term (above {@code pmiThreshold}) become bidirectional synonym
 * edges. Unlike a hand-written synonym list this graph reflects the actual
 * vocabulary and topical structure of the indexed documents.</p>
 */
public final class PmiThesaurusBuilder {

    private final int windowSize;
    private final double pmiThreshold;
    private final int maxNeighbors;

    private final Tokenizer tokenizer = new Tokenizer();
    private final UnicodeNormalizer normalizer = new UnicodeNormalizer();
    private final CaseFolder caseFolder = new CaseFolder();
    private final StopWordFilter stopWordFilter = new StopWordFilter();

    /**
     * Creates a builder with window size 10, PMI threshold 1.0, and up to 5
     * neighbors per term.
     */
    public PmiThesaurusBuilder() {
        this(10, 1.0, 5);
    }

    /**
     * Creates a builder with explicit parameters.
     *
     * @param windowSize   Co-occurrence window length in tokens (>= 2).
     * @param pmiThreshold Minimum PMI for a pair to become an edge (>= 0).
     * @param maxNeighbors Maximum neighbors kept per term (>= 1).
     */
    public PmiThesaurusBuilder(int windowSize, double pmiThreshold, int maxNeighbors) {
        if (windowSize < 2) {
            throw new IllegalArgumentException("windowSize must be >= 2");
        }
        if (pmiThreshold < 0) {
            throw new IllegalArgumentException("pmiThreshold must be >= 0");
        }
        if (maxNeighbors < 1) {
            throw new IllegalArgumentException("maxNeighbors must be >= 1");
        }
        this.windowSize = windowSize;
        this.pmiThreshold = pmiThreshold;
        this.maxNeighbors = maxNeighbors;
    }

    /**
     * Builds a {@link SynonymGraph} from the corpus.
     *
     * @param documents The indexed documents.
     * @return A synonym graph populated with PMI-derived edges.
     */
    public SynonymGraph build(List<ParsedDocument> documents) {
        List<List<String>> tokenizedDocs = tokenize(documents);
        CooccurrenceStats stats = countCooccurrences(tokenizedDocs);
        return buildGraph(stats);
    }

    private SynonymGraph buildGraph(CooccurrenceStats stats) {
        SynonymGraph graph = new SynonymGraph();
        double totalWindows = stats.totalWindows;
        for (Map.Entry<String, Integer> entry : stats.termWindows.entrySet()) {
            String term = entry.getKey();
            List<ScoredTerm> scored = new ArrayList<>();
            Set<String> pairs = stats.pairsByTerm.get(term);
            if (pairs == null) continue;
            for (String other : pairs) {
                if (other.equals(term)) continue;
                int cooccur = stats.pairWindows.getOrDefault(pairKey(term, other), 0);
                double pmi = pmi(entry.getValue(), stats.termWindows.getOrDefault(other, 0), cooccur, totalWindows);
                if (pmi >= pmiThreshold) {
                    scored.add(new ScoredTerm(other, pmi));
                }
            }
            scored.sort(Comparator
                    .comparingDouble(ScoredTerm::score).reversed()
                    .thenComparing(ScoredTerm::term));
            int kept = 0;
            for (ScoredTerm s : scored) {
                if (kept >= maxNeighbors) break;
                graph.addSynonym(term, s.term);
                kept++;
            }
        }
        return graph;
    }

    private double pmi(int termAWindows, int termBWindows, int cooccurWindows, double totalWindows) {
        if (totalWindows <= 0 || termAWindows <= 0 || termBWindows <= 0 || cooccurWindows <= 0) {
            return Double.NEGATIVE_INFINITY;
        }
        double pA = termAWindows / totalWindows;
        double pB = termBWindows / totalWindows;
        double pAB = cooccurWindows / totalWindows;
        return Math.log(pAB / (pA * pB));
    }

    private List<List<String>> tokenize(List<ParsedDocument> documents) {
        List<List<String>> result = new ArrayList<>();
        for (ParsedDocument doc : documents) {
            String text = (doc.title() != null ? doc.title() + " " : "") + doc.text();
            List<String> tokens = tokenizer.tokenize(normalizer.normalize(text));
            List<String> cleaned = new ArrayList<>();
            for (String token : tokens) {
                String folded = caseFolder.fold(token);
                if (folded.length() > 1 && !stopWordFilter.isStopWord(folded)) {
                    cleaned.add(folded);
                }
            }
            result.add(cleaned);
        }
        return result;
    }

    private CooccurrenceStats countCooccurrences(List<List<String>> docs) {
        CooccurrenceStats stats = new CooccurrenceStats();
        for (List<String> doc : docs) {
            for (int start = 0; start <= doc.size() - windowSize; start++) {
                stats.totalWindows++;
                Set<String> inWindow = new HashSet<>(doc.subList(start, start + windowSize));
                for (String term : inWindow) {
                    stats.termWindows.merge(term, 1, Integer::sum);
                    stats.pairsByTerm.computeIfAbsent(term, k -> new HashSet<>());
                }
                List<String> terms = new ArrayList<>(inWindow);
                for (int i = 0; i < terms.size(); i++) {
                    for (int j = i + 1; j < terms.size(); j++) {
                        String a = terms.get(i);
                        String b = terms.get(j);
                        String key = pairKey(a, b);
                        stats.pairWindows.merge(key, 1, Integer::sum);
                        stats.pairsByTerm.computeIfAbsent(a, k -> new HashSet<>()).add(b);
                        stats.pairsByTerm.computeIfAbsent(b, k -> new HashSet<>()).add(a);
                    }
                }
            }
        }
        return stats;
    }

    private static String pairKey(String a, String b) {
        return a.compareTo(b) < 0 ? a + "\u0000" + b : b + "\u0000" + a;
    }

    private record ScoredTerm(String term, double score) {
    }

    private static final class CooccurrenceStats {
        double totalWindows;
        final Map<String, Integer> termWindows = new HashMap<>();
        final Map<String, Integer> pairWindows = new HashMap<>();
        final Map<String, Set<String>> pairsByTerm = new HashMap<>();
    }
}
