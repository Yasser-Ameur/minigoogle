package com.minigoogle.corpus;

import com.minigoogle.core.config.Configuration;
import com.minigoogle.search.SearchEngineBuild;
import com.minigoogle.search.SearchEngineBuilder;
import com.minigoogle.storage.metadata.Metadata;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * CLI that loads a BEIR dataset and builds a verified {@code SearchEngine}
 * index from it, printing the exact numbers that may go into project docs and
 * a resume.
 *
 * <p>Usage:</p>
 * <pre>
 *   BeirIndexMain --dataset trec-covid --dir data/beir/trec-covid
 *                 --out build/beir-index [--maxDocs 100000]
 *                 [--config ranking.pagerank.enabled=false] ...
 * </pre>
 *
 * <p>The default configuration follows the evaluation protocol: PageRank and
 * domain diversification are disabled (BEIR corpora have no links and a single
 * synthetic domain), and the lexical candidate cutoff matches the semantic
 * fetch width so every variant evaluates the same candidate pool.</p>
 */
public final class BeirIndexMain {

    private BeirIndexMain() {
    }

    public static void main(String[] args) throws IOException {
        configureLogging();
        CliOptions options = CliOptions.parse(args);
        long startNanos = System.nanoTime();

        Consumer<BeirCorpusReader.Progress> progress = p ->
                System.out.println("  " + p);

        System.out.println("Loading BEIR corpus '" + options.dataset + "' from " + options.dir);
        BeirCorpus corpus = BeirIngestionPipeline.load(options.dir, options.dataset,
                options.maxDocs, options.workDir, progress);

        System.out.println("Corpus: " + corpus.docs().size() + " docs, "
                + corpus.queries().size() + " queries, splits=" + corpus.qrels().keySet()
                + " (lines=" + corpus.stats().lines() + ", malformed=" + corpus.stats().malformed()
                + ", duplicates=" + corpus.stats().duplicates()
                + (corpus.stats().capped() ? ", CAPPED at maxDocs=" + options.maxDocs : "") + ")");

        Configuration config = buildConfig(options.configOverrides);
        Files.createDirectories(options.out);
        long buildStart = System.nanoTime();
        SearchEngineBuild build = SearchEngineBuilder.build(corpus.docs(), config, options.out);
        long buildMillis = (System.nanoTime() - buildStart) / 1_000_000;

        Metadata metadata = build.metadata();
        long totalMillis = (System.nanoTime() - startNanos) / 1_000_000;
        long usedBytes = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();

        System.out.println();
        System.out.println("=== Verified index ===");
        System.out.println("  documents indexed : " + metadata.documentCount());
        System.out.println("  vocabulary size   : " + metadata.vocabularySize());
        System.out.println("  avg doc length    : " + metadata.averageDocumentLength());
        System.out.println("  index build time  : " + buildMillis + " ms (total " + totalMillis + " ms)");
        System.out.println("  JVM heap max      : " + (Runtime.getRuntime().maxMemory() / (1024 * 1024)) + " MB");
        System.out.println("  JVM heap used     : " + (usedBytes / (1024 * 1024)) + " MB");
        System.out.println("  resolved qrels    : " + corpus.resolvedRelJudgments("test")
                + " (test split)");
        System.out.println("  id mapping        : " + options.workDir.resolve(BeirIngestionPipeline.IDS_FILE));
        System.out.println("  manifest          : " + options.workDir.resolve(BeirIngestionPipeline.MANIFEST_FILE));
        System.out.println("  index dir         : " + options.out);
    }

    private static void configureLogging() {
        ch.qos.logback.classic.Logger mini = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger("com.minigoogle");
        mini.setLevel(ch.qos.logback.classic.Level.INFO);
    }

    private static Configuration buildConfig(Map<String, String> overrides) {
        Map<String, String> props = new HashMap<>();
        // Evaluation protocol defaults for BEIR corpora (no links, one domain).
        props.put("ranking.pagerank.enabled", "false");
        props.put("ranking.diversify.enabled", "false");
        props.put("ranking.topK", "100");
        props.put("semantic.hybrid.fetchK", "100");
        props.put("semantic.enabled", "true");
        props.put("semantic.hybrid.enabled", "true");
        props.put("semantic.expansion.enabled", "false");
        props.put("semantic.index.mode", "flat");
        props.putAll(overrides);
        return new Configuration(props);
    }

    private static final class CliOptions {
        String dataset = "trec-covid";
        Path dir;
        Path out = Path.of("build/beir-index");
        Path workDir;
        int maxDocs = 0;
        final Map<String, String> configOverrides = new HashMap<>();

        static CliOptions parse(String[] args) {
            CliOptions o = new CliOptions();
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--dataset" -> o.dataset = args[++i];
                    case "--dir" -> o.dir = Path.of(args[++i]);
                    case "--out" -> o.out = Path.of(args[++i]);
                    case "--work" -> o.workDir = Path.of(args[++i]);
                    case "--maxDocs" -> o.maxDocs = Integer.parseInt(args[++i]);
                    case "--config" -> {
                        String kv = args[++i];
                        int eq = kv.indexOf('=');
                        if (eq <= 0) {
                            throw new IllegalArgumentException("--config expects key=value, got '" + kv + "'");
                        }
                        o.configOverrides.put(kv.substring(0, eq), kv.substring(eq + 1));
                    }
                    default -> throw new IllegalArgumentException(
                            "Unknown argument '" + args[i] + "' (use --dataset/--dir/--out/--work/--maxDocs/--config)");
                }
            }
            if (o.dir == null) {
                throw new IllegalArgumentException("Missing required --dir <dataset directory>");
            }
            if (o.workDir == null) {
                o.workDir = o.out.resolve("work");
            }
            return o;
        }
    }
}
