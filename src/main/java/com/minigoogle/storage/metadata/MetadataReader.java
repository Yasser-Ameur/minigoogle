package com.minigoogle.storage.metadata;

import com.minigoogle.storage.serialization.BinaryReader;
import java.io.IOException;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Reads index metadata from a memory-mapped binary file.
 * Deserializes the document count, vocabulary size, average document length,
 * version string, and creation timestamp into a {@link Metadata} record.
 */
public class MetadataReader {
    public Metadata read(Path filePath) throws IOException {
        try (FileChannel channel = FileChannel.open(filePath, StandardOpenOption.READ)) {
            MappedByteBuffer mmap = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size());
            BinaryReader reader = new BinaryReader(mmap);
            
            if (!reader.hasRemaining()) {
                return null;
            }

            int docCount = reader.readInt();
            int vocabSize = reader.readInt();
            int avgDocLength = reader.readInt();
            String version = reader.readString();
            long timestamp = reader.readLong();
            
            return new Metadata(docCount, vocabSize, avgDocLength, version, timestamp);
        }
    }
}
