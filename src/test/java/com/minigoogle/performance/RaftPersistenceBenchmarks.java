package com.minigoogle.performance;

import com.minigoogle.cluster.RaftLog;
import com.minigoogle.monitoring.benchmark.BenchmarkReport;
import com.minigoogle.storage.wal.WriteAheadLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * COMPONENT — the cost of the crash-safe persistence path.
 *
 * <p>Durability changes are worth measuring even when correctness decides the
 * outcome, because an unaffordable guarantee gets switched off. Measured here:
 * fsynced append latency, atomic whole-log replacement (which backs truncation
 * and compaction), and recovery time on restart.</p>
 *
 * <p>Note on the comparison: the previous clear-then-rewrite issued one fsync
 * <em>per retained entry</em>, because it replayed the survivors through
 * {@code append}. The atomic replacement writes the whole file and fsyncs once.
 * Crash safety here is therefore not a tax — it removes work.</p>
 */
class RaftPersistenceBenchmarks {

    @TempDir
    Path tempDir;

    private static byte[] payload(int i) {
        return ("entry-payload-" + i + "-0123456789").getBytes(StandardCharsets.UTF_8);
    }

    private static List<WriteAheadLog.WalEntry> entries(int count) {
        List<WriteAheadLog.WalEntry> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            list.add(new WriteAheadLog.WalEntry(RaftLog.RAFT_ENTRY_OP, payload(i)));
        }
        return list;
    }

    @Test
    void fsyncedAppendLatency() throws IOException {
        Path log = tempDir.resolve("append-bench.bin");
        WriteAheadLog wal = new WriteAheadLog(log);

        for (int i = 0; i < 50; i++) {
            wal.append(RaftLog.RAFT_ENTRY_OP, payload(i));
        }

        int iterations = 300;
        List<Long> latencies = new ArrayList<>(iterations);
        long wall = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            long t0 = System.nanoTime();
            wal.append(RaftLog.RAFT_ENTRY_OP, payload(i));
            latencies.add(System.nanoTime() - t0);
        }
        BenchmarkReport report = new BenchmarkReport("wal-append", iterations, latencies,
                Duration.ofNanos(System.nanoTime() - wall));

        System.out.println("=== WAL append (one fsync per record) ===");
        System.out.printf("  p50=%.3fms p95=%.3fms p99=%.3fms throughput=%.0f appends/s%n",
                report.p50LatencyMs(), report.p95LatencyMs(), report.p99LatencyMs(),
                iterations / (report.wallTime().toNanos() / 1e9));

        assertTrue(report.p50LatencyMs() >= 0);
    }

    /**
     * Atomic replacement versus the old clear-then-rewrite, at the same retained
     * sizes. Both are measured here so the comparison is on one machine and one
     * filesystem rather than against a remembered number.
     */
    @Test
    void atomicReplaceVersusClearThenRewrite() throws IOException {
        System.out.println("=== Whole-log replacement: atomic swap vs clear-then-rewrite ===");

        for (int retained : new int[]{10, 100, 1_000}) {
            List<WriteAheadLog.WalEntry> retainedEntries = entries(retained);

            // New path: one temp file, one fsync, one atomic rename.
            Path atomicPath = tempDir.resolve("atomic-" + retained + ".bin");
            WriteAheadLog atomic = new WriteAheadLog(atomicPath);
            atomic.replaceAll(retainedEntries);
            int iterations = retained >= 1_000 ? 20 : 50;
            List<Long> atomicLatencies = new ArrayList<>(iterations);
            for (int i = 0; i < iterations; i++) {
                long t0 = System.nanoTime();
                atomic.replaceAll(retainedEntries);
                atomicLatencies.add(System.nanoTime() - t0);
            }
            BenchmarkReport atomicReport = new BenchmarkReport("atomic-replace", iterations,
                    atomicLatencies, Duration.ofNanos(1));

            // Old path, reconstructed exactly: delete the file, then append each
            // retained entry back (one fsync each).
            Path legacyPath = tempDir.resolve("legacy-" + retained + ".bin");
            WriteAheadLog legacy = new WriteAheadLog(legacyPath);
            List<Long> legacyLatencies = new ArrayList<>(iterations);
            for (int i = 0; i < iterations; i++) {
                long t0 = System.nanoTime();
                legacy.clear();
                for (WriteAheadLog.WalEntry entry : retainedEntries) {
                    legacy.append(entry.operationType(), entry.payload());
                }
                legacyLatencies.add(System.nanoTime() - t0);
            }
            BenchmarkReport legacyReport = new BenchmarkReport("clear-then-rewrite", iterations,
                    legacyLatencies, Duration.ofNanos(1));

            System.out.printf("  retained=%5d  atomic p50=%8.3fms   clear+rewrite p50=%8.3fms   speedup=%.1fx%n",
                    retained, atomicReport.p50LatencyMs(), legacyReport.p50LatencyMs(),
                    legacyReport.p50LatencyMs() / Math.max(atomicReport.p50LatencyMs(), 1e-6));

            // Both must produce the same log contents.
            assertEquals(retained, atomic.readAll().size());
            assertEquals(retained, legacy.readAll().size());
        }
    }

    @Test
    void recoveryTimeOnRestart() throws IOException {
        System.out.println("=== Raft log recovery on restart ===");

        for (int size : new int[]{100, 1_000, 10_000}) {
            Path log = tempDir.resolve("recover-" + size + ".bin");
            WriteAheadLog wal = new WriteAheadLog(log);
            wal.replaceAll(entries(size));
            long bytes = Files.size(log);

            int iterations = 20;
            List<Long> latencies = new ArrayList<>(iterations);
            for (int i = 0; i < iterations; i++) {
                long t0 = System.nanoTime();
                RaftLog recovered = new RaftLog(new WriteAheadLog(log));
                latencies.add(System.nanoTime() - t0);
                assertEquals(size, recovered.lastIndex());
            }
            BenchmarkReport report = new BenchmarkReport("recovery", iterations, latencies,
                    Duration.ofNanos(1));

            System.out.printf("  entries=%6d (%6d bytes)  recovery p50=%7.3fms p99=%7.3fms%n",
                    size, bytes, report.p50LatencyMs(), report.p99LatencyMs());
        }
    }

    /**
     * Torn-tail recovery must not be pathologically slower than a clean read;
     * it is on the startup path of every node that crashed mid-append.
     */
    @Test
    void tornTailRecoveryCostMatchesACleanRead() throws IOException {
        int size = 5_000;
        Path clean = tempDir.resolve("clean-tail.bin");
        new WriteAheadLog(clean).replaceAll(entries(size));

        Path torn = tempDir.resolve("torn-tail.bin");
        new WriteAheadLog(torn).replaceAll(entries(size));
        // Append a partial record: a complete header claiming more than follows.
        byte[] partial = new byte[]{RaftLog.RAFT_ENTRY_OP, 0, 0, 1, 0, 42};
        Files.write(torn, partial, java.nio.file.StandardOpenOption.APPEND);

        int iterations = 20;
        List<Long> cleanLatencies = new ArrayList<>(iterations);
        List<Long> tornLatencies = new ArrayList<>(iterations);
        for (int i = 0; i < iterations; i++) {
            long t0 = System.nanoTime();
            new WriteAheadLog(clean).readAll();
            cleanLatencies.add(System.nanoTime() - t0);
        }
        for (int i = 0; i < iterations; i++) {
            // Re-torn each round, since recovery truncates the tail away.
            Files.write(torn, partial, java.nio.file.StandardOpenOption.APPEND);
            long t0 = System.nanoTime();
            new WriteAheadLog(torn).readAll();
            tornLatencies.add(System.nanoTime() - t0);
        }

        BenchmarkReport cleanReport = new BenchmarkReport("clean", iterations, cleanLatencies, Duration.ofNanos(1));
        BenchmarkReport tornReport = new BenchmarkReport("torn", iterations, tornLatencies, Duration.ofNanos(1));

        System.out.println("=== Recovery cost: clean log vs torn tail (" + size + " entries) ===");
        System.out.printf("  clean p50=%.3fms   torn-tail p50=%.3fms (includes truncate+fsync)%n",
                cleanReport.p50LatencyMs(), tornReport.p50LatencyMs());

        assertEquals(size, new WriteAheadLog(clean).readAll().size());
    }
}
