package com.minigoogle.cluster;

import com.minigoogle.storage.wal.WriteAheadLog;
import com.minigoogle.storage.wal.WriteAheadLog.PersistencePoint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Snapshot/log crash consistency.
 *
 * <p>{@code RaftConsensus.maybeSnapshot} persists the snapshot and then compacts
 * the log. A crash in between leaves a durable snapshot at index N while the log
 * file still holds the whole uncompacted log. Recovery must reconstruct a log
 * whose entries keep their original absolute indexes; renumbering them would
 * silently violate log matching, because a term is then attributed to the wrong
 * index.</p>
 *
 * <p>WAL records carry a term and a payload but not their absolute index, so the
 * index has to come from somewhere. It comes from a base marker written as part
 * of the same atomic replacement that compacts the log — which means the marker
 * and the entries can never disagree.</p>
 */
class SnapshotLogConsistencyTest {

    @TempDir
    Path tempDir;

    private Path logPath() {
        return tempDir.resolve("raft-log.bin");
    }

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private RaftLog buildLog(WriteAheadLog wal, int count) throws IOException {
        RaftLog log = new RaftLog(wal);
        for (int i = 1; i <= count; i++) {
            log.append(i <= 5 ? 1 : 2, utf8("entry-" + i));
        }
        return log;
    }

    /**
     * The crash window inside {@code maybeSnapshot}: the snapshot is durable at
     * index 5, but compaction never replaced the log. Recovery must still see
     * entries at their original indexes, not shifted by the snapshot's base.
     */
    @Test
    void aLogThatWasNeverCompactedRecoversAtItsOriginalIndexes() throws IOException {
        WriteAheadLog wal = new WriteAheadLog(logPath());
        buildLog(wal, 10);

        // Interrupt the compaction before it replaces the file, exactly as a
        // crash between snapshotStore.save() and a completed compact() would.
        RaftLog live = new RaftLog(new WriteAheadLog(logPath()));
        WriteAheadLog interrupting = new WriteAheadLog(logPath());
        RaftLog toCompact = new RaftLog(interrupting);
        interrupting.setFailureInjector(point -> {
            if (point == PersistencePoint.BEFORE_RENAME) {
                throw new IllegalStateException("simulated crash");
            }
        });
        try {
            toCompact.compact(5, 1);
        } catch (IllegalStateException expected) {
            // abrupt termination
        }
        interrupting.setFailureInjector(null);
        assertEquals(10, live.lastIndex(), "sanity: the pre-crash log had 10 entries");

        // Recovery. The log file is the uncompacted one, so the recovered log
        // must be the complete pre-compaction state.
        RaftLog recovered = new RaftLog(new WriteAheadLog(logPath()));

        assertEquals(1, recovered.firstIndex(), "an uncompacted log starts at index 1");
        assertEquals(10, recovered.lastIndex());
        assertEquals("entry-1", new String(recovered.payloadAt(1), StandardCharsets.UTF_8),
                "entry 1 must still be at index 1");
        assertEquals("entry-10", new String(recovered.payloadAt(10), StandardCharsets.UTF_8),
                "entry 10 must still be at index 10");
        // Terms must stay attached to their own indexes or log matching breaks.
        assertEquals(1, recovered.termAt(5));
        assertEquals(2, recovered.termAt(6));
    }

    /**
     * A completed compaction must recover with the retained tail at its original
     * absolute indexes, without the caller having to supply the base.
     */
    @Test
    void aCompactedLogRecoversItsOwnBaseWithoutBeingTold() throws IOException {
        WriteAheadLog wal = new WriteAheadLog(logPath());
        RaftLog log = buildLog(wal, 10);
        log.compact(6, 2);

        // Deliberately does NOT pass the snapshot base: the log describes itself.
        RaftLog recovered = new RaftLog(new WriteAheadLog(logPath()));

        assertEquals(7, recovered.firstIndex(), "the retained tail starts after the snapshot");
        assertEquals(10, recovered.lastIndex(), "the tail keeps its absolute indexes");
        assertEquals(2, recovered.termAt(6), "the snapshot base term is restored");
        assertEquals("entry-7", new String(recovered.payloadAt(7), StandardCharsets.UTF_8));
        assertEquals("entry-10", new String(recovered.payloadAt(10), StandardCharsets.UTF_8));
    }

    @Test
    void aTruncatedLogStillRecoversFromIndexOne() throws IOException {
        WriteAheadLog wal = new WriteAheadLog(logPath());
        RaftLog log = buildLog(wal, 10);
        log.truncateFrom(4);

        RaftLog recovered = new RaftLog(new WriteAheadLog(logPath()));

        assertEquals(1, recovered.firstIndex());
        assertEquals(3, recovered.lastIndex());
        assertEquals("entry-1", new String(recovered.payloadAt(1), StandardCharsets.UTF_8));
    }

    @Test
    void compactionThenTruncationKeepsAbsoluteIndexes() throws IOException {
        WriteAheadLog wal = new WriteAheadLog(logPath());
        RaftLog log = buildLog(wal, 12);
        log.compact(5, 1);
        log.truncateFrom(10);

        RaftLog recovered = new RaftLog(new WriteAheadLog(logPath()));

        assertEquals(6, recovered.firstIndex());
        assertEquals(9, recovered.lastIndex());
        assertEquals("entry-6", new String(recovered.payloadAt(6), StandardCharsets.UTF_8));
        assertEquals("entry-9", new String(recovered.payloadAt(9), StandardCharsets.UTF_8));
    }

    @Test
    void anInstalledSnapshotRecoversItsBaseEvenWithAnEmptyTail() throws IOException {
        // resetTo leaves no entries at all. A deleted log file would recover as a
        // log starting at index 1, silently contradicting the installed snapshot.
        WriteAheadLog wal = new WriteAheadLog(logPath());
        RaftLog log = buildLog(wal, 6);
        log.resetTo(9, 3);

        RaftLog recovered = new RaftLog(new WriteAheadLog(logPath()));

        assertEquals(9, recovered.lastIndex(), "an empty log sits at the snapshot index");
        assertEquals(10, recovered.firstIndex(), "the next entry follows the snapshot");
        assertEquals(3, recovered.termAt(9), "the snapshot term must survive");
    }

    @Test
    void anExplicitBaseIsStillHonouredForLogsWithoutAMarker() throws IOException {
        // Backward compatibility: a log written before base markers existed has
        // no marker, so the caller-supplied base still applies.
        WriteAheadLog wal = new WriteAheadLog(logPath());
        RaftLog appendOnly = new RaftLog(wal);
        appendOnly.append(1, utf8("a"));
        appendOnly.append(1, utf8("b"));

        RaftLog recovered = new RaftLog(new WriteAheadLog(logPath()), 4, 1);
        assertEquals(5, recovered.firstIndex());
        assertEquals(6, recovered.lastIndex());
        assertTrue(recovered.payloadAt(5) != null);
    }
}
