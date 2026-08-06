package com.minigoogle.storage.metadata;

import com.minigoogle.cluster.ClusterConfiguration;
import com.minigoogle.cluster.RaftSnapshot;
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
 * Crash-consistent store for the latest Raft state-machine snapshot.
 *
 * <p>Writes are atomic, mirroring {@link RaftMetadataStore} and
 * {@link RaftAppliedStore}: the snapshot is serialized to a sibling temp file
 * with {@link BinaryWriter}, fsynced, and moved over the target with an atomic
 * rename. A missing or empty file yields {@code null}; a corrupt file fails
 * fast so a state machine is never restored from a wrong snapshot.
 *
 * <p>The in-memory variant ({@link #inMemory()}) is a no-op used by nodes and
 * tests that do not opt into persistence.
 *
 * <p>v2 file format: {@code magic (4 bytes, "RSNP")} + {@code version (1 byte, 2)}
 * + {@code lastIncludedIndex (4 bytes)} + {@code lastIncludedTerm (4 bytes)}
 * + {@code configCount (4 bytes)} + {@code member (length-prefixed strings)}*
 * + {@code dataLength (4 bytes)} + {@code data}. The config section was added in
 * v2; v1 files (no config) are still readable and yield a snapshot whose config
 * is the empty {@link ClusterConfiguration}.
 */
public class RaftSnapshotStore {

    private static final int MAGIC = 0x52534E50; // "RSNP"
    private static final byte VERSION = 2;
    private static final byte VERSION_1 = 1;

    private final Path file;
    private final boolean persistent;

    /**
     * Creates a store that persists to the given file.
     *
     * @param file The target snapshot file path.
     */
    public RaftSnapshotStore(Path file) {
        this.file = file;
        this.persistent = true;
    }

    private RaftSnapshotStore() {
        this.file = null;
        this.persistent = false;
    }

    /**
     * @return A no-op store: {@link #load()} returns {@code null} and
     *         {@link #save(RaftSnapshot)} writes nothing.
     */
    public static RaftSnapshotStore inMemory() {
        return new RaftSnapshotStore();
    }

    /**
     * Reads the persisted snapshot, or {@code null} when none exists. Both the
     * v1 (no config) and v2 (config-carrying) formats are accepted; anything
     * else raises {@link IOException} so startup fails fast rather than
     * silently restoring a wrong snapshot.
     *
     * @return The latest snapshot, or {@code null}.
     * @throws IOException If the file exists but cannot be parsed.
     */
    public RaftSnapshot load() throws IOException {
        if (!persistent || !Files.exists(file) || Files.size(file) == 0) {
            return null;
        }
        byte[] bytes = Files.readAllBytes(file);
        try {
            BinaryReader reader = new BinaryReader(ByteBuffer.wrap(bytes));
            if (reader.readInt() != MAGIC) {
                throw new IOException("Invalid raft snapshot file (bad magic): " + file);
            }
            int version = reader.readByte();
            if (version != VERSION && version != VERSION_1) {
                throw new IOException("Unsupported raft snapshot version in file: " + file);
            }
            int lastIncludedIndex = reader.readInt();
            int lastIncludedTerm = reader.readInt();
            if (lastIncludedIndex < 0 || lastIncludedTerm < 0) {
                throw new IOException("Negative field in raft snapshot file: " + file);
            }
            ClusterConfiguration config = ClusterConfiguration.EMPTY;
            if (version == VERSION) {
                int configCount = reader.readInt();
                if (configCount < 0) {
                    throw new IOException("Negative config count in raft snapshot file: " + file);
                }
                List<String> members = new ArrayList<>(configCount);
                for (int i = 0; i < configCount; i++) {
                    members.add(reader.readString());
                }
                config = ClusterConfiguration.of(members);
            }
            int dataLength = reader.readInt();
            if (dataLength < 0) {
                throw new IOException("Negative field in raft snapshot file: " + file);
            }
            byte[] data = new byte[dataLength];
            reader.getBuffer().get(data);
            if (reader.hasRemaining()) {
                throw new IOException("Trailing bytes in raft snapshot file: " + file);
            }
            return new RaftSnapshot(lastIncludedIndex, lastIncludedTerm, data, config);
        } catch (BufferUnderflowException e) {
            throw new IOException("Truncated raft snapshot file: " + file, e);
        }
    }

    /**
     * Durably records a snapshot in the v2 format (committed config included).
     * The write is atomic: serialize to a sibling temp file, fsync, then rename
     * over the target. A no-op for the in-memory variant.
     *
     * @param snapshot The snapshot to persist.
     * @throws IOException If the snapshot cannot be persisted.
     */
    public synchronized void save(RaftSnapshot snapshot) throws IOException {
        if (!persistent) {
            return;
        }
        Path target = file;
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        byte[] data = snapshot.data();
        List<String> members = new ArrayList<>(snapshot.config().members());
        try (BinaryWriter writer = new BinaryWriter(tmp)) {
            writer.writeInt(MAGIC);
            writer.writeByte(VERSION);
            writer.writeInt(snapshot.lastIncludedIndex());
            writer.writeInt(snapshot.lastIncludedTerm());
            writer.writeInt(members.size());
            for (String member : members) {
                writer.writeString(member);
            }
            writer.writeInt(data.length);
            writer.writeBytes(data);
        }
        try (FileChannel channel = FileChannel.open(tmp, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
}
