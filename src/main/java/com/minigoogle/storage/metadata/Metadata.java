package com.minigoogle.storage.metadata;

import com.minigoogle.storage.serialization.BinaryWriter;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Record storing index metadata: document count, vocabulary size, average doc length, version, and timestamp.
 */
public record Metadata(
    int documentCount,
    int vocabularySize,
    int averageDocumentLength,
    String version,
    long creationTimestamp
) {
    public void write(Path filePath) throws IOException {
        try (BinaryWriter writer = new BinaryWriter(filePath)) {
            writer.writeInt(documentCount);
            writer.writeInt(vocabularySize);
            writer.writeInt(averageDocumentLength);
            writer.writeString(version);
            writer.writeLong(creationTimestamp);
        }
    }
}
