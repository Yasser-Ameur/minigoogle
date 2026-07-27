package com.minigoogle.indexer;

import com.minigoogle.crawler.model.ParsedDocument;
import com.minigoogle.indexer.inverted.InvertedIndex;
import com.minigoogle.indexer.inverted.Posting;
import com.minigoogle.indexer.model.IndexedDocument;
import com.minigoogle.indexer.normalization.CaseFolder;
import com.minigoogle.indexer.normalization.UnicodeNormalizer;
import com.minigoogle.indexer.positional.PositionTracker;
import com.minigoogle.indexer.statistics.TermFrequencyCalculator;
import com.minigoogle.indexer.stemming.PorterStemmer;
import com.minigoogle.indexer.stopwords.StopWordFilter;

import com.minigoogle.indexer.tokenizer.Tokenizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Orchestrates the full indexing pipeline: tokenization, normalization, stemming,
 * inverted index construction, and persistence to disk.
 */
public class IndexBuilder {
    private static final Logger logger = LoggerFactory.getLogger(IndexBuilder.class);

    private final UnicodeNormalizer normalizer = new UnicodeNormalizer();
    private final Tokenizer tokenizer = new Tokenizer();
    private final CaseFolder caseFolder = new CaseFolder();
    private final StopWordFilter stopWordFilter = new StopWordFilter();
    private final PorterStemmer stemmer = new PorterStemmer();
    
    private final TermFrequencyCalculator tfCalculator = new TermFrequencyCalculator();
    private final PositionTracker positionTracker = new PositionTracker();

    private final InvertedIndex invertedIndex = new InvertedIndex();
    private final List<IndexedDocument> documents = new ArrayList<>();
    private final List<ParsedDocument> processedDocs = new ArrayList<>();

    private int currentDocId = 0;

    public void processDocument(ParsedDocument doc) {
        currentDocId++;
        int docId = currentDocId;
        processedDocs.add(doc);
        
        String text = doc.text();
        String normalizedText = normalizer.normalize(text);
        List<String> rawTokens = tokenizer.tokenize(normalizedText);
        
        List<String> processedTokens = new ArrayList<>(rawTokens.size());
        
        for (String rawToken : rawTokens) {
            String folded = caseFolder.fold(rawToken);
            if (stopWordFilter.isStopWord(folded)) {
                processedTokens.add(""); // Keep placeholder for positions
                continue;
            }
            String stemmed = stemmer.stem(folded);
            processedTokens.add(stemmed);
        }

        // Calculate statistics and positions
        Map<String, Integer> frequencies = tfCalculator.calculateFrequencies(
            processedTokens.stream().filter(s -> !s.isEmpty()).toList()
        );
        Map<String, List<Integer>> positions = positionTracker.trackPositions(processedTokens);

        // Update Inverted Index
        for (Map.Entry<String, Integer> entry : frequencies.entrySet()) {
            String term = entry.getKey();
            int freq = entry.getValue();
            List<Integer> posList = positions.get(term);
            
            Posting posting = new Posting(docId, freq, posList);
            invertedIndex.addPosting(term, posting);
        }
        
        // Save document metadata
        documents.add(new IndexedDocument(doc.id(), doc.url(), doc.title(), rawTokens.size(), doc.crawlTime()));
        logger.debug("Processed document: {}", doc.url());
    }

    public List<ParsedDocument> getProcessedDocuments() {
        return java.util.Collections.unmodifiableList(processedDocs);
    }

    public void flush(String dictionaryPath, String postingsPath, String documentsPath) throws IOException {
        logger.info("Sorting posting lists...");
        invertedIndex.sortAllPostingLists();
        
        logger.info("Writing documents to disk...");
        new com.minigoogle.storage.documents.DocumentWriter().write(documents, Path.of(documentsPath));
        
        logger.info("Writing postings to disk...");
        Map<String, Long> dictionary = new com.minigoogle.storage.postings.PostingWriter().write(invertedIndex.getIndex(), Path.of(postingsPath));
        
        logger.info("Writing dictionary to disk...");
        Map<String, com.minigoogle.storage.dictionary.DictionaryEntry> dictEntries = new java.util.HashMap<>();
        for (Map.Entry<String, Long> entry : dictionary.entrySet()) {
             String term = entry.getKey();
             long offset = entry.getValue();
             int df = invertedIndex.getIndex().get(term).getPostings().size();
             dictEntries.put(term, new com.minigoogle.storage.dictionary.DictionaryEntry(term, offset, df));
        }
        new com.minigoogle.storage.dictionary.DictionaryWriter().write(dictEntries, Path.of(dictionaryPath));
        
        logger.info("Writing metadata to disk...");
        long timestamp = System.currentTimeMillis();
        int totalLength = documents.stream().mapToInt(com.minigoogle.indexer.model.IndexedDocument::length).sum();
        int avgLength = documents.isEmpty() ? 0 : totalLength / documents.size();
        com.minigoogle.storage.metadata.Metadata metadata = new com.minigoogle.storage.metadata.Metadata(
            documents.size(), dictEntries.size(), avgLength, "1.0", timestamp
        );
        metadata.write(Path.of(documentsPath).getParent().resolve("metadata.bin"));
        
        logger.info("Index flush complete.");
    }
}
