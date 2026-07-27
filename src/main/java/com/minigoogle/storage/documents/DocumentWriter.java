package com.minigoogle.storage.documents;

import com.minigoogle.indexer.model.IndexedDocument;
import com.minigoogle.storage.serialization.BinaryWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Writes indexed document metadata to disk.
 * Serializes the document count followed by each document's ID, URL, title, length, and timestamp.
 */
public class DocumentWriter {
    public void write(List<IndexedDocument> documents, Path filePath) throws IOException {
        try (BinaryWriter writer = new BinaryWriter(filePath)) {
            writer.writeInt(documents.size());
            for (IndexedDocument doc : documents) {
                writer.writeString(doc.id().toString());
                writer.writeString(doc.url().toString());
                writer.writeString(doc.title() != null ? doc.title() : "");
                writer.writeInt(doc.length());
                writer.writeLong(doc.timestamp().toEpochMilli());
            }
        }
    }
}
