package com.minigoogle.search;

import com.minigoogle.indexer.inverted.Posting;
import com.minigoogle.indexer.inverted.PostingList;
import com.minigoogle.indexer.normalization.CaseFolder;
import com.minigoogle.indexer.normalization.UnicodeNormalizer;
import com.minigoogle.indexer.stemming.PorterStemmer;
import com.minigoogle.ml.features.FeatureExtractor;
import com.minigoogle.ml.features.NormalizationContext;
import com.minigoogle.ml.features.RawFeatures;
import com.minigoogle.query.ast.AndNode;
import com.minigoogle.query.ast.NotNode;
import com.minigoogle.query.ast.OrNode;
import com.minigoogle.query.ast.PhraseNode;
import com.minigoogle.query.ast.QueryNode;
import com.minigoogle.query.ast.QueryVisitor;
import com.minigoogle.query.ast.WordNode;
import com.minigoogle.query.lexer.Lexer;
import com.minigoogle.query.lexer.Token;
import com.minigoogle.query.lexer.TokenType;
import com.minigoogle.query.parser.Parser;
import com.minigoogle.query.planner.QueryPlanner;
import com.minigoogle.ranking.model.RankedDocument;
import com.minigoogle.ranking.pipeline.RankingPipeline;
import com.minigoogle.semantic.EmbeddingGenerator;
import com.minigoogle.semantic.VectorIndex;
import com.minigoogle.semantic.expansion.QueryExpander;
import com.minigoogle.semantic.rag.RetrievalPipeline;
import com.minigoogle.semantic.reranking.CrossEncoderRanker;
import com.minigoogle.semantic.spell.SpellCorrector;
import com.minigoogle.storage.dictionary.DictionaryEntry;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The single production retrieval engine shared by every node type and by the
 * evaluation harness.
 *
 * <p>It owns the full retrieval stage — query expansion, parsing, spell
 * correction, BM25 + PageRank ranking, hybrid semantic recall and cross-encoder
 * re-ranking — so standalone search, shard candidate gathering and the offline
 * quality harness all drive one code path. Final learning-to-rank scoring is
 * intentionally delegated to the shared
 * {@link com.minigoogle.ranking.pipeline.GlobalRankingPipeline}.
 */
public class SearchEngine {

    private final QueryPlanner planner;
    private final RankingPipeline ranking;
    private final Lexer lexer;
    private final SpellCorrector spellCorrector;
    private final QueryExpander queryExpander;
    private final CrossEncoderRanker reranker;
    private final VectorIndex vectorIndex;
    private final EmbeddingGenerator embeddingGenerator;
    private final FeatureExtractor featureExtractor;
    private final Map<String, DictionaryEntry> dictionary;
    private final Map<Integer, String> docUrls;
    private final Map<Integer, String> docTitles;
    private final Map<Integer, String> docBodies;
    private final UnicodeNormalizer normalizer;
    private final CaseFolder caseFolder;
    private final PorterStemmer stemmer;
    private final SearchEngineConfig config;

    public SearchEngine(QueryPlanner planner,
                        RankingPipeline ranking,
                        Lexer lexer,
                        SpellCorrector spellCorrector,
                        QueryExpander queryExpander,
                        CrossEncoderRanker reranker,
                        VectorIndex vectorIndex,
                        EmbeddingGenerator embeddingGenerator,
                        FeatureExtractor featureExtractor,
                        Map<String, DictionaryEntry> dictionary,
                        Map<Integer, String> docUrls,
                        Map<Integer, String> docTitles,
                        Map<Integer, String> docBodies,
                        UnicodeNormalizer normalizer,
                        CaseFolder caseFolder,
                        PorterStemmer stemmer,
                        SearchEngineConfig config) {
        this.planner = planner;
        this.ranking = ranking;
        this.lexer = lexer;
        this.spellCorrector = spellCorrector;
        this.queryExpander = queryExpander;
        this.reranker = reranker;
        this.vectorIndex = vectorIndex;
        this.embeddingGenerator = embeddingGenerator;
        this.featureExtractor = featureExtractor;
        this.dictionary = dictionary;
        this.docUrls = docUrls;
        this.docTitles = docTitles;
        this.docBodies = docBodies;
        this.normalizer = normalizer;
        this.caseFolder = caseFolder;
        this.stemmer = stemmer;
        this.config = config;
    }

    /**
     * Retrieval stage shared by standalone and shard execution: query
     * expansion, parsing, spell correction, lexical BM25 + PageRank ranking,
     * hybrid semantic recall and cross-encoder re-ranking. The returned
     * candidates carry no learning-to-rank score — that decision is made by
     * {@link com.minigoogle.ranking.pipeline.GlobalRankingPipeline}
     * (standalone) or by the coordinator (distributed).
     */
    public RetrievalResult retrieveCandidates(String query, int pageSize) {
        // Parse the original query first so quoted phrases and boolean
        // structure are preserved. Expansion then rewrites only the word leaves
        // (OR-ing each word with its synonyms) and never touches phrase nodes,
        // so exact-phrase semantics survive expansion. Adjacent unquoted words
        // keep the parser's documented implicit-AND behavior.
        List<Token> tokens = lexer.tokenize(query);
        QueryNode ast = new Parser(tokens).parse();
        if (ast == null) {
            return new RetrievalResult(List.of(), null);
        }
        // Query-scoped planner: the boolean pass below and the per-term ranking
        // pass further down both resolve the same word leaves, and each
        // resolution deserializes the term's posting list from the mapped file.
        // A planner scoped to this query memoizes those lookups so each term is
        // read once. The memo is confined to this call and released with it.
        QueryPlanner scopedPlanner = planner.forQuery();
        QueryNode expandedAst = queryExpander.expand(ast, config.maxExpansions());
        PostingList results = scopedPlanner.execute(expandedAst);

        // Spell correction fallback
        String didYouMean = null;
        if (results.getPostings().isEmpty()) {
            List<String> correctedPieces = new ArrayList<>();
            List<String> originalPieces = new ArrayList<>();
            for (Token t : tokens) {
                if (t.type() == TokenType.PHRASE) {
                    // Phrases are exact-match units; keep them verbatim.
                    String quoted = "\"" + t.value() + "\"";
                    originalPieces.add(quoted);
                    correctedPieces.add(quoted);
                    continue;
                }
                String stemmed = stemmer.stem(caseFolder.fold(normalizer.normalize(t.value())));
                originalPieces.add(t.value());
                if (stemmed.isEmpty() || dictionary.containsKey(stemmed)) {
                    correctedPieces.add(t.value());
                } else {
                    String fix = spellCorrector.correct(stemmed);
                    correctedPieces.add(fix.equals(stemmed) ? t.value() : fix);
                }
            }
            if (!correctedPieces.equals(originalPieces)) {
                didYouMean = String.join(" ", correctedPieces);
                QueryNode correctedAst = new Parser(lexer.tokenize(didYouMean)).parse();
                expandedAst = queryExpander.expand(correctedAst, config.maxExpansions());
                results = scopedPlanner.execute(expandedAst);
            }
        }

        boolean hybridEnabled = config.hybridEnabled()
                && vectorIndex != null && embeddingGenerator != null;

        if (results.getPostings().isEmpty() && !hybridEnabled) {
            return new RetrievalResult(List.of(), didYouMean);
        }

        // Build per-term posting lists for ranking from the expanded query's
        // word leaves (original terms plus synonyms; phrase contents are
        // handled positionally by the phrase executor and not re-ranked here).
        Map<String, PostingList> candidatePostings = new HashMap<>();
        Map<String, Integer> documentFrequencies = new HashMap<>();

        for (String word : collectWordLeaves(expandedAst)) {
            String processed = stemmer.stem(caseFolder.fold(normalizer.normalize(word)));
            if (!processed.isEmpty()) {
                QueryNode termNode = new WordNode(processed);
                PostingList termResults = scopedPlanner.execute(termNode);
                if (!termResults.getPostings().isEmpty()) {
                    candidatePostings.put(processed, termResults);
                    documentFrequencies.put(processed, termResults.getPostings().size());
                }
            }
        }

        if (candidatePostings.isEmpty()) {
            candidatePostings.put(query, results);
            documentFrequencies.put(query, results.getPostings().size());
        }

        // Rank with BM25 + PageRank
        List<String> queryTerms = collectWordLeaves(expandedAst).stream()
            .map(v -> stemmer.stem(caseFolder.fold(normalizer.normalize(v))))
            .filter(t -> !t.isEmpty())
            .collect(Collectors.toList());

        List<RankedDocument> ranked;
        if (results.getPostings().isEmpty()) {
            // No lexical matches; rely on semantic recall below.
            ranked = new ArrayList<>();
        } else if (queryTerms.isEmpty() || expandedAst instanceof NotNode) {
            // No individual word leaves to score (pure phrase query) or the
            // root is NOT, so the matched set is a complement of the term
            // postings (nothing to BM25-rank against); rank the matched
            // documents directly instead of dropping them.
            ranked = rankedFromPostings(results);
        } else {
            ranked = ranking.rank(queryTerms, candidatePostings, documentFrequencies);
            // The ranking stage scores every document in the candidate term
            // posting lists; restrict its output to the documents that satisfy
            // the boolean query (AND/OR/NOT/phrase) computed above.
            Set<Integer> matchedDocIds = results.getPostings().stream()
                    .map(Posting::getDocumentId)
                    .collect(Collectors.toSet());
            ranked = ranked.stream()
                    .filter(r -> matchedDocIds.contains(r.documentId()))
                    .collect(Collectors.toList());
        }

        // Hybrid recall: merge lexical candidates with semantically-similar
        // documents (which may share no lexical terms with the query) using the
        // normalized score blend from the retrieval pipeline.
        if (hybridEnabled) {
            List<VectorIndex.VectorResult> lexical = ranked.stream()
                    .map(r -> new VectorIndex.VectorResult(r.documentId(), r.finalScore(), r.title()))
                    .collect(Collectors.toList());

            double[] queryVector = embeddingGenerator.embed(query);
            List<VectorIndex.VectorResult> semantic = vectorIndex.search(queryVector, config.fetchK());

            int topK = Math.max(pageSize, ranked.size());
            List<VectorIndex.VectorResult> merged = RetrievalPipeline.mergeResults(
                    lexical, semantic, topK, config.lexicalWeight());

            Map<Integer, RankedDocument> byId = ranked.stream()
                    .collect(Collectors.toMap(RankedDocument::documentId, r -> r));
            ranked = new ArrayList<>();
            for (VectorIndex.VectorResult r : merged) {
                RankedDocument existing = byId.get(r.id());
                if (existing != null) {
                    ranked.add(new RankedDocument(
                            existing.documentId(), existing.url(), existing.title(),
                            existing.bm25Score(), existing.pageRankScore(), r.score(), existing.snippet()));
                } else {
                    String url = docUrls.getOrDefault(r.id(), "");
                    String title = docTitles.getOrDefault(r.id(), r.metadata());
                    ranked.add(new RankedDocument(
                            r.id(), url, title, 0.0, 0.0, r.score(), snippetFor(r.id())));
                }
            }
        }

        // Re-rank with cross-encoder
        ranked = reranker.rerank(query, ranked);

        return new RetrievalResult(ranked, didYouMean);
    }

    /**
     * Corpus-wide normalization context (max over documents) so learning-to-rank
     * features are normalized identically at serve time and train time.
     */
    public NormalizationContext normalizationContext() {
        return featureExtractor != null
                ? featureExtractor.normalizationContext()
                : NormalizationContext.EMPTY;
    }

    /**
     * Raw feature vector for one (query, document) pair, used by the final
     * ranking pipeline. Returns {@code null} when the node has no feature
     * extractor (feature-less shards).
     */
    public RawFeatures rawFeatures(String query, int documentId) {
        return featureExtractor != null
                ? featureExtractor.extractRaw(query, documentId)
                : null;
    }

    public FeatureExtractor featureExtractor() {
        return featureExtractor;
    }

    private String snippetFor(int docId) {
        String body = docBodies.getOrDefault(docId, "");
        if (body == null || body.isEmpty()) {
            return docTitles.getOrDefault(docId, "");
        }
        String cleaned = body.replaceAll("\\s+", " ").trim();
        return cleaned.length() > 160 ? cleaned.substring(0, 160) : cleaned;
    }

    /**
     * Collects the raw word leaves of an (expanded) query tree, synonyms
     * included and phrase contents excluded, for per-term ranking features.
     */
    private static List<String> collectWordLeaves(QueryNode root) {
        WordCollector collector = new WordCollector();
        root.accept(collector);
        return collector.words;
    }

    /**
     * Builds ranked documents directly from a posting list when the query has
     * no individual word leaves to score (e.g. a pure phrase query).
     */
    private List<RankedDocument> rankedFromPostings(PostingList results) {
        List<RankedDocument> ranked = new ArrayList<>();
        for (Posting posting : results.getPostings()) {
            int docId = posting.getDocumentId();
            String url = docUrls.getOrDefault(docId, "");
            String title = docTitles.getOrDefault(docId, "");
            ranked.add(new RankedDocument(docId, url, title, 0.0, 0.0, 1.0, snippetFor(docId)));
        }
        return ranked;
    }

    private static final class WordCollector implements QueryVisitor<List<String>> {
        private final List<String> words = new ArrayList<>();

        @Override
        public List<String> visit(WordNode node) {
            words.add(node.word());
            return words;
        }

        @Override
        public List<String> visit(PhraseNode node) {
            return words;
        }

        @Override
        public List<String> visit(AndNode node) {
            node.left().accept(this);
            node.right().accept(this);
            return words;
        }

        @Override
        public List<String> visit(OrNode node) {
            node.left().accept(this);
            node.right().accept(this);
            return words;
        }

        @Override
        public List<String> visit(NotNode node) {
            node.operand().accept(this);
            return words;
        }
    }
}
