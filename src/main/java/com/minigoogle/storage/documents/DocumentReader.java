package com.minigoogle.storage.documents;

import com.minigoogle.indexer.model.IndexedDocument;
import com.minigoogle.storage.serialization.BinaryReader;
import java.io.IOException;
import java.net.URI;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Reads indexed document metadata from a memory-mapped file.
 * Deserializes each document's ID, URL, title, length, and timestamp using {@link BinaryReader}.
 */
public class DocumentReader {
    public List<IndexedDocument> read(Path filePath) throws IOException {
        List<IndexedDocument> documents = new ArrayList<>();
        try (FileChannel channel = FileChannel.open(filePath, StandardOpenOption.READ)) {
            MappedByteBuffer mmap = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size());
            BinaryReader reader = new BinaryReader(mmap);
            
            if (!reader.hasRemaining()) {
                return documents;
            }

            int size = reader.readInt();
            for (int i = 0; i < size; i++) {
                UUID id = UUID.fromString(reader.readString());
                URI url = URI.create(reader.readString());
                String title = reader.readString();
                int length = reader.readInt();
                Instant timestamp = Instant.ofEpochMilli(reader.readLong());
                
                documents.add(new IndexedDocument(id, url, title, length, timestamp));
            }
        }
        return documents;
    }
}
