package com.minigoogle.storage.metadata;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for the crash-consistent durable Raft metadata store. */
class RaftMetadataStoreTest {

    private Path tempDir() throws IOException {
        Path dir = Files.createTempDirectory("raft-meta-test");
        dir.toFile().deleteOnExit();
        return dir;
    }

    private Path metadataFile(Path dir) {
        return dir.resolve("raft-metadata.bin");
    }

    @Test
    void testMissingFileLoadsDefaults() throws IOException {
        RaftMetadataStore store = new RaftMetadataStore(metadataFile(tempDir()));
        RaftMetadata metadata = store.load();
        assertEquals(0, metadata.currentTerm());
        assertNull(metadata.votedFor());
    }

    @Test
    void testEmptyFileLoadsDefaults() throws IOException {
        Path file = metadataFile(tempDir());
        Files.createFile(file);
        RaftMetadataStore store = new RaftMetadataStore(file);
        RaftMetadata metadata = store.load();
        assertEquals(0, metadata.currentTerm());
        assertNull(metadata.votedFor());
    }

    @Test
    void testPersistAndLoadRoundTrip() throws IOException {
        Path file = metadataFile(tempDir());
        RaftMetadataStore store = new RaftMetadataStore(file);
        store.persist(7, "candidate-a");

        RaftMetadata metadata = store.load();
        assertEquals(7, metadata.currentTerm());
        assertEquals("candidate-a", metadata.votedFor());
    }

    @Test
    void testPersistNullVote() throws IOException {
        Path file = metadataFile(tempDir());
        RaftMetadataStore store = new RaftMetadataStore(file);
        store.persist(7, null);

        RaftMetadata metadata = store.load();
        assertEquals(7, metadata.currentTerm());
        assertNull(metadata.votedFor());
    }

    @Test
    void testPersistOverwritesPreviousRecord() throws IOException {
        Path file = metadataFile(tempDir());
        RaftMetadataStore store = new RaftMetadataStore(file);
        store.persist(3, "candidate-a");
        store.persist(9, "candidate-b");

        RaftMetadata metadata = store.load();
        assertEquals(9, metadata.currentTerm());
        assertEquals("candidate-b", metadata.votedFor());
    }

    @Test
    void testReloadFromFreshStoreInstance() throws IOException {
        Path file = metadataFile(tempDir());
        new RaftMetadataStore(file).persist(5, "candidate-b");

        RaftMetadataStore reader = new RaftMetadataStore(file);
        RaftMetadata metadata = reader.load();
        assertEquals(5, metadata.currentTerm());
        assertEquals("candidate-b", metadata.votedFor());
    }

    @Test
    void testCorruptFileFailsFast() throws IOException {
        Path file = metadataFile(tempDir());
        Files.writeString(file, "not-raft-metadata-at-all");

        RaftMetadataStore store = new RaftMetadataStore(file);
        assertThrows(IOException.class, store::load);
    }

    @Test
    void testTruncatedFileFailsFast() throws IOException {
        Path file = metadataFile(tempDir());
        byte[] truncated = {0x52, 0x4D, 0x45, 0x54, 1, 0, 0, 0, 2, 1}; // magic, version, term=2, vote flag, then nothing
        Files.write(file, truncated);

        RaftMetadataStore store = new RaftMetadataStore(file);
        assertThrows(IOException.class, store::load);
    }

    @Test
    void testWrongVersionFailsFast() throws IOException {
        Path file = metadataFile(tempDir());
        byte[] future = {0x52, 0x4D, 0x45, 0x54, 9, 0, 0, 0, 1, 0}; // magic, version=9
        Files.write(file, future);

        RaftMetadataStore store = new RaftMetadataStore(file);
        assertThrows(IOException.class, store::load);
    }

    @Test
    void testInMemoryStoreDoesNothing() throws IOException {
        RaftMetadataStore store = RaftMetadataStore.inMemory();
        store.persist(4, "node-1");

        RaftMetadata metadata = store.load();
        assertEquals(0, metadata.currentTerm());
        assertNull(metadata.votedFor());
    }
}
