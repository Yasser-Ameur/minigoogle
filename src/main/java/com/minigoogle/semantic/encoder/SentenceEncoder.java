package com.minigoogle.semantic.encoder;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * A trained sentence-embedding encoder running locally on CPU through ONNX
 * Runtime.
 *
 * <p>This replaces the feature-hash "embedding" in the semantic role. That one
 * had no learned parameters — it hashed tokens into buckets, so two texts
 * sharing no token could not be related, which is exactly what semantic
 * retrieval is for. Measured, it recovered 2.3% of the relevant documents BM25
 * misses on TREC-COVID.</p>
 *
 * <h2>Model</h2>
 * Default is {@code sentence-transformers/all-MiniLM-L6-v2}: a 6-layer
 * MiniLM bi-encoder, 384 output dimensions, Apache-2.0, trained contrastively on
 * roughly a billion sentence pairs. It is a <em>pretrained</em> encoder —
 * MiniGoogle does not train it, and this class does not claim to.
 *
 * <h2>Pooling</h2>
 * Sentence-transformers produces its sentence vector by mean-pooling the token
 * embeddings under the attention mask and L2-normalizing. Both steps are
 * required: taking the {@code [CLS]} vector instead, or skipping normalization,
 * silently yields a different (and much worse) embedding space while still
 * returning plausible-looking numbers.
 *
 * <h2>Model files</h2>
 * Not committed — the ONNX graph alone is 90 MB. {@link #isAvailable(Path)}
 * reports whether a model directory is present so callers can degrade rather
 * than fail, which is how the BEIR datasets are handled elsewhere.
 */
public final class SentenceEncoder implements AutoCloseable {

    /** Output dimensionality of all-MiniLM-L6-v2. */
    public static final int MINILM_DIMENSION = 384;

    /** Sequence length the sentence-transformers configuration truncates to. */
    public static final int DEFAULT_MAX_TOKENS = 256;

    private final OrtEnvironment environment;
    private final OrtSession session;
    private final WordPieceTokenizer tokenizer;
    private final int maxTokens;
    private final int dimension;
    private final Set<String> inputNames;

    private SentenceEncoder(OrtEnvironment environment, OrtSession session,
                            WordPieceTokenizer tokenizer, int maxTokens, int dimension) {
        this.environment = environment;
        this.session = session;
        this.tokenizer = tokenizer;
        this.maxTokens = maxTokens;
        this.dimension = dimension;
        this.inputNames = new LinkedHashSet<>(session.getInputNames());
    }

    /** @return true when {@code modelDir} holds both {@code model.onnx} and {@code vocab.txt}. */
    public static boolean isAvailable(Path modelDir) {
        return modelDir != null
                && Files.isRegularFile(modelDir.resolve("model.onnx"))
                && Files.isRegularFile(modelDir.resolve("vocab.txt"));
    }

    public static SentenceEncoder load(Path modelDir) throws IOException, OrtException {
        return load(modelDir, DEFAULT_MAX_TOKENS, MINILM_DIMENSION, 1);
    }

    /**
     * @param threads intra-op threads for inference; 1 keeps benchmark numbers
     *                comparable across machines, raise it for throughput
     */
    public static SentenceEncoder load(Path modelDir, int maxTokens, int dimension, int threads)
            throws IOException, OrtException {
        if (!isAvailable(modelDir)) {
            throw new IOException("No encoder model at " + modelDir
                    + " (expected model.onnx and vocab.txt)");
        }
        WordPieceTokenizer tokenizer = WordPieceTokenizer.fromVocabFile(modelDir.resolve("vocab.txt"));

        OrtEnvironment env = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions options = new OrtSession.SessionOptions();
        options.setIntraOpNumThreads(threads);
        OrtSession session = env.createSession(modelDir.resolve("model.onnx").toString(), options);

        return new SentenceEncoder(env, session, tokenizer, maxTokens, dimension);
    }

    /** Embeds one text into an L2-normalized vector. */
    public float[] encode(String text) throws OrtException {
        return encodeBatch(new String[]{text == null ? "" : text})[0];
    }

    /**
     * Embeds a batch. Batching matters: per-call overhead dominates at batch
     * size 1, and a corpus of 171k documents is embedded one batch at a time.
     */
    public float[][] encodeBatch(String[] texts) throws OrtException {
        int batch = texts.length;
        long[][] ids = new long[batch][];
        long[][] mask = new long[batch][];
        long[][] types = new long[batch][];

        for (int i = 0; i < batch; i++) {
            WordPieceTokenizer.Encoding e =
                    tokenizer.encode(texts[i] == null ? "" : texts[i], maxTokens);
            ids[i] = e.inputIds();
            mask[i] = e.attentionMask();
            types[i] = e.tokenTypeIds();
        }

        try (OnnxTensor idTensor = OnnxTensor.createTensor(environment, ids);
             OnnxTensor maskTensor = OnnxTensor.createTensor(environment, mask);
             OnnxTensor typeTensor = OnnxTensor.createTensor(environment, types)) {

            Map<String, OnnxTensor> inputs = new HashMap<>();
            inputs.put("input_ids", idTensor);
            inputs.put("attention_mask", maskTensor);
            // Some exports omit token_type_ids; supplying an unknown input fails.
            if (inputNames.contains("token_type_ids")) {
                inputs.put("token_type_ids", typeTensor);
            }

            try (OrtSession.Result result = session.run(inputs)) {
                // last_hidden_state: [batch, sequence, hidden]
                float[][][] hidden = (float[][][]) result.get(0).getValue();
                float[][] pooled = new float[batch][];
                for (int i = 0; i < batch; i++) {
                    pooled[i] = meanPoolAndNormalize(hidden[i], mask[i]);
                }
                return pooled;
            }
        }
    }

    /**
     * Mean-pools token vectors over unmasked positions, then L2-normalizes.
     * Padding positions must be excluded or short texts are diluted toward the
     * padding embedding.
     */
    private float[] meanPoolAndNormalize(float[][] tokenVectors, long[] attentionMask) {
        float[] pooled = new float[dimension];
        int counted = 0;
        for (int t = 0; t < tokenVectors.length; t++) {
            if (attentionMask[t] == 0) {
                continue;
            }
            counted++;
            float[] token = tokenVectors[t];
            for (int d = 0; d < dimension; d++) {
                pooled[d] += token[d];
            }
        }
        if (counted > 0) {
            for (int d = 0; d < dimension; d++) {
                pooled[d] /= counted;
            }
        }

        double normSq = 0;
        for (float v : pooled) {
            normSq += (double) v * v;
        }
        if (normSq > 0) {
            float norm = (float) Math.sqrt(normSq);
            for (int d = 0; d < dimension; d++) {
                pooled[d] /= norm;
            }
        }
        return pooled;
    }

    /** Cosine similarity; for L2-normalized vectors this is the dot product. */
    public static double similarity(float[] a, float[] b) {
        if (a.length != b.length) {
            throw new IllegalArgumentException("dimension mismatch: " + a.length + " vs " + b.length);
        }
        double dot = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
        }
        return dot;
    }

    public int dimension() {
        return dimension;
    }

    public int maxTokens() {
        return maxTokens;
    }

    @Override
    public void close() throws OrtException {
        session.close();
        // OrtEnvironment is a shared singleton; closing it would break any other
        // session in the process, so it is deliberately left open.
    }
}
