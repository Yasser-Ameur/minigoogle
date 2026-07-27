package com.minigoogle.storage.dictionary;

import com.minigoogle.storage.serialization.BinaryWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

/**
 * Writes the term dictionary to disk as a sequence of term entries.
 * Serializes the dictionary size followed by each entry's term, posting offset, and document frequency.
 */
public class DictionaryWriter {
    public void write(Map<String, DictionaryEntry> dictionary, Path filePath) throws IOException {
        try (BinaryWriter writer = new BinaryWriter(filePath)) {
            writer.writeInt(dictionary.size());
            for (Map.Entry<String, DictionaryEntry> entry : dictionary.entrySet()) {
                DictionaryEntry dictEntry = entry.getValue();
                writer.writeString(dictEntry.term());
                writer.writeLong(dictEntry.postingOffset());
                writer.writeInt(dictEntry.documentFrequency());
            }
        }
    }
}
