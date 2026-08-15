package com.minigoogle.semantic.encoder;

import ai.onnxruntime.OrtException;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Semantic candidate generation: a second retrieval channel alongside BM25.
 *
 * <p>Deliberately a <em>candidate generator</em>, not a ranker. It answers "which
 * documents are worth considering", and hands them to the existing ranking stage
 * unchanged. Keeping the two separate is what makes it possible to measure
 * retrieval coverage independently of score calibration — and the previous
 * mission showed how badly an uncalibrated semantic score damages a strong
 * lexical ranker when the two are conflated.</p>
 *
 * <h2>Vectors are built once</h2>
 * Embedding is the expensive part (9–19 documents/second on CPU), so document
 * vectors are computed once and persisted. A search node loads them; it does not
 * re-embed the corpus at startup.
 *
 * <h2>Search is exact</h2>
 * A full scan over the vector set. This is the ground-truth oracle: any
 * approximate index added later has to be measured against it, so exact search
 * stays available rather than being replaced.
 */
public final class SemanticRetriever implements AutoCloseable {

    /** Magic + version, so a stale or foreign vector file is rejected loudly. */
    private static final int MAGIC = 0x4D47_5643;   // "MGVC"
    private static final int FORMAT_VERSION = 1;

    private final SentenceEncoder encoder;
    private final float[][] vectors;   // 1-based document ids; null for gaps
    private final int dimension;

    private SemanticRetriever(SentenceEncoder encoder, float[][] vectors, int dimension) {
        this.encoder = encoder;
        this.vectors = vectors;
        this.dimension = dimension;
    }

    /** A scored semantic candidate. */
    public record Candidate(int documentId, double similarity) {
    }

    /**
     * Embeds {@code texts} (index i maps to document id i+1) and writes the
     * vectors to {@code target}.
     *
     * @param progress called with (completed, total) periodically; may be null
     */
    public static void buildVectorStore(SentenceEncoder encoder, List<String> texts, Path target,
                                        java.util.function.ObjIntConsumer<Integer> progress)
            throws IOException, OrtException {
        int dim = encoder.dimension();
        int batchSize = 32;
        float[][] vectors = new float[texts.size() + 1][];

        for (int i = 0; i < texts.size(); i += batchSize) {
            int end = Math.min(i + batchSize, texts.size());
            String[] batch = new String[end - i];
            for (int j = i; j < end; j++) {
                batch[j - i] = texts.get(j);
            }
            float[][] encoded = encoder.encodeBatch(batch);
            for (int j = i; j < end; j++) {
                vectors[j + 1] = encoded[j - i];
            }
            if (progress != null) {
                progress.accept(end, texts.size());
            }
        }
        write(target, vectors, texts.size(), dim);
    }

    private static void write(Path target, float[][] vectors, int count, int dim) throws IOException {
        Path parent = target.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (DataOutputStream out = new DataOutputStream(
                new BufferedOutputStream(Files.newOutputStream(target), 1 << 20))) {
            out.writeInt(MAGIC);
            out.writeInt(FORMAT_VERSION);
            out.writeInt(count);
            out.writeInt(dim);
            for (int i = 1; i <= count; i++) {
                float[] v = vectors[i];
                for (int d = 0; d < dim; d++) {
                    out.writeFloat(v == null ? 0f : v[d]);
                }
            }
        }
    }

    /** @return true when a usable vector store exists at {@code vectorFile}. */
    public static boolean hasVectorStore(Path vectorFile) {
        return vectorFile != null && Files.isRegularFile(vectorFile);
    }

    /**
     * Loads a persisted vector store and pairs it with an encoder for queries.
     *
     * @throws IOException if the file is absent, foreign, or its dimension does
     *                     not match the encoder — a silent mismatch would produce
     *                     plausible but meaningless similarities
     */
    public static SemanticRetriever load(SentenceEncoder encoder, Path vectorFile) throws IOException {
        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(Files.newInputStream(vectorFile), 1 << 20))) {
            if (in.readInt() != MAGIC) {
                throw new IOException("Not a MiniGoogle vector store: " + vectorFile);
            }
            int version = in.readInt();
            if (version != FORMAT_VERSION) {
                throw new IOException("Unsupported vector store version " + version
                        + " in " + vectorFile + " (expected " + FORMAT_VERSION + ")");
            }
            int count = in.readInt();
            int dim = in.readInt();
            if (dim != encoder.dimension()) {
                throw new IOException("Vector store dimension " + dim
                        + " does not match encoder dimension " + encoder.dimension());
            }
            float[][] vectors = new float[count + 1][];
            for (int i = 1; i <= count; i++) {
                float[] v = new float[dim];
                for (int d = 0; d < dim; d++) {
                    v[d] = in.readFloat();
                }
                vectors[i] = v;
            }
            return new SemanticRetriever(encoder, vectors, dim);
        }
    }

    /**
     * Returns the {@code k} nearest documents to {@code query} by cosine
     * similarity, highest first. Exact: every stored vector is scored.
     */
    public List<Candidate> retrieve(String query, int k) throws OrtException {
        float[] queryVector = encoder.encode(query);
        return retrieve(queryVector, k);
    }

    /** As {@link #retrieve(String, int)} for a pre-computed query vector. */
    public List<Candidate> retrieve(float[] queryVector, int k) {
        if (k <= 0) {
            return List.of();
        }
        PriorityQueue<Candidate> worstFirst =
                new PriorityQueue<>(Comparator.comparingDouble(Candidate::similarity));

        for (int id = 1; id < vectors.length; id++) {
            float[] v = vectors[id];
            if (v == null) {
                continue;
            }
            double score = SentenceEncoder.similarity(queryVector, v);
            if (worstFirst.size() < k) {
                worstFirst.offer(new Candidate(id, score));
            } else if (score > worstFirst.peek().similarity()) {
                worstFirst.poll();
                worstFirst.offer(new Candidate(id, score));
            }
        }

        List<Candidate> best = new ArrayList<>(worstFirst);
        best.sort(Comparator.comparingDouble(Candidate::similarity).reversed());
        return best;
    }

    /** @return number of documents with a stored vector. */
    public int size() {
        int n = 0;
        for (int i = 1; i < vectors.length; i++) {
            if (vectors[i] != null) {
                n++;
            }
        }
        return n;
    }

    public int dimension() {
        return dimension;
    }

    @Override
    public void close() throws OrtException {
        encoder.close();
    }
}
