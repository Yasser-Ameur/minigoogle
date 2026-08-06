package com.minigoogle.storage.metadata;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for the crash-consistent durable Raft apply watermark store. */
class RaftAppliedStoreTest {

    private Path tempDir() throws IOException {
        Path dir = Files.createTempDirectory("raft-applied-test");
        dir.toFile().deleteOnExit();
        return dir;
    }

    private Path appliedFile(Path dir) {
        return dir.resolve("raft-applied.bin");
    }

    @Test
    void testMissingFileLoadsZero() throws IOException {
        RaftAppliedStore store = new RaftAppliedStore(appliedFile(tempDir()));
        assertEquals(0, store.load());
    }

    @Test
    void testEmptyFileLoadsZero() throws IOException {
        Path file = appliedFile(tempDir());
        Files.createFile(file);
        RaftAppliedStore store = new RaftAppliedStore(file);
        assertEquals(0, store.load());
    }

    @Test
    void testPersistAndLoadRoundTrip() throws IOException {
        Path file = appliedFile(tempDir());
        RaftAppliedStore store = new RaftAppliedStore(file);
        store.persist(12);

        assertEquals(12, store.load());
    }

    @Test
    void testPersistOverwritesPreviousRecord() throws IOException {
        Path file = appliedFile(tempDir());
        RaftAppliedStore store = new RaftAppliedStore(file);
        store.persist(4);
        store.persist(9);

        assertEquals(9, store.load());
    }

    @Test
    void testReloadFromFreshStoreInstance() throws IOException {
        Path file = appliedFile(tempDir());
        new RaftAppliedStore(file).persist(5);

        RaftAppliedStore reader = new RaftAppliedStore(file);
        assertEquals(5, reader.load());
    }

    @Test
    void testCorruptFileFailsFast() throws IOException {
        Path file = appliedFile(tempDir());
        Files.writeString(file, "not-raft-applied-at-all");

        RaftAppliedStore store = new RaftAppliedStore(file);
        assertThrows(IOException.class, store::load);
    }

    @Test
    void testTruncatedFileFailsFast() throws IOException {
        Path file = appliedFile(tempDir());
        byte[] truncated = {0x52, 0x41, 0x50, 0x50, 1, 0, 0, 0}; // magic, version, then only 1 byte of the int
        Files.write(file, truncated);

        RaftAppliedStore store = new RaftAppliedStore(file);
        assertThrows(IOException.class, store::load);
    }

    @Test
    void testWrongVersionFailsFast() throws IOException {
        Path file = appliedFile(tempDir());
        byte[] future = {0x52, 0x41, 0x50, 0x50, 9, 0, 0, 0, 3}; // magic, version=9
        Files.write(file, future);

        RaftAppliedStore store = new RaftAppliedStore(file);
        assertThrows(IOException.class, store::load);
    }

    @Test
    void testNegativeIndexFailsFast() throws IOException {
        Path file = appliedFile(tempDir());
        byte[] negative = {0x52, 0x41, 0x50, 0x50, 1, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF};
        Files.write(file, negative);

        RaftAppliedStore store = new RaftAppliedStore(file);
        assertThrows(IOException.class, store::load);
    }

    @Test
    void testInMemoryStoreDoesNothing() throws IOException {
        RaftAppliedStore store = RaftAppliedStore.inMemory();
        store.persist(7);

        assertEquals(0, store.load());
    }
}
