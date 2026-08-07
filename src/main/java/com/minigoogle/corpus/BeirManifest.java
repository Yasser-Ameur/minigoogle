package com.minigoogle.corpus;

/**
 * Audit record written after a BEIR corpus is loaded and (optionally) indexed.
 * It captures exactly what was ingested and how long it took, so a claim like
 * "indexed N documents" is always traceable to a specific corpus file, checksum
 * and configuration.
 */
public record BeirManifest(
        String dataset,
        String corpusFile,
        String corpusMd5,
        long corpusBytes,
        int docsIndexed,
        long linesRead,
        long malformed,
        long duplicates,
        boolean capped,
        int maxDocs,
        int queries,
        long resolvedRelJudgments,
        long durationMillis,
        long jvmMaxBytes,
        long jvmUsedBytes) {
}
