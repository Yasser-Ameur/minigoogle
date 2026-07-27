package com.minigoogle.storage.postings;

import com.minigoogle.indexer.compression.GapEncoder;
import com.minigoogle.indexer.inverted.Posting;
import com.minigoogle.indexer.inverted.PostingList;
import com.minigoogle.storage.serialization.BinaryReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads gap-encoded posting lists from a memory-mapped file.
 * Decodes document IDs from their gap-encoded representation and reconstructs
 * full {@link PostingList} instances with frequencies and positional data.
 */
public class PostingReader {
    private final GapEncoder gapEncoder = new GapEncoder();

    public PostingList read(BinaryReader reader) {
        int docFreq = reader.readInt();
        List<Posting> postings = new ArrayList<>(docFreq);
        
        List<Integer> gapEncodedIds = new ArrayList<>(docFreq);
        List<Integer> frequencies = new ArrayList<>(docFreq);
        List<List<Integer>> allPositions = new ArrayList<>(docFreq);

        for (int i = 0; i < docFreq; i++) {
            gapEncodedIds.add(reader.readInt());
            frequencies.add(reader.readInt());
            
            int posCount = reader.readInt();
            List<Integer> positions = new ArrayList<>(posCount);
            for (int j = 0; j < posCount; j++) {
                positions.add(reader.readInt());
            }
            allPositions.add(positions);
        }

        List<Integer> docIds = gapEncoder.decode(gapEncodedIds);

        for (int i = 0; i < docFreq; i++) {
            postings.add(new Posting(docIds.get(i), frequencies.get(i), allPositions.get(i)));
        }

        return new PostingList(postings);
    }
}
