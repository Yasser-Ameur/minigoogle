package com.minigoogle.semantic.encoder;

import ai.onnxruntime.OrtException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the encoder produces a genuinely <em>semantic</em> space, which is the
 * whole reason for replacing the feature-hash representation.
 *
 * <p>The critical assertion is {@link #relatesTermsThatShareNoTokens()}: a
 * feature hash maps distinct tokens to unrelated buckets, so it scores
 * "coronavirus" against "SARS-CoV-2" at roughly zero. An encoder that cannot beat
 * that has bought nothing, no matter how large the model.</p>
 *
 * <p>Skipped when the model is absent, so the suite stays green on a clean
 * checkout — the ONNX graph is 90 MB and is not committed.</p>
 */
@EnabledIf("modelPresent")
class SentenceEncoderTest {

    private static final Path MODEL_DIR = Path.of("models", "all-MiniLM-L6-v2");

    static boolean modelPresent() {
        return SentenceEncoder.isAvailable(MODEL_DIR);
    }

    @Test
    void producesNormalizedVectorsOfTheExpectedDimension() throws IOException, OrtException {
        try (SentenceEncoder encoder = SentenceEncoder.load(MODEL_DIR)) {
            float[] v = encoder.encode("severe acute respiratory syndrome");

            assertEquals(SentenceEncoder.MINILM_DIMENSION, v.length);

            double normSq = 0;
            for (float x : v) {
                normSq += (double) x * x;
            }
            assertEquals(1.0, Math.sqrt(normSq), 1e-4,
                    "sentence-transformers vectors are L2-normalized; cosine reduces to a dot product");
        }
    }

    @Test
    void isDeterministic() throws IOException, OrtException {
        try (SentenceEncoder encoder = SentenceEncoder.load(MODEL_DIR)) {
            float[] a = encoder.encode("how does the coronavirus respond to weather");
            float[] b = encoder.encode("how does the coronavirus respond to weather");
            assertEquals(1.0, SentenceEncoder.similarity(a, b), 1e-6,
                    "the same text must embed identically or benchmarks are not reproducible");
        }
    }

    @Test
    void relatesTermsThatShareNoTokens() throws IOException, OrtException {
        // The entire justification for the change. These strings share no
        // token, so a feature hash relates them only by collision.
        try (SentenceEncoder encoder = SentenceEncoder.load(MODEL_DIR)) {
            float[] a = encoder.encode("coronavirus");
            float[] b = encoder.encode("SARS-CoV-2");
            float[] unrelated = encoder.encode("banana bread recipe");

            double related = SentenceEncoder.similarity(a, b);
            double control = SentenceEncoder.similarity(a, unrelated);

            assertTrue(related > control + 0.2,
                    "synonymous virology terms must be far closer than an unrelated phrase, "
                            + "but similarity was " + related + " vs " + control);
            assertTrue(related > 0.4,
                    "terms sharing no token must still be related, was " + related);
        }
    }

    @Test
    void paraphrasesRankAboveTopicalNeighbours() throws IOException, OrtException {
        try (SentenceEncoder encoder = SentenceEncoder.load(MODEL_DIR)) {
            float[] query = encoder.encode("what causes death from COVID-19");
            float[] paraphrase = encoder.encode("mortality mechanisms in coronavirus disease patients");
            float[] sameField = encoder.encode("hospital triage guidelines during a pandemic");

            double toParaphrase = SentenceEncoder.similarity(query, paraphrase);
            double toSameField = SentenceEncoder.similarity(query, sameField);

            assertTrue(toParaphrase > toSameField,
                    "a paraphrase must outrank a merely same-domain sentence: "
                            + toParaphrase + " vs " + toSameField);
        }
    }

    @Test
    void handlesEmptyAndOverlongInput() throws IOException, OrtException {
        try (SentenceEncoder encoder = SentenceEncoder.load(MODEL_DIR)) {
            float[] empty = encoder.encode("");
            assertEquals(SentenceEncoder.MINILM_DIMENSION, empty.length,
                    "empty input must still yield a well-formed vector");

            // Far beyond the 256-token window: must truncate, not throw.
            String overlong = "coronavirus transmission ".repeat(2000);
            float[] truncated = encoder.encode(overlong);
            double normSq = 0;
            for (float x : truncated) {
                normSq += (double) x * x;
            }
            assertEquals(1.0, Math.sqrt(normSq), 1e-4);
        }
    }

    @Test
    void batchingMatchesSingleEncoding() throws IOException, OrtException {
        // Padding is per-batch, so a batched encode must still exclude padding
        // positions from the mean pool. If it does not, a short text batched with
        // a long one drifts toward the padding embedding.
        try (SentenceEncoder encoder = SentenceEncoder.load(MODEL_DIR)) {
            String shortText = "covid";
            String longText = "a considerably longer passage about the epidemiology of "
                    + "respiratory viruses and their seasonal transmission dynamics";

            float[] single = encoder.encode(shortText);
            float[][] batched = encoder.encodeBatch(new String[]{shortText, longText});

            assertEquals(1.0, SentenceEncoder.similarity(single, batched[0]), 1e-5,
                    "batched encoding must equal single encoding for the same text");
        }
    }
}
