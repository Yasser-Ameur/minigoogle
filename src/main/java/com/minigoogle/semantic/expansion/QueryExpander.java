package com.minigoogle.semantic.expansion;

import com.minigoogle.query.ast.AndNode;
import com.minigoogle.query.ast.NotNode;
import com.minigoogle.query.ast.OrNode;
import com.minigoogle.query.ast.PhraseNode;
import com.minigoogle.query.ast.QueryNode;
import com.minigoogle.query.ast.QueryVisitor;
import com.minigoogle.query.ast.WordNode;
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
     * Expands a parsed query tree: each word leaf with known synonyms is
     * replaced by an OR of the original word and up to {@code maxExpansions}
     * synonyms. Phrase nodes and boolean structure are preserved verbatim, so
     * quoted phrases keep their exact-match semantics and explicit AND/OR/NOT
     * grouping is untouched. This is the correct place for expansion — the
     * query model — instead of on a raw string, which cannot represent phrases.
     *
     * @param ast            The parsed query tree.
     * @param maxExpansions  Maximum number of expansion terms to add per word.
     * @return The expanded query tree.
     */
    public QueryNode expand(QueryNode ast, int maxExpansions) {
        if (ast == null) {
            return null;
        }
        return ast.accept(new ExpansionVisitor(maxExpansions));
    }

    /**
     * Returns the synonyms for a single term, excluding the term itself.
     */
    public Set<String> synonymsFor(String term) {
        Set<String> synonyms = synonymGraph.getSynonyms(term);
        synonyms.remove(term.toLowerCase(Locale.ROOT));
        return synonyms;
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

    /**
     * Rewrites a query tree, OR-ing synonyms into each word leaf while leaving
     * phrase nodes and operators intact.
     */
    private final class ExpansionVisitor implements QueryVisitor<QueryNode> {
        private final int maxExpansions;

        ExpansionVisitor(int maxExpansions) {
            this.maxExpansions = maxExpansions;
        }

        @Override
        public QueryNode visit(WordNode node) {
            Set<String> synonyms = synonymsFor(node.word());
            if (synonyms.isEmpty()) {
                return node;
            }
            QueryNode result = new WordNode(node.word());
            int added = 0;
            for (String synonym : synonyms) {
                if (added >= maxExpansions) {
                    break;
                }
                result = new OrNode(result, new WordNode(synonym));
                added++;
            }
            return result;
        }

        @Override
        public QueryNode visit(PhraseNode node) {
            return node;
        }

        @Override
        public QueryNode visit(AndNode node) {
            return new AndNode(node.left().accept(this), node.right().accept(this));
        }

        @Override
        public QueryNode visit(OrNode node) {
            return new OrNode(node.left().accept(this), node.right().accept(this));
        }

        @Override
        public QueryNode visit(NotNode node) {
            return new NotNode(node.operand().accept(this));
        }
    }
}
