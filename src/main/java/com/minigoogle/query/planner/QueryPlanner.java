package com.minigoogle.query.planner;

import com.minigoogle.query.ast.*;
import com.minigoogle.query.executor.BooleanExecutor;
import com.minigoogle.query.executor.PhraseExecutor;
import com.minigoogle.indexer.inverted.Posting;
import com.minigoogle.indexer.inverted.PostingList;
import com.minigoogle.storage.dictionary.DictionaryEntry;
import com.minigoogle.storage.dictionary.DictionaryReader;
import com.minigoogle.storage.mmap.MemoryMappedIndex;

import com.minigoogle.indexer.normalization.CaseFolder;
import com.minigoogle.indexer.normalization.UnicodeNormalizer;
import com.minigoogle.indexer.stemming.PorterStemmer;
import java.util.Map;
import java.util.List;
import java.util.Arrays;

/**
 * Implements {@link QueryVisitor} to walk the AST and execute each node against the index.
 * Translates parsed query nodes into posting list operations by resolving terms through the
 * dictionary, normalizing and stemming words, and delegating boolean/phrase logic to the
 * appropriate executor.
 */
public class QueryPlanner implements QueryVisitor<PostingList> {

    private final MemoryMappedIndex index;
    private final Map<String, DictionaryEntry> dictionary;
    private final BooleanExecutor booleanExecutor = new BooleanExecutor();
    private final PhraseExecutor phraseExecutor = new PhraseExecutor();
    private final PostingList universe;

    private final UnicodeNormalizer normalizer = new UnicodeNormalizer();
    private final CaseFolder caseFolder = new CaseFolder();
    private final PorterStemmer stemmer = new PorterStemmer();

    /**
     * Per-query memo of term → posting list, or null on a shared planner.
     *
     * <p>A single query reads the same term's postings more than once: the
     * boolean pass resolves every word leaf of the AST, and the ranking stage
     * then resolves each leaf again to collect per-term postings. Each read is a
     * full deserialization of the list from the mapped file. Memoizing within one
     * query removes the repeat reads.</p>
     *
     * <p>The memo lives on a short-lived planner created by {@link #forQuery()},
     * so it is confined to the executing thread and is released with that
     * planner. The shared planner keeps this null and stays stateless, which is
     * what makes concurrent queries safe.</p>
     */
    private final Map<String, PostingList> memo;

    public QueryPlanner(MemoryMappedIndex index, Map<String, DictionaryEntry> dictionary, int documentCount) {
        this.index = index;
        this.dictionary = dictionary;
        this.universe = buildUniverse(documentCount);
        this.memo = null;
    }

    /**
     * Creates a query-scoped planner that memoizes term lookups. Shares the
     * index, dictionary and document universe with this planner (no copying),
     * and adds a memo private to the returned instance.
     *
     * <p>Safe because the index and dictionary are immutable for this planner's
     * lifetime: a rebuild publishes a whole new engine rather than mutating this
     * one, so a memo can never serve a stale posting list.</p>
     */
    public QueryPlanner forQuery() {
        return new QueryPlanner(this);
    }

    private QueryPlanner(QueryPlanner base) {
        this.index = base.index;
        this.dictionary = base.dictionary;
        this.universe = base.universe;
        this.memo = new java.util.HashMap<>();
    }

    /**
     * The universe of every document in the index (document ids are assigned
     * contiguously from 1 by the {@link com.minigoogle.indexer.IndexBuilder}),
     * used as the domain against which NOT is evaluated.
     */
    private static PostingList buildUniverse(int documentCount) {
        List<Posting> postings = new java.util.ArrayList<>(documentCount);
        for (int docId = 1; docId <= documentCount; docId++) {
            postings.add(new Posting(docId));
        }
        return new PostingList(postings);
    }

    public PostingList execute(QueryNode node) {
        if (node == null) return new PostingList();
        return node.accept(this);
    }

    @Override
    public PostingList visit(WordNode node) {
        String word = node.word();
        String processed = stemmer.stem(caseFolder.fold(normalizer.normalize(word)));
        if (processed == null || processed.isEmpty()) {
            return new PostingList();
        }
        if (memo != null) {
            PostingList cached = memo.get(processed);
            if (cached != null) {
                return cached;
            }
        }
        DictionaryEntry entry = dictionary.get(processed);
        if (entry == null) {
            // Cache the miss too: an absent term is resolved once per query
            // rather than on every occurrence.
            PostingList empty = new PostingList();
            if (memo != null) {
                memo.put(processed, empty);
            }
            return empty;
        }
        try {
            PostingList pl = index.readPostingList(entry.postingOffset());
            if (memo != null) {
                memo.put(processed, pl);
            }
            return pl;
        } catch (java.io.IOException e) {
            throw new RuntimeException("Failed to read posting list at offset " + entry.postingOffset(), e);
        }
    }

    @Override
    public PostingList visit(PhraseNode node) {
        String phrase = node.phrase();
        String[] words = phrase.split("\\s+");
        if (words.length == 0) return new PostingList();

        PostingList current = visit(new WordNode(words[0]));
        for (int i = 1; i < words.length; i++) {
            PostingList next = visit(new WordNode(words[i]));
            // Distance is 1 for adjacent words, but we should track accumulated distance.
            // Simplified: we pass i as the relative distance from the first word.
            // A more robust implementation would update the base positions.
            // For now, distance = 1 incrementally works if we assume step-by-step verification
            // and we keep positions of the *last* matched term. Wait, if we keep positions of the
            // last matched term, the next term is distance 1 from *it*.
            current = phraseExecutor.intersectPhrase(current, next, 1);
            if (current.getPostings().isEmpty()) break;
        }
        return current;
    }

    @Override
    public PostingList visit(AndNode node) {
        // Optimization: In a real system, we'd estimate sizes before executing both,
        // but for now we execute and then intersect.
        PostingList left = node.left().accept(this);
        PostingList right = node.right().accept(this);
        return booleanExecutor.intersect(left, right);
    }

    @Override
    public PostingList visit(OrNode node) {
        PostingList left = node.left().accept(this);
        PostingList right = node.right().accept(this);
        return booleanExecutor.union(left, right);
    }

    @Override
    public PostingList visit(NotNode node) {
        // NOT x = universe \ x. Evaluated against the full document universe so
        // that root-level NOT works (e.g. "NOT java") and nested negation
        // ("a AND NOT b") composes through the set-algebra in intersect().
        PostingList operand = node.operand().accept(this);
        return booleanExecutor.difference(universe, operand);
    }
}
