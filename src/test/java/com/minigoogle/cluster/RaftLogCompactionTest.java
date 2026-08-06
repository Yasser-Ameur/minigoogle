package com.minigoogle.cluster;

import com.minigoogle.storage.wal.WriteAheadLog;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for RaftLog prefix compaction and base re-indexing. */
class RaftLogCompactionTest {

    private static final String PREFIX = "e";

    private Path tempDir() throws IOException {
        Path dir = Files.createTempDirectory("raft-log-compaction-test");
        dir.toFile().deleteOnExit();
        return dir;
    }

    private WriteAheadLog wal(Path dir) {
        return new WriteAheadLog(dir.resolve("raft-log.bin"));
    }

    private byte[] payload(String suffix) {
        return (PREFIX + suffix).getBytes(StandardCharsets.UTF_8);
    }

    private RaftLog logWith(int count) {
        RaftLog log = RaftLog.inMemory();
        for (int i = 1; i <= count; i++) {
            log.append(i, payload(Integer.toString(i)));
        }
        return log;
    }

    @Test
    void testCompactDropsPrefixAndKeepsAbsoluteIndexes() {
        RaftLog log = logWith(10);
        log.compact(6, 6);

        assertEquals(7, log.firstIndex());
        assertEquals(10, log.lastIndex());
        assertEquals(10, log.lastTerm());
        assertEquals(6, log.termAt(6), "term at the base is the snapshot term");
        assertEquals(0, log.termAt(5), "below the base is out of range");
        assertNull(log.payloadAt(6), "the snapshot entry has no payload");
        assertArrayEquals(payload("7"), log.payloadAt(7));
        assertEquals(4, log.snapshot().size(), "only the tail [7..10] is retained");
    }

    @Test
    void testCompactWithEmptyTail() {
        RaftLog log = logWith(10);
        log.compact(10, 10);

        assertEquals(11, log.firstIndex());
        assertEquals(10, log.lastIndex(), "an empty tail leaves the log at the base");
        assertEquals(10, log.lastTerm());
        assertTrue(log.snapshot().isEmpty());
        assertEquals(10, log.termAt(10));
    }

    @Test
    void testAppendAfterCompactUsesAbsoluteIndex() {
        RaftLog log = logWith(10);
        log.compact(6, 6);

        assertEquals(11, log.append(7, payload("11")));
        assertEquals(11, log.lastIndex());
        assertArrayEquals(payload("11"), log.payloadAt(11));
    }

    @Test
    void testEntriesFromClampsBelowBase() {
        RaftLog log = logWith(10);
        log.compact(6, 6);

        List<byte[]> frames = log.entriesFrom(3, 100);
        assertEquals(4, frames.size(), "clamps to the retained tail");
        assertEquals(7, RaftLog.termFromFrame(frames.get(0)));
        assertArrayEquals(payload("7"), RaftLog.payloadFromFrame(frames.get(0)));
        assertEquals(10, RaftLog.termFromFrame(frames.get(3)));
    }

    @Test
    void testTruncateFromAfterCompact() {
        RaftLog log = logWith(10);
        log.compact(6, 6);

        log.truncateFrom(8);
        assertEquals(7, log.lastIndex());
        assertArrayEquals(payload("7"), log.payloadAt(7));
        assertEquals(0, log.termAt(8));
    }

    @Test
    void testTruncateFromWholeTailAfterCompact() {
        RaftLog log = logWith(10);
        log.compact(6, 6);

        log.truncateFrom(7);
        assertEquals(6, log.lastIndex(), "truncating at the first retained index empties the tail");
        assertTrue(log.snapshot().isEmpty());
        assertEquals(6, log.termAt(6));
    }

    @Test
    void testCompactIsNoOpAtOrBelowBase() {
        RaftLog log = logWith(10);
        log.compact(6, 6);
        log.compact(4, 4);
        assertEquals(7, log.firstIndex());

        log.compact(6, 99);
        assertEquals(6, log.termAt(6), "re-compacting at the same index keeps the original base term");
    }

    @Test
    void testResetToReplacesEverything() {
        RaftLog log = logWith(10);
        log.resetTo(6, 6);

        assertEquals(7, log.firstIndex());
        assertEquals(6, log.lastIndex());
        assertEquals(6, log.lastTerm());
        assertTrue(log.snapshot().isEmpty());
    }

    @Test
    void testResetToIsNoOpBelowBase() {
        RaftLog log = logWith(10);
        log.compact(6, 6);
        log.resetTo(4, 4);

        assertEquals(7, log.firstIndex(), "a snapshot behind the base must not move the base backward");
    }

    @Test
    void testCompactionSurvivesReopenWithBase() throws IOException {
        Path dir = tempDir();
        RaftLog log = new RaftLog(wal(dir));
        for (int i = 1; i <= 10; i++) {
            log.append(i, payload(Integer.toString(i)));
        }
        log.compact(6, 6);
        log.append(7, payload("11"));

        RaftLog replayed = new RaftLog(wal(dir), 6, 6);
        assertEquals(7, replayed.firstIndex());
        assertEquals(11, replayed.lastIndex());
        assertEquals(6, replayed.termAt(6), "the base term survives reopen");
        assertNull(replayed.payloadAt(6));
        assertArrayEquals(payload("7"), replayed.payloadAt(7));
        assertArrayEquals(payload("11"), replayed.payloadAt(11));
    }

    @Test
    void testCompactionTailSurvivesReopenWithBase() throws IOException {
        Path dir = tempDir();
        RaftLog log = new RaftLog(wal(dir));
        for (int i = 1; i <= 10; i++) {
            log.append(i, payload(Integer.toString(i)));
        }
        log.compact(10, 10);

        RaftLog replayed = new RaftLog(wal(dir), 10, 10);
        assertEquals(11, replayed.firstIndex());
        assertEquals(10, replayed.lastIndex());
        assertTrue(replayed.snapshot().isEmpty());
        assertEquals(10, replayed.termAt(10));
    }

    @Test
    void testResetToSurvivesReopenWithBase() throws IOException {
        Path dir = tempDir();
        RaftLog log = new RaftLog(wal(dir));
        for (int i = 1; i <= 10; i++) {
            log.append(i, payload(Integer.toString(i)));
        }
        log.resetTo(6, 6);
        log.append(7, payload("11"));

        RaftLog replayed = new RaftLog(wal(dir), 6, 6);
        assertEquals(7, replayed.firstIndex());
        assertEquals(7, replayed.lastIndex());
        assertEquals(7, replayed.lastTerm());
        assertArrayEquals(payload("11"), replayed.payloadAt(7));
    }

    @Test
    void testReplayWithBaseTerminatesAndKeepsSentinelAligned() throws IOException {
        Path dir = tempDir();
        RaftLog log = new RaftLog(wal(dir));
        log.append(1, payload("1"));
        log.append(1, payload("2"));

        RaftLog replayed = new RaftLog(wal(dir), 0, 0);
        assertEquals(1, replayed.firstIndex());
        assertEquals(2, replayed.lastIndex());
        assertArrayEquals(payload("1"), replayed.payloadAt(1));
        assertArrayEquals(payload("2"), replayed.payloadAt(2));
    }
}
