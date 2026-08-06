package com.minigoogle.storage.metadata;

import com.minigoogle.cluster.RaftSnapshot;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for the crash-consistent durable Raft snapshot store. */
class RaftSnapshotStoreTest {

    private static final byte[] DATA = "kv-state".getBytes(StandardCharsets.UTF_8);

    private Path tempDir() throws IOException {
        Path dir = Files.createTempDirectory("raft-snapshot-test");
        dir.toFile().deleteOnExit();
        return dir;
    }

    private Path snapshotFile(Path dir) {
        return dir.resolve("raft-snapshot.bin");
    }

    @Test
    void testMissingFileLoadsNull() throws IOException {
        RaftSnapshotStore store = new RaftSnapshotStore(snapshotFile(tempDir()));
        assertNull(store.load());
    }

    @Test
    void testEmptyFileLoadsNull() throws IOException {
        Path file = snapshotFile(tempDir());
        Files.createFile(file);
        RaftSnapshotStore store = new RaftSnapshotStore(file);
        assertNull(store.load());
    }

    @Test
    void testSaveAndLoadRoundTrip() throws IOException {
        Path file = snapshotFile(tempDir());
        RaftSnapshotStore store = new RaftSnapshotStore(file);
        store.save(new RaftSnapshot(42, 7, DATA));

        RaftSnapshot loaded = store.load();
        assertNotNull(loaded);
        assertEquals(42, loaded.lastIncludedIndex());
        assertEquals(7, loaded.lastIncludedTerm());
        assertArrayEquals(DATA, loaded.data());
    }

    @Test
    void testSaveOverwritesPreviousSnapshot() throws IOException {
        Path file = snapshotFile(tempDir());
        RaftSnapshotStore store = new RaftSnapshotStore(file);
        store.save(new RaftSnapshot(5, 1, new byte[]{1}));
        store.save(new RaftSnapshot(9, 2, new byte[]{2}));

        RaftSnapshot loaded = store.load();
        assertEquals(9, loaded.lastIncludedIndex());
        assertEquals(2, loaded.lastIncludedTerm());
        assertArrayEquals(new byte[]{2}, loaded.data());
    }

    @Test
    void testReloadFromFreshStoreInstance() throws IOException {
        Path file = snapshotFile(tempDir());
        new RaftSnapshotStore(file).save(new RaftSnapshot(8, 3, DATA));

        RaftSnapshot loaded = new RaftSnapshotStore(file).load();
        assertNotNull(loaded);
        assertEquals(8, loaded.lastIncludedIndex());
        assertEquals(3, loaded.lastIncludedTerm());
        assertArrayEquals(DATA, loaded.data());
    }

    @Test
    void testLargeDataRoundTrip() throws IOException {
        Path file = snapshotFile(tempDir());
        byte[] large = new byte[64 * 1024];
        for (int i = 0; i < large.length; i++) {
            large[i] = (byte) i;
        }
        RaftSnapshotStore store = new RaftSnapshotStore(file);
        store.save(new RaftSnapshot(100, 4, large));

        assertArrayEquals(large, store.load().data());
    }

    @Test
    void testCorruptFileFailsFast() throws IOException {
        Path file = snapshotFile(tempDir());
        Files.writeString(file, "not-a-raft-snapshot");

        RaftSnapshotStore store = new RaftSnapshotStore(file);
        assertThrows(IOException.class, store::load);
    }

    @Test
    void testTruncatedFileFailsFast() throws IOException {
        Path file = snapshotFile(tempDir());
        byte[] truncated = {0x52, 0x53, 0x4E, 0x50, 1, 0, 0, 0, 42}; // magic, version, then only part of the index
        Files.write(file, truncated);

        RaftSnapshotStore store = new RaftSnapshotStore(file);
        assertThrows(IOException.class, store::load);
    }

    @Test
    void testWrongVersionFailsFast() throws IOException {
        Path file = snapshotFile(tempDir());
        byte[] future = {0x52, 0x53, 0x4E, 0x50, 9, 0, 0, 0, 1, 0, 0, 0, 2, 0, 0, 0, 0}; // magic, version=9
        Files.write(file, future);

        RaftSnapshotStore store = new RaftSnapshotStore(file);
        assertThrows(IOException.class, store::load);
    }

    @Test
    void testNegativeIndexFailsFast() throws IOException {
        Path file = snapshotFile(tempDir());
        byte[] negative = {0x52, 0x53, 0x4E, 0x50, 1,
                (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, // lastIncludedIndex = -1
                0, 0, 0, 1, 0, 0, 0, 0};
        Files.write(file, negative);

        RaftSnapshotStore store = new RaftSnapshotStore(file);
        assertThrows(IOException.class, store::load);
    }

    @Test
    void testTrailingBytesFailFast() throws IOException {
        Path file = snapshotFile(tempDir());
        RaftSnapshotStore store = new RaftSnapshotStore(file);
        store.save(new RaftSnapshot(1, 1, DATA));

        byte[] bytes = Files.readAllBytes(file);
        byte[] padded = new byte[bytes.length + 1];
        System.arraycopy(bytes, 0, padded, 0, bytes.length);
        padded[bytes.length] = 0;
        Files.write(file, padded);

        assertThrows(IOException.class, store::load);
    }

    @Test
    void testInMemoryStoreDoesNothing() throws IOException {
        RaftSnapshotStore store = RaftSnapshotStore.inMemory();
        store.save(new RaftSnapshot(7, 2, DATA));

        assertNull(store.load());
    }
}
