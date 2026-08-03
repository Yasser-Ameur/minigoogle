package com.minigoogle.semantic.expansion;

import com.minigoogle.semantic.synonym.SynonymGraph;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Expands queries with related terms to improve recall.
 *
 * <p>Uses a {@link SynonymGraph} to discover synonyms for each query term,
 * then returns a combined list of original terms plus their expansions.
 * Expansion is capped at {@code maxExpansions} to prevent query drift.</p>
 *
 * <p>Pre-loaded with common search synonyms covering frequent queries.</p>
 */
public class QueryExpander {

    private final SynonymGraph synonymGraph;

    /**
     * Creates a query expander with a fresh synonym graph pre-loaded with defaults.
     */
    public QueryExpander() {
        this.synonymGraph = new SynonymGraph();
        loadDefaultSynonyms();
    }

    /**
     * Creates a query expander using a provided synonym graph.
     *
     * <p>No hard-coded defaults are added; expansion uses only the supplied
     * graph (e.g. a corpus-derived PMI thesaurus).</p>
     *
     * @param synonymGraph The synonym graph to use for expansion.
     */
    public QueryExpander(SynonymGraph synonymGraph) {
        this.synonymGraph = synonymGraph;
    }

    /**
     * Expands the given query string with related terms.
     *
     * @param query          The original query text.
     * @param maxExpansions  Maximum number of expansion terms to add.
     * @return A list of expanded terms (original words included).
     */
    public List<String> expand(String query, int maxExpansions) {
        String[] terms = query.toLowerCase(Locale.ROOT).split("\\s+");
        Set<String> expanded = new LinkedHashSet<>(Arrays.asList(terms));
        int remaining = maxExpansions;

        for (String term : terms) {
            if (remaining <= 0) break;
            Set<String> synonyms = synonymGraph.getSynonyms(term);
            for (String synonym : synonyms) {
                if (remaining <= 0) break;
                if (expanded.add(synonym)) {
                    remaining--;
                }
            }
        }

        return new ArrayList<>(expanded);
    }

    /**
     * Adds a custom synonym relationship to the expander.
     *
     * @param term     The base term.
     * @param synonyms The related terms.
     */
    public void addSynonyms(String term, String... synonyms) {
        for (String synonym : synonyms) {
            synonymGraph.addSynonym(term.toLowerCase(Locale.ROOT),
                    synonym.toLowerCase(Locale.ROOT));
        }
    }

    private void loadDefaultSynonyms() {
        synonymGraph.addSynonym("car", "automobile");
        synonymGraph.addSynonym("car", "vehicle");
        synonymGraph.addSynonym("fast", "quick");
        synonymGraph.addSynonym("fast", "rapid");
        synonymGraph.addSynonym("happy", "joyful");
        synonymGraph.addSynonym("happy", "cheerful");
        synonymGraph.addSynonym("big", "large");
        synonymGraph.addSynonym("big", "huge");
        synonymGraph.addSynonym("small", "tiny");
        synonymGraph.addSynonym("small", "little");
        synonymGraph.addSynonym("smart", "intelligent");
        synonymGraph.addSynonym("smart", "clever");
        synonymGraph.addSynonym("search", "find");
        synonymGraph.addSynonym("search", "lookup");
        synonymGraph.addSynonym("buy", "purchase");
        synonymGraph.addSynonym("buy", "acquire");
        synonymGraph.addSynonym("home", "house");
        synonymGraph.addSynonym("home", "residence");
        synonymGraph.addSynonym("good", "excellent");
        synonymGraph.addSynonym("good", "great");
        synonymGraph.addSynonym("bad", "poor");
        synonymGraph.addSynonym("bad", "terrible");
        synonymGraph.addSynonym("walk", "stroll");
        synonymGraph.addSynonym("walk", "amble");
        synonymGraph.addSynonym("food", "meal");
        synonymGraph.addSynonym("food", "cuisine");
        synonymGraph.addSynonym("computer", "pc");
        synonymGraph.addSynonym("computer", "machine");
        synonymGraph.addSynonym("run", "jog");
        synonymGraph.addSynonym("run", "sprint");
        synonymGraph.addSynonym("movie", "film");
        synonymGraph.addSynonym("movie", "cinema");
        synonymGraph.addSynonym("begin", "start");
        synonymGraph.addSynonym("end", "finish");
        synonymGraph.addSynonym("help", "assist");
        synonymGraph.addSynonym("help", "support");
    }
}
