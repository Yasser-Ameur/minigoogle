package com.minigoogle.storage.metadata;

import com.minigoogle.cluster.ClusterConfiguration;
import com.minigoogle.storage.serialization.BinaryReader;
import com.minigoogle.storage.serialization.BinaryWriter;

import java.io.IOException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Crash-consistent store for the Raft committed configuration: the set of
 * member node IDs the consensus layer uses for quorum.
 *
 * <p>Writes are atomic, mirroring {@link RaftMetadataStore} and
 * {@link RaftAppliedStore}: the config is serialized to a sibling temp file
 * with {@link BinaryWriter}, fsynced, and moved over the target with an atomic
 * rename. A missing or empty file yields the empty config; a corrupt file fails
 * fast so a node never derives a quorum from a wrong member set.
 *
 * <p>The in-memory variant ({@link #inMemory()}) is a no-op used by nodes and
 * tests that do not opt into persistence.
 *
 * <p>File format: {@code magic (4 bytes, "RCON")} + {@code version (1 byte, 1)}
 * + {@code memberCount (4 bytes)} + {@code member (length-prefixed strings)}.
 */
public class RaftConfigurationStore {

    private static final int MAGIC = 0x52434F4E; // "RCON"
    private static final byte VERSION = 1;

    private final Path file;
    private final boolean persistent;

    /**
     * Creates a store that persists to the given file.
     *
     * @param file The target config file path.
     */
    public RaftConfigurationStore(Path file) {
        this.file = file;
        this.persistent = true;
    }

    private RaftConfigurationStore() {
        this.file = null;
        this.persistent = false;
    }

    /**
     * @return A no-op store: {@link #load()} returns the empty config and
     *         {@link #persist(ClusterConfiguration)} writes nothing.
     */
    public static RaftConfigurationStore inMemory() {
        return new RaftConfigurationStore();
    }

    /**
     * Reads the persisted committed config, or the empty config when none
     * exists. A corrupt, truncated, or version-mismatched file raises
     * {@link IOException} so startup fails fast rather than silently deriving
     * a quorum from a wrong member set.
     *
     * @return The committed config, or {@link ClusterConfiguration#EMPTY}.
     * @throws IOException If the file exists but cannot be parsed.
     */
    public ClusterConfiguration load() throws IOException {
        if (!persistent || !Files.exists(file) || Files.size(file) == 0) {
            return ClusterConfiguration.EMPTY;
        }
        byte[] bytes = Files.readAllBytes(file);
        try {
            BinaryReader reader = new BinaryReader(ByteBuffer.wrap(bytes));
            if (reader.readInt() != MAGIC) {
                throw new IOException("Invalid raft config file (bad magic): " + file);
            }
            if (reader.readByte() != VERSION) {
                throw new IOException("Unsupported raft config version in file: " + file);
            }
            int memberCount = reader.readInt();
            if (memberCount < 0) {
                throw new IOException("Negative member count in raft config file: " + file);
            }
            List<String> members = new ArrayList<>(memberCount);
            for (int i = 0; i < memberCount; i++) {
                members.add(reader.readString());
            }
            if (reader.hasRemaining()) {
                throw new IOException("Trailing bytes in raft config file: " + file);
            }
            return ClusterConfiguration.of(members);
        } catch (BufferUnderflowException e) {
            throw new IOException("Truncated raft config file: " + file, e);
        }
    }

    /**
     * Durably records the committed config. The write is atomic: serialize to a
     * sibling temp file, fsync, then rename over the target. A no-op for the
     * in-memory variant.
     *
     * @param config The committed config to persist.
     * @throws IOException If the config cannot be persisted.
     */
    public synchronized void persist(ClusterConfiguration config) throws IOException {
        if (!persistent) {
            return;
        }
        Path target = file;
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        List<String> members = new ArrayList<>(config.members());
        try (BinaryWriter writer = new BinaryWriter(tmp)) {
            writer.writeInt(MAGIC);
            writer.writeByte(VERSION);
            writer.writeInt(members.size());
            for (String member : members) {
                writer.writeString(member);
            }
        }
        try (FileChannel channel = FileChannel.open(tmp, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
}
