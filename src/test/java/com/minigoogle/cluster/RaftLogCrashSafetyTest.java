package com.minigoogle.cluster;

import com.minigoogle.storage.wal.WriteAheadLog;
import com.minigoogle.storage.wal.WriteAheadLog.PersistencePoint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The crash matrix for the two destructive Raft log operations.
 *
 * <p>These do not merely call {@code truncateFrom()} then reopen the log — that
 * would prove nothing about a crash <em>during</em> persistence. A failure
 * injector aborts the operation at each persistence boundary, and the assertion
 * is on what a fresh {@link RaftLog} recovers from the files left behind.</p>
 *
 * <p>The invariant under test is the one that matters for Raft safety: after an
 * interrupted truncation or compaction, the recovered log is either the complete
 * pre-operation state or the complete post-operation state. It is never missing,
 * never partial, and never short of a committed entry.</p>
 */
class RaftLogCrashSafetyTest {

    @TempDir
    Path tempDir;

    private Path logPath() {
        return tempDir.resolve("raft-log.bin");
    }

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    /** Simulates abrupt termination at exactly one persistence boundary. */
    private static final class CrashAt implements java.util.function.Consumer<PersistencePoint> {
        private final PersistencePoint target;

        CrashAt(PersistencePoint target) {
            this.target = target;
        }

        @Override
        public void accept(PersistencePoint point) {
            if (point == target) {
                throw new SimulatedCrash(point);
            }
        }
    }

    private static final class SimulatedCrash extends RuntimeException {
        SimulatedCrash(PersistencePoint point) {
            super("simulated crash at " + point);
        }
    }

    /** Builds a log with entries 1..count, all persisted and fsynced. */
    private RaftLog logWithEntries(WriteAheadLog wal, int count) throws IOException {
        RaftLog log = new RaftLog(wal);
        for (int i = 1; i <= count; i++) {
            log.append(1, utf8("entry-" + i));
        }
        return log;
    }

    /** Reopens the log from disk exactly as a restarting process would. */
    private RaftLog recover() throws IOException {
        return new RaftLog(new WriteAheadLog(logPath()));
    }

    private RaftLog recover(int baseIndex, int baseTerm) throws IOException {
        return new RaftLog(new WriteAheadLog(logPath()), baseIndex, baseTerm);
    }

    private static List<String> payloads(RaftLog log) {
        List<String> out = new ArrayList<>();
        for (int i = log.firstIndex(); i <= log.lastIndex(); i++) {
            byte[] payload = log.payloadAt(i);
            out.add(payload == null ? null : new String(payload, StandardCharsets.UTF_8));
        }
        return out;
    }

    // ── truncateFrom: crash at every persistence boundary ──

    @Test
    void truncationInterruptedBeforeTheRenameLeavesTheOriginalLogIntact() throws IOException {
        WriteAheadLog wal = new WriteAheadLog(logPath());
        RaftLog log = logWithEntries(wal, 5);

        for (PersistencePoint point : List.of(
                PersistencePoint.AFTER_TEMP_WRITE,
                PersistencePoint.AFTER_TEMP_FORCE,
                PersistencePoint.BEFORE_RENAME)) {

            wal.setFailureInjector(new CrashAt(point));
            assertThrows(SimulatedCrash.class, () -> log.truncateFrom(3),
                    "the crash must abort the operation at " + point);
            wal.setFailureInjector(null);

            RaftLog recovered = recover();
            assertEquals(5, recovered.lastIndex(),
                    "a crash at " + point + " must leave the pre-truncation log");
            assertEquals(List.of("entry-1", "entry-2", "entry-3", "entry-4", "entry-5"),
                    payloads(recovered),
                    "no entry may be lost by a crash at " + point);
        }
    }

    @Test
    void truncationInterruptedAfterTheRenameLeavesTheNewLogIntact() throws IOException {
        for (PersistencePoint point : List.of(
                PersistencePoint.AFTER_RENAME,
                PersistencePoint.AFTER_DIRECTORY_SYNC)) {

            Files.deleteIfExists(logPath());
            WriteAheadLog wal = new WriteAheadLog(logPath());
            RaftLog log = logWithEntries(wal, 5);

            wal.setFailureInjector(new CrashAt(point));
            assertThrows(SimulatedCrash.class, () -> log.truncateFrom(3));
            wal.setFailureInjector(null);

            RaftLog recovered = recover();
            assertEquals(2, recovered.lastIndex(),
                    "a crash at " + point + " must leave the post-truncation log");
            assertEquals(List.of("entry-1", "entry-2"), payloads(recovered),
                    "the retained prefix must be complete after a crash at " + point);
        }
    }

    @Test
    void aCompletedTruncationIsDurable() throws IOException {
        WriteAheadLog wal = new WriteAheadLog(logPath());
        RaftLog log = logWithEntries(wal, 5);
        log.truncateFrom(4);

        RaftLog recovered = recover();
        assertEquals(3, recovered.lastIndex());
        assertEquals(List.of("entry-1", "entry-2", "entry-3"), payloads(recovered));
    }

    @Test
    void theLogFileIsNeverAbsentDuringTruncation() throws IOException {
        // The old implementation deleted the log before rewriting it, so a crash
        // in that window left no file at all. Assert the file exists at every
        // boundary.
        WriteAheadLog wal = new WriteAheadLog(logPath());
        RaftLog log = logWithEntries(wal, 4);

        for (PersistencePoint point : PersistencePoint.values()) {
            assertTrue(Files.exists(logPath()), "log must exist before " + point);
            boolean[] observed = {false};
            wal.setFailureInjector(p -> {
                observed[0] = true;
                assertTrue(Files.exists(logPath()),
                        "the log file must never be absent, but was at " + p);
                if (p == point) {
                    throw new SimulatedCrash(p);
                }
            });
            try {
                log.truncateFrom(3);
            } catch (SimulatedCrash expected) {
                // continue to the next boundary
            }
            wal.setFailureInjector(null);
            assertTrue(observed[0],
                    "the operation never reached a persistence boundary, so the "
                            + "file-presence check at " + point + " proves nothing");
            assertTrue(Files.exists(logPath()), "log must exist after a crash at " + point);
        }
    }

    // ── compact: crash at every persistence boundary ──

    @Test
    void compactionInterruptedBeforeTheRenameLeavesThePreCompactionLog() throws IOException {
        WriteAheadLog wal = new WriteAheadLog(logPath());
        RaftLog log = logWithEntries(wal, 6);

        for (PersistencePoint point : List.of(
                PersistencePoint.AFTER_TEMP_WRITE,
                PersistencePoint.AFTER_TEMP_FORCE,
                PersistencePoint.BEFORE_RENAME)) {

            wal.setFailureInjector(new CrashAt(point));
            assertThrows(SimulatedCrash.class, () -> log.compact(3, 1));
            wal.setFailureInjector(null);

            RaftLog recovered = recover();
            assertEquals(6, recovered.lastIndex(),
                    "a crash at " + point + " must leave the pre-compaction log");
            assertEquals(6, payloads(recovered).size());
        }
    }

    @Test
    void compactionInterruptedAfterTheRenameLeavesTheRetainedTail() throws IOException {
        for (PersistencePoint point : List.of(
                PersistencePoint.AFTER_RENAME,
                PersistencePoint.AFTER_DIRECTORY_SYNC)) {

            Files.deleteIfExists(logPath());
            WriteAheadLog wal = new WriteAheadLog(logPath());
            RaftLog log = logWithEntries(wal, 6);

            wal.setFailureInjector(new CrashAt(point));
            assertThrows(SimulatedCrash.class, () -> log.compact(3, 1));
            wal.setFailureInjector(null);

            // A restart pairs the snapshot's base with the retained tail, which
            // is how ClusterNode.createRaftLog reconstructs a compacted log.
            RaftLog recovered = recover(3, 1);
            assertEquals(6, recovered.lastIndex(),
                    "the tail above the snapshot must survive a crash at " + point);
            assertEquals(List.of("entry-4", "entry-5", "entry-6"), payloads(recovered),
                    "the retained tail must be complete after a crash at " + point);
        }
    }

    @Test
    void aCompletedCompactionIsDurableAndPairsWithItsSnapshotBase() throws IOException {
        WriteAheadLog wal = new WriteAheadLog(logPath());
        RaftLog log = logWithEntries(wal, 6);
        log.compact(4, 1);

        RaftLog recovered = recover(4, 1);
        // firstIndex is baseIndex + 1: the snapshot consumed 1..4, so the first
        // retained entry is 5.
        assertEquals(5, recovered.firstIndex());
        assertEquals(6, recovered.lastIndex());
        assertEquals(1, recovered.termAt(4), "the snapshot base term must be restored");
        assertEquals(List.of("entry-5", "entry-6"), payloads(recovered));
    }

    // ── Raft-level properties ──

    @Test
    void committedEntriesSurviveAnInterruptedTruncation() throws IOException {
        // entry-1 and entry-2 are committed; the truncation drops the divergent
        // suffix from index 3. An interrupted attempt must never cost a
        // committed entry, whichever side of the rename it fails on.
        for (PersistencePoint point : PersistencePoint.values()) {
            Files.deleteIfExists(logPath());
            WriteAheadLog wal = new WriteAheadLog(logPath());
            RaftLog log = logWithEntries(wal, 5);

            // Track that a crash was genuinely injected. Without this the test
            // passes vacuously against any implementation that never reaches a
            // persistence boundary -- which is exactly what the old
            // clear-then-rewrite did.
            boolean[] crashed = {false};
            wal.setFailureInjector(p -> {
                if (p == point) {
                    crashed[0] = true;
                    throw new SimulatedCrash(p);
                }
            });
            try {
                log.truncateFrom(3);
            } catch (SimulatedCrash expected) {
                // abrupt termination
            }
            wal.setFailureInjector(null);
            assertTrue(crashed[0],
                    "no crash was injected at " + point + "; the operation did not reach "
                            + "an instrumented persistence boundary, so this proves nothing");

            RaftLog recovered = recover();
            assertTrue(recovered.lastIndex() >= 2,
                    "committed entries 1-2 must survive a crash at " + point
                            + ", but the log recovered to index " + recovered.lastIndex());
            assertEquals("entry-1", new String(recovered.payloadAt(1), StandardCharsets.UTF_8));
            assertEquals("entry-2", new String(recovered.payloadAt(2), StandardCharsets.UTF_8));
        }
    }

    @Test
    void recoveredLogPreservesTermsForLogMatching() throws IOException {
        WriteAheadLog wal = new WriteAheadLog(logPath());
        RaftLog log = new RaftLog(wal);
        log.append(1, utf8("t1-a"));
        log.append(1, utf8("t1-b"));
        log.append(2, utf8("t2-a"));
        log.append(3, utf8("t3-a"));

        log.truncateFrom(4);

        RaftLog recovered = recover();
        // Log matching depends on (index, term) pairs surviving intact.
        assertEquals(1, recovered.termAt(1));
        assertEquals(1, recovered.termAt(2));
        assertEquals(2, recovered.termAt(3));
        assertEquals(3, recovered.lastIndex());
        assertEquals(2, recovered.lastTerm());
    }

    @Test
    void anInterruptedTruncationLeavesNoTemporaryFilesBehind() throws IOException {
        WriteAheadLog wal = new WriteAheadLog(logPath());
        RaftLog log = logWithEntries(wal, 4);

        for (PersistencePoint point : PersistencePoint.values()) {
            wal.setFailureInjector(new CrashAt(point));
            try {
                log.truncateFrom(3);
            } catch (SimulatedCrash expected) {
                // abrupt termination
            }
            wal.setFailureInjector(null);
        }

        try (var files = Files.list(tempDir)) {
            List<Path> temps = files
                    .filter(p -> p.getFileName().toString().startsWith(".wal-replace-"))
                    .toList();
            assertTrue(temps.isEmpty(), "temporary replacement files must be cleaned up: " + temps);
        }
    }

    @Test
    void truncationFailureSurfacesRatherThanCorruptingSilently() throws IOException {
        WriteAheadLog wal = new WriteAheadLog(logPath());
        RaftLog log = logWithEntries(wal, 3);

        wal.setFailureInjector(point -> {
            if (point == PersistencePoint.BEFORE_RENAME) {
                throw new UncheckedIOException(new IOException("disk full"));
            }
        });

        assertThrows(RuntimeException.class, () -> log.truncateFrom(2),
                "a persistence failure must not be swallowed");
        wal.setFailureInjector(null);

        RaftLog recovered = recover();
        assertNotNull(recovered.payloadAt(3), "the original log must remain intact");
        assertEquals(3, recovered.lastIndex());
    }
}
