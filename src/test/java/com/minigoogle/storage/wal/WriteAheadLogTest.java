package com.minigoogle.storage.wal;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for the write-ahead log's append persistence. */
class WriteAheadLogTest {

    private Path tempDir() throws IOException {
        Path dir = Files.createTempDirectory("wal-test");
        dir.toFile().deleteOnExit();
        return dir;
    }

    private WriteAheadLog log(Path dir) {
        return new WriteAheadLog(dir.resolve("wal.bin"));
    }

    @Test
    void testMissingFileReadsEmpty() throws IOException {
        WriteAheadLog log = log(tempDir());
        assertTrue(log.readAll().isEmpty());
    }

    @Test
    void testMultipleAppendsAreAllPersisted() throws IOException {
        Path dir = tempDir();
        WriteAheadLog log = log(dir);
        log.append((byte) 1, "first".getBytes(StandardCharsets.UTF_8));
        log.append((byte) 2, "second".getBytes(StandardCharsets.UTF_8));
        log.append((byte) 3, "third".getBytes(StandardCharsets.UTF_8));

        // Regression: append() used to truncate the file before each write, so
        // only the last entry survived. All entries must be present, in order.
        List<WriteAheadLog.WalEntry> entries = log(dir).readAll();
        assertEquals(3, entries.size());
        assertEquals(1, entries.get(0).operationType());
        assertEquals("first", new String(entries.get(0).payload(), StandardCharsets.UTF_8));
        assertEquals(2, entries.get(1).operationType());
        assertEquals("second", new String(entries.get(1).payload(), StandardCharsets.UTF_8));
        assertEquals(3, entries.get(2).operationType());
        assertEquals("third", new String(entries.get(2).payload(), StandardCharsets.UTF_8));
    }

    @Test
    void testClearThenAppendStartsFresh() throws IOException {
        Path dir = tempDir();
        WriteAheadLog log = log(dir);
        log.append((byte) 1, "first".getBytes(StandardCharsets.UTF_8));
        log.clear();
        log.append((byte) 9, "after".getBytes(StandardCharsets.UTF_8));

        List<WriteAheadLog.WalEntry> entries = log.readAll();
        assertEquals(1, entries.size());
        assertEquals(9, entries.get(0).operationType());
        assertEquals("after", new String(entries.get(0).payload(), StandardCharsets.UTF_8));
    }
}
