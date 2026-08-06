package com.minigoogle.storage.metadata;

import com.minigoogle.cluster.ClusterConfiguration;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for the crash-consistent durable Raft config store. */
class RaftConfigurationStoreTest {

    private Path tempDir() throws IOException {
        Path dir = Files.createTempDirectory("raft-config-test");
        dir.toFile().deleteOnExit();
        return dir;
    }

    private Path configFile(Path dir) {
        return dir.resolve("raft-config.bin");
    }

    @Test
    void testMissingFileLoadsEmpty() throws IOException {
        RaftConfigurationStore store = new RaftConfigurationStore(configFile(tempDir()));
        assertTrue(store.load().isEmpty());
    }

    @Test
    void testEmptyFileLoadsEmpty() throws IOException {
        Path file = configFile(tempDir());
        Files.createFile(file);
        RaftConfigurationStore store = new RaftConfigurationStore(file);
        assertTrue(store.load().isEmpty());
    }

    @Test
    void testSaveAndLoadRoundTrip() throws IOException {
        Path file = configFile(tempDir());
        RaftConfigurationStore store = new RaftConfigurationStore(file);
        store.persist(ClusterConfiguration.of("node-1", "node-2", "node-3"));

        assertEquals(Set.of("node-1", "node-2", "node-3"), store.load().members());
    }

    @Test
    void testPersistOverwritesPreviousConfig() throws IOException {
        Path file = configFile(tempDir());
        RaftConfigurationStore store = new RaftConfigurationStore(file);
        store.persist(ClusterConfiguration.of("node-1", "node-2", "node-3"));
        store.persist(ClusterConfiguration.of("node-1", "node-2", "node-3", "node-4"));

        assertEquals(Set.of("node-1", "node-2", "node-3", "node-4"), store.load().members());
    }

    @Test
    void testReloadFromFreshStoreInstance() throws IOException {
        Path file = configFile(tempDir());
        new RaftConfigurationStore(file).persist(ClusterConfiguration.of("a", "b", "c"));

        assertEquals(Set.of("a", "b", "c"), new RaftConfigurationStore(file).load().members());
    }

    @Test
    void testManyMembersRoundTrip() throws IOException {
        Path file = configFile(tempDir());
        List<String> members = IntStream.rangeClosed(1, 50)
                .mapToObj(i -> "node-" + i)
                .collect(Collectors.toList());
        new RaftConfigurationStore(file).persist(ClusterConfiguration.of(members));

        assertEquals(Set.copyOf(members), new RaftConfigurationStore(file).load().members());
    }

    @Test
    void testPersistEmptyConfig() throws IOException {
        Path file = configFile(tempDir());
        RaftConfigurationStore store = new RaftConfigurationStore(file);
        store.persist(ClusterConfiguration.EMPTY);

        assertTrue(store.load().isEmpty());
    }

    @Test
    void testCorruptFileFailsFast() throws IOException {
        Path file = configFile(tempDir());
        Files.writeString(file, "not-a-raft-config");

        RaftConfigurationStore store = new RaftConfigurationStore(file);
        assertThrows(IOException.class, store::load);
    }

    @Test
    void testTruncatedFileFailsFast() throws IOException {
        Path file = configFile(tempDir());
        byte[] truncated = {0x52, 0x43, 0x4F, 0x4E, 1, 0, 0, 0}; // magic, version, then only part of the count
        Files.write(file, truncated);

        RaftConfigurationStore store = new RaftConfigurationStore(file);
        assertThrows(IOException.class, store::load);
    }

    @Test
    void testWrongVersionFailsFast() throws IOException {
        Path file = configFile(tempDir());
        byte[] future = {0x52, 0x43, 0x4F, 0x4E, 9, 0, 0, 0, 1, 0, 1, 'a'}; // magic, version=9
        Files.write(file, future);

        RaftConfigurationStore store = new RaftConfigurationStore(file);
        assertThrows(IOException.class, store::load);
    }

    @Test
    void testNegativeMemberCountFailsFast() throws IOException {
        Path file = configFile(tempDir());
        byte[] negative = {0x52, 0x43, 0x4F, 0x4E, 1,
                (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF}; // memberCount = -1
        Files.write(file, negative);

        RaftConfigurationStore store = new RaftConfigurationStore(file);
        assertThrows(IOException.class, store::load);
    }

    @Test
    void testTrailingBytesFailFast() throws IOException {
        Path file = configFile(tempDir());
        RaftConfigurationStore store = new RaftConfigurationStore(file);
        store.persist(ClusterConfiguration.of("a"));

        byte[] bytes = Files.readAllBytes(file);
        byte[] padded = new byte[bytes.length + 1];
        System.arraycopy(bytes, 0, padded, 0, bytes.length);
        padded[bytes.length] = 0;
        Files.write(file, padded);

        assertThrows(IOException.class, store::load);
    }

    @Test
    void testInMemoryStoreDoesNothing() throws IOException {
        RaftConfigurationStore store = RaftConfigurationStore.inMemory();
        store.persist(ClusterConfiguration.of("a", "b", "c"));

        assertTrue(store.load().isEmpty());
    }
}
