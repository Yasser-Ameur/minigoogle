package com.minigoogle.storage.dictionary;

import com.minigoogle.storage.serialization.BinaryReader;
import java.io.IOException;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;

/**
 * Reads the term dictionary from a memory-mapped file into a HashMap.
 * Each entry contains the term string, its byte offset into the postings file,
 * and the document frequency. Uses {@link BinaryReader} for deserialization.
 */
public class DictionaryReader {
    public Map<String, DictionaryEntry> read(Path filePath) throws IOException {
        Map<String, DictionaryEntry> dictionary = new HashMap<>();
        try (FileChannel channel = FileChannel.open(filePath, StandardOpenOption.READ)) {
            MappedByteBuffer mmap = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size());
            BinaryReader reader = new BinaryReader(mmap);
            
            if (!reader.hasRemaining()) {
                return dictionary;
            }

            int size = reader.readInt();
            for (int i = 0; i < size; i++) {
                String term = reader.readString();
                long offset = reader.readLong();
                int df = reader.readInt();
                dictionary.put(term, new DictionaryEntry(term, offset, df));
            }
        }
        return dictionary;
    }
}
