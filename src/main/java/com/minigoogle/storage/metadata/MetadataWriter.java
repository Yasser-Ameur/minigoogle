package com.minigoogle.storage.metadata;

import com.minigoogle.storage.serialization.BinaryWriter;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Writes index metadata to a binary file on disk.
 * Serializes the document count, vocabulary size, average document length,
 * version string, and creation timestamp using {@link BinaryWriter}.
 */
public class MetadataWriter {

    /**
     * Writes the given metadata to the specified file path.
     * Creates or overwrites the file.
     *
     * @param filePath The path to write the metadata file.
     * @param metadata The metadata to serialize.
     * @throws IOException If an I/O error occurs during writing.
     */
    public void write(Path filePath, Metadata metadata) throws IOException {
        try (BinaryWriter writer = new BinaryWriter(filePath)) {
            writer.writeInt(metadata.documentCount());
            writer.writeInt(metadata.vocabularySize());
            writer.writeInt(metadata.averageDocumentLength());
            writer.writeString(metadata.version());
            writer.writeLong(metadata.creationTimestamp());
        }
    }
}
