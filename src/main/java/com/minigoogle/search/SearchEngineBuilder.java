package com.minigoogle.search;

import com.minigoogle.core.config.Configuration;
import com.minigoogle.crawler.model.ParsedDocument;
import com.minigoogle.indexer.IndexBuilder;
import com.minigoogle.indexer.model.IndexedDocument;
import com.minigoogle.indexer.normalization.CaseFolder;
import com.minigoogle.indexer.normalization.UnicodeNormalizer;
import com.minigoogle.indexer.stemming.PorterStemmer;
import com.minigoogle.indexer.stopwords.StopWordFilter;
import com.minigoogle.indexer.tokenizer.Tokenizer;
import com.minigoogle.ml.features.FeatureExtractor;
import com.minigoogle.query.lexer.Lexer;
import com.minigoogle.query.planner.QueryPlanner;
import com.minigoogle.ranking.bm25.BM25Parameters;
import com.minigoogle.ranking.pagerank.GraphBuilder;
import com.minigoogle.ranking.pagerank.PageRankCalculator;
import com.minigoogle.ranking.pipeline.RankingPipeline;
import com.minigoogle.semantic.EmbeddingGenerator;
import com.minigoogle.semantic.VectorIndex;
import com.minigoogle.semantic.autocomplete.TrieAutocomplete;
import com.minigoogle.semantic.expansion.PmiThesaurusBuilder;
import com.minigoogle.semantic.expansion.QueryExpander;
import com.minigoogle.semantic.reranking.CrossEncoderRanker;
import com.minigoogle.semantic.spell.SpellCorrector;
import com.minigoogle.semantic.synonym.SynonymGraph;
import com.minigoogle.storage.dictionary.DictionaryEntry;
import com.minigoogle.storage.dictionary.DictionaryReader;
import com.minigoogle.storage.documents.DocumentReader;
import com.minigoogle.storage.metadata.Metadata;
import com.minigoogle.storage.metadata.MetadataReader;
import com.minigoogle.storage.mmap.MemoryMappedIndex;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * Builds a {@link SearchEngine} (and its collateral) from a document list and
 * configuration. This is the single place that turns raw documents into the
 * inverted index, semantic vector index, PageRank scores, feature extractor
 * and supporting structures used by every node type and by the evaluation
 * harness.
 */
public final class SearchEngineBuilder {

    private SearchEngineBuilder() {
    }

    public static SearchEngineBuild build(List<ParsedDocument> docs,
                                          Configuration config,
                                          Path indexDir) throws IOException {
        if (docs.isEmpty()) {
            throw new IllegalArgumentException("Cannot build a search engine from an empty corpus");
        }
        Files.createDirectories(indexDir);

        IndexBuilder builder = new IndexBuilder();
        for (ParsedDocument doc : docs) {
            builder.processDocument(doc);
        }
        builder.flush(
            indexDir.resolve("dictionary.bin").toString(),
            indexDir.resolve("postings.bin").toString(),
            indexDir.resolve("documents.bin").toString()
        );

        DictionaryReader dictReader = new DictionaryReader();
        Map<String, DictionaryEntry> dictionary = dictReader.read(indexDir.resolve("dictionary.bin"));

        Metadata metadata = new MetadataReader().read(indexDir.resolve("metadata.bin"));

        MemoryMappedIndex mmapIndex = new MemoryMappedIndex(indexDir.resolve("postings.bin"));
        QueryPlanner planner = new QueryPlanner(mmapIndex, dictionary, metadata.documentCount());
        Lexer lexer = new Lexer();
        UnicodeNormalizer normalizer = new UnicodeNormalizer();
        CaseFolder caseFolder = new CaseFolder();
        PorterStemmer stemmer = new PorterStemmer();
        StopWordFilter stopWordFilter = new StopWordFilter();
        Tokenizer rawTokenizer = new Tokenizer();

        // Build surface-form vocabulary from raw document text
        Map<String, Integer> surfaceFreqs = new HashMap<>();
        for (ParsedDocument doc : docs) {
            List<String> tokens = rawTokenizer.tokenize(normalizer.normalize(doc.text()));
            String prevWord = null;
            for (String token : tokens) {
                String cleaned = caseFolder.fold(token);
                if (!cleaned.isEmpty() && !stopWordFilter.isStopWord(cleaned)) {
                    surfaceFreqs.merge(cleaned, 1, Integer::sum);
                    if (prevWord != null) {
                        surfaceFreqs.merge(prevWord + " " + cleaned, 1, Integer::sum);
                    }
                    prevWord = cleaned;
                } else {
                    prevWord = null;
                }
            }
        }

        TrieAutocomplete autocomplete = new TrieAutocomplete(surfaceFreqs);
        SpellCorrector spellCorrector = new SpellCorrector(new HashSet<>(surfaceFreqs.keySet()));
        for (String word : surfaceFreqs.keySet()) {
            autocomplete.addWord(word);
        }

        QueryExpander queryExpander = buildQueryExpander(docs, config);

        // Build the semantic vector index from real document content. Documents
        // that share vocabulary map to nearby vectors via feature hashing.
        VectorIndex vectorIndex;
        EmbeddingGenerator embeddingGenerator;
        CrossEncoderRanker reranker;
        boolean semanticEnabled = config.getBoolean("semantic.enabled", true);
        if (semanticEnabled) {
            int embeddingDim = config.getInt("semantic.dimension", 128);
            double semanticWeight = config.getDouble("semantic.weight", 0.3);
            VectorIndex.VectorMode indexMode = "flat".equalsIgnoreCase(
                    config.get("semantic.index.mode", "hnsw"))
                    ? VectorIndex.VectorMode.EXACT
                    : VectorIndex.VectorMode.HNSW;
            embeddingGenerator = new EmbeddingGenerator(embeddingDim);
            vectorIndex = new VectorIndex(embeddingDim, indexMode);
            for (int i = 0; i < docs.size(); i++) {
                ParsedDocument doc = docs.get(i);
                String content = doc.title() + " " + doc.text();
                vectorIndex.add(i + 1, embeddingGenerator.embed(content), doc.title());
            }
            reranker = new CrossEncoderRanker(vectorIndex, embeddingGenerator, semanticWeight);
        } else {
            vectorIndex = null;
            embeddingGenerator = null;
            reranker = new CrossEncoderRanker();
        }

        List<IndexedDocument> indexedDocs = new DocumentReader().read(indexDir.resolve("documents.bin"));

        Map<Integer, IndexedDocument> docIdToIndexed = new HashMap<>();
        for (int i = 0; i < indexedDocs.size(); i++) {
            docIdToIndexed.put(i + 1, indexedDocs.get(i));
        }

        Map<Integer, ParsedDocument> docIdToParsed = new HashMap<>();
        for (int i = 0; i < docs.size(); i++) {
            docIdToParsed.put(i + 1, docs.get(i));
        }

        Map<String, Integer> urlToDocId = new HashMap<>();
        for (Map.Entry<Integer, IndexedDocument> e : docIdToIndexed.entrySet()) {
            urlToDocId.put(e.getValue().url().toString(), e.getKey());
        }

        boolean pagerankEnabled = config.getBoolean("ranking.pagerank.enabled", true);
        boolean diversifyEnabled = config.getBoolean("ranking.diversify.enabled", true);
        int rankingTopK = config.getInt("ranking.topK", 20);

        Map<Integer, Double> pageRank;
        if (pagerankEnabled) {
            GraphBuilder graph = new GraphBuilder();
            for (Map.Entry<Integer, ParsedDocument> e : docIdToParsed.entrySet()) {
                int docId = e.getKey();
                ParsedDocument parsed = e.getValue();
                graph.addNode(docId);
                for (URI link : parsed.outgoingLinks()) {
                    Integer targetId = urlToDocId.get(link.toString());
                    if (targetId != null && targetId != docId) {
                        graph.addEdge(docId, targetId);
                    }
                }
            }
            pageRank = new PageRankCalculator().compute(graph);
        } else {
            pageRank = Map.of();
        }

        Map<Integer, String> docUrls = new HashMap<>();
        Map<Integer, String> docTitles = new HashMap<>();
        Map<Integer, String> docBodies = new HashMap<>();
        Map<Integer, Integer> docLengths = new HashMap<>();

        for (Map.Entry<Integer, IndexedDocument> e : docIdToIndexed.entrySet()) {
            int id = e.getKey();
            IndexedDocument idx = e.getValue();
            ParsedDocument parsed = docIdToParsed.get(id);
            docUrls.put(id, idx.url().toString());
            docTitles.put(id, idx.title());
            docBodies.put(id, parsed != null ? parsed.text() : "");
            docLengths.put(id, idx.length());
        }

        BM25Parameters bm25Params = BM25Parameters.withDefaults(
            metadata.documentCount(), metadata.averageDocumentLength()
        );
        Map<Integer, Double> pageRankScores = pageRank;
        RankingPipeline ranking = new RankingPipeline(
                bm25Params, pageRankScores, docUrls, docTitles, docBodies, docLengths,
                rankingTopK, diversifyEnabled);

        FeatureExtractor featureExtractor = new FeatureExtractor(docUrls, docTitles, docBodies,
                docLengths, pageRankScores, vectorIndex, embeddingGenerator);

        SearchEngine engine = new SearchEngine(
                planner, ranking, lexer, spellCorrector, queryExpander, reranker,
                vectorIndex, embeddingGenerator, featureExtractor, dictionary,
                docUrls, docTitles, docBodies, normalizer, caseFolder, stemmer,
                SearchEngineConfig.from(config));

        return new SearchEngineBuild(
                engine, mmapIndex, metadata, autocomplete, spellCorrector, ranking, planner,
                featureExtractor, urlToDocId, docUrls, docTitles, docBodies, docLengths, pageRankScores);
    }

    /**
     * Builds the query expander, preferring a corpus-derived PMI thesaurus when
     * corpus-based expansion is enabled.
     */
    private static QueryExpander buildQueryExpander(List<ParsedDocument> docs, Configuration config) {
        // Defaults to OFF. Measured on BEIR scifact with everything else held
        // identical, PMI expansion degraded every quality metric - NDCG@10
        // 0.6015 -> 0.4469, MRR@10 0.5641 -> 0.3990, Recall@10 0.7360 -> 0.6126 -
        // while Recall@1000 was flat (0.9343 -> 0.9333). It adds candidates
        // without recovering relevant documents, and costs ~8x the wall time.
        // Enable it explicitly if a corpus is shown to benefit.
        boolean expansionEnabled = config.getBoolean("semantic.expansion.enabled", false);
        if (!expansionEnabled) {
            return new QueryExpander();
        }
        int windowSize = config.getInt("semantic.expansion.windowSize", 10);
        double pmiThreshold = config.getDouble("semantic.expansion.pmiThreshold", 1.0);
        int maxNeighbors = config.getInt("semantic.expansion.maxNeighbors", 5);
        SynonymGraph thesaurus = new PmiThesaurusBuilder(windowSize, pmiThreshold, maxNeighbors).build(docs);
        return new QueryExpander(thesaurus);
    }
}
