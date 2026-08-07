package com.minigoogle.corpus;

import com.minigoogle.network.serialization.JsonSerializer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Loads a BEIR dataset directory into a {@link BeirCorpus}: corpus documents,
 * queries and every qrels split present (e.g. {@code test}, {@code dev},
 * {@code train}).
 *
 * <p>After reading, a deterministic id mapping ({@code beir_ids.tsv}) and a
 * manifest ({@code beir_manifest.json}) are persisted under the working
 * directory so later phases (evaluation, distributed testing) can align on the
 * exact same document ids and audit exactly what was indexed. Re-loading the
 * same corpus always reproduces the same mapping, which the evaluation suite
 * verifies.</p>
 */
public final class BeirIngestionPipeline {

    public static final String IDS_FILE = "beir_ids.tsv";
    public static final String MANIFEST_FILE = "beir_manifest.json";

    private BeirIngestionPipeline() {
    }

    public static BeirCorpus load(Path datasetDir, String dataset, int maxDocs, Path workDir,
                                  Consumer<BeirCorpusReader.Progress> onProgress) throws IOException {
        long startNanos = System.nanoTime();
        Path corpusFile = BeirCorpusReader.corpusFile(datasetDir);
        BeirCorpusReader.Result result = BeirCorpusReader.read(corpusFile, dataset, maxDocs, onProgress);

        List<BeirQuery> queries = BeirQueriesReader.read(datasetDir.resolve("queries.jsonl"));

        Map<String, Map<String, Map<String, Integer>>> qrels = new LinkedHashMap<>();
        Path qrelsDir = datasetDir.resolve("qrels");
        if (Files.isDirectory(qrelsDir)) {
            try (var entries = Files.list(qrelsDir)) {
                for (Path splitFile : entries.toList()) {
                    if (!Files.isRegularFile(splitFile)) {
                        continue;
                    }
                    String split = splitFile.getFileName().toString();
                    if (!split.endsWith(".tsv")) {
                        continue;
                    }
                    qrels.put(split.substring(0, split.length() - ".tsv".length()),
                            BeirQrelsReader.read(splitFile));
                }
            }
        }

        BeirCorpus corpus = new BeirCorpus(dataset, result.docs(), result.beirIdToDocId(),
                queries, qrels, result, datasetDir);

        persist(workDir, corpus, corpusFile, maxDocs, System.nanoTime() - startNanos);
        return corpus;
    }

    private static void persist(Path workDir, BeirCorpus corpus, Path corpusFile, int maxDocs,
                                long durationNanos) throws IOException {
        Files.createDirectories(workDir);
        Path idsFile = workDir.resolve(IDS_FILE);
        try (var writer = Files.newBufferedWriter(idsFile, StandardCharsets.UTF_8)) {
            writer.write("docId\tbeir_id\n");
            for (Map.Entry<String, Integer> e : corpus.beirIdToDocId().entrySet()) {
                writer.write(e.getValue() + "\t" + e.getKey() + "\n");
            }
        }

        long resolved = corpus.hasSplit("test")
                ? corpus.resolvedRelJudgments("test")
                : corpus.qrels().values().stream().mapToLong(m ->
                        m.values().stream().mapToLong(Map::size).sum()).sum();

        BeirManifest manifest = new BeirManifest(
                corpus.dataset(),
                corpusFile.getFileName().toString(),
                md5(corpusFile),
                corpusFile.toFile().length(),
                corpus.docs().size(),
                corpus.stats().lines(),
                corpus.stats().malformed(),
                corpus.stats().duplicates(),
                corpus.stats().capped(),
                maxDocs,
                corpus.queries().size(),
                resolved,
                durationNanos / 1_000_000,
                Runtime.getRuntime().maxMemory(),
                Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory());
        Files.writeString(workDir.resolve(MANIFEST_FILE), JsonSerializer.toJson(manifest), StandardCharsets.UTF_8);
    }

    private static String md5(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] buffer = new byte[1 << 16];
            int n;
            while ((n = in.read(buffer)) != -1) {
                md.update(buffer, 0, n);
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (IOException | NoSuchAlgorithmException e) {
            return "unavailable";
        }
    }
}
