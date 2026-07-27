package com.minigoogle.storage.segment;

import java.nio.file.Path;

/**
 * Represents a single immutable index segment.
 * Contains the dictionary, postings, and document stores.
 */
public record Segment(
        String segmentId,
        Path directory,
        long documentCount,
        long sizeInBytes
) {
    public Path getDictionaryFile() {
        return directory.resolve("dictionary.bin");
    }

    public Path getPostingsFile() {
        return directory.resolve("postings.bin");
    }

    public Path getDocumentsFile() {
        return directory.resolve("documents.bin");
    }
}
