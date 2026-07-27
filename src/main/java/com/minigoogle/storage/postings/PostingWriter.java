package com.minigoogle.storage.postings;

import com.minigoogle.indexer.compression.GapEncoder;
import com.minigoogle.indexer.inverted.Posting;
import com.minigoogle.indexer.inverted.PostingList;
import com.minigoogle.storage.serialization.BinaryWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Writes posting lists to disk with gap encoding for document IDs.
 * Returns a map of term to file offset so the dictionary can reference each posting list's location.
 */
public class PostingWriter {
    private final GapEncoder gapEncoder = new GapEncoder();

    public Map<String, Long> write(Map<String, PostingList> invertedIndex, Path filePath) throws IOException {
        Map<String, Long> dictionaryOffsets = new HashMap<>();

        try (BinaryWriter writer = new BinaryWriter(filePath)) {
            for (Map.Entry<String, PostingList> entry : invertedIndex.entrySet()) {
                String term = entry.getKey();
                PostingList postingList = entry.getValue();

                long offset = writer.position();
                dictionaryOffsets.put(term, offset);

                List<Posting> postings = postingList.getPostings();
                
                List<Integer> docIds = postings.stream().map(Posting::getDocumentId).collect(Collectors.toList());
                List<Integer> gapEncodedIds = gapEncoder.encode(docIds);

                writer.writeInt(postings.size()); // Document Frequency

                for (int i = 0; i < postings.size(); i++) {
                    Posting posting = postings.get(i);
                    writer.writeInt(gapEncodedIds.get(i));
                    writer.writeInt(posting.getFrequency());
                    
                    List<Integer> positions = posting.getPositions();
                    writer.writeInt(positions.size());
                    for (int pos : positions) {
                        writer.writeInt(pos);
                    }
                }
            }
        }
        return dictionaryOffsets;
    }
}
