package com.minigoogle.cluster;

import com.minigoogle.storage.wal.WriteAheadLog;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for the Raft replicated log and its WAL-backed durability. */
class RaftLogTest {

    private Path tempDir() throws IOException {
        Path dir = Files.createTempDirectory("raft-log-test");
        dir.toFile().deleteOnExit();
        return dir;
    }

    private WriteAheadLog wal(Path dir) {
        return new WriteAheadLog(dir.resolve("raft-log.bin"));
    }

    @Test
    void testEmptyLogDefaults() {
        RaftLog log = RaftLog.inMemory();
        assertEquals(0, log.lastIndex());
        assertEquals(0, log.lastTerm());
        assertEquals(0, log.termAt(1));
        assertNull(log.payloadAt(1));
        assertTrue(log.snapshot().isEmpty());
    }

    @Test
    void testAppendAdvancesIndexesAndTerms() {
        RaftLog log = RaftLog.inMemory();
        assertEquals(1, log.append(1, "a".getBytes(StandardCharsets.UTF_8)));
        assertEquals(2, log.append(1, "b".getBytes(StandardCharsets.UTF_8)));
        assertEquals(3, log.append(2, "c".getBytes(StandardCharsets.UTF_8)));

        assertEquals(3, log.lastIndex());
        assertEquals(2, log.lastTerm());
        assertEquals(1, log.termAt(1));
        assertEquals(2, log.termAt(3));
        assertArrayEquals("b".getBytes(StandardCharsets.UTF_8), log.payloadAt(2));
        assertEquals(0, log.termAt(4));
    }

    @Test
    void testFrameRoundTrip() {
        byte[] payload = "hello".getBytes(StandardCharsets.UTF_8);
        byte[] frame = RaftLog.toFrame(42, payload);
        assertEquals(42, RaftLog.termFromFrame(frame));
        assertArrayEquals(payload, RaftLog.payloadFromFrame(frame));
    }

    @Test
    void testEntriesFromReturnsFramesInRange() {
        RaftLog log = RaftLog.inMemory();
        log.append(1, "a".getBytes(StandardCharsets.UTF_8));
        log.append(1, "b".getBytes(StandardCharsets.UTF_8));
        log.append(2, "c".getBytes(StandardCharsets.UTF_8));

        List<byte[]> frames = log.entriesFrom(2, 2);
        assertEquals(2, frames.size());
        assertEquals(1, RaftLog.termFromFrame(frames.get(0)));
        assertArrayEquals("b".getBytes(StandardCharsets.UTF_8), RaftLog.payloadFromFrame(frames.get(0)));
        assertEquals(2, RaftLog.termFromFrame(frames.get(1)));

        assertTrue(log.entriesFrom(4, 5).isEmpty());
        assertEquals(3, log.entriesFrom(1, 100).size());
    }

    @Test
    void testTruncateFromDropsTail() {
        RaftLog log = RaftLog.inMemory();
        log.append(1, "a".getBytes(StandardCharsets.UTF_8));
        log.append(1, "b".getBytes(StandardCharsets.UTF_8));
        log.append(2, "c".getBytes(StandardCharsets.UTF_8));

        log.truncateFrom(2);

        assertEquals(1, log.lastIndex());
        assertEquals(1, log.lastTerm());
        assertArrayEquals("a".getBytes(StandardCharsets.UTF_8), log.payloadAt(1));
        assertEquals(0, log.termAt(2));
    }

    @Test
    void testTruncateFromKeepsPrefixAndIsNoOpOutOfRange() throws IOException {
        Path dir = tempDir();
        RaftLog log = new RaftLog(wal(dir));
        log.append(1, "a".getBytes(StandardCharsets.UTF_8));
        log.append(1, "b".getBytes(StandardCharsets.UTF_8));
        log.append(2, "c".getBytes(StandardCharsets.UTF_8));
        log.append(2, "d".getBytes(StandardCharsets.UTF_8));

        log.truncateFrom(3);

        assertEquals(2, log.lastIndex());
        assertArrayEquals("a".getBytes(StandardCharsets.UTF_8), log.payloadAt(1));
        assertArrayEquals("b".getBytes(StandardCharsets.UTF_8), log.payloadAt(2));

        // Out-of-range truncation must be a no-op.
        log.truncateFrom(1);
        log.truncateFrom(10);
        assertEquals(2, log.lastIndex());
    }

    @Test
    void testWALBackedAppendsSurviveReopen() throws IOException {
        Path dir = tempDir();
        RaftLog log = new RaftLog(wal(dir));
        log.append(1, "a".getBytes(StandardCharsets.UTF_8));
        log.append(1, "b".getBytes(StandardCharsets.UTF_8));
        log.append(3, "c".getBytes(StandardCharsets.UTF_8));

        RaftLog replayed = new RaftLog(wal(dir));
        assertEquals(3, replayed.lastIndex());
        assertEquals(3, replayed.lastTerm());
        assertEquals(1, replayed.termAt(1));
        assertEquals(3, replayed.termAt(3));
        assertArrayEquals("b".getBytes(StandardCharsets.UTF_8), replayed.payloadAt(2));
        assertArrayEquals("c".getBytes(StandardCharsets.UTF_8), replayed.payloadAt(3));
    }

    @Test
    void testWALBackedTruncatePersistsRetainedPrefix() throws IOException {
        Path dir = tempDir();
        RaftLog log = new RaftLog(wal(dir));
        log.append(1, "a".getBytes(StandardCharsets.UTF_8));
        log.append(1, "b".getBytes(StandardCharsets.UTF_8));
        log.append(2, "c".getBytes(StandardCharsets.UTF_8));

        log.truncateFrom(2);
        log.append(2, "d".getBytes(StandardCharsets.UTF_8));

        RaftLog replayed = new RaftLog(wal(dir));
        assertEquals(2, replayed.lastIndex());
        assertArrayEquals("a".getBytes(StandardCharsets.UTF_8), replayed.payloadAt(1));
        assertEquals(1, replayed.termAt(1));
        assertArrayEquals("d".getBytes(StandardCharsets.UTF_8), replayed.payloadAt(2));
        assertEquals(2, replayed.termAt(2));
    }
}
