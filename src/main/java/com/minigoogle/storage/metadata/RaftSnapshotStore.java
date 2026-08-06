package com.minigoogle.storage.metadata;

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
 * <p>File format: {@code magic (4 bytes, "RSNP")} + {@code version (1 byte, 1)}
 * + {@code lastIncludedIndex (4 bytes)} + {@code lastIncludedTerm (4 bytes)}
 * + {@code dataLength (4 bytes)} + {@code data}.
 */
public class RaftSnapshotStore {

    private static final int MAGIC = 0x52534E50; // "RSNP"
    private static final byte VERSION = 1;

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
     * Reads the persisted snapshot, or {@code null} when none exists. A
     * corrupt, truncated, or version-mismatched file raises {@link IOException}
     * so startup fails fast rather than silently restoring a wrong snapshot.
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
            if (reader.readByte() != VERSION) {
                throw new IOException("Unsupported raft snapshot version in file: " + file);
            }
            int lastIncludedIndex = reader.readInt();
            int lastIncludedTerm = reader.readInt();
            int dataLength = reader.readInt();
            if (lastIncludedIndex < 0 || lastIncludedTerm < 0 || dataLength < 0) {
                throw new IOException("Negative field in raft snapshot file: " + file);
            }
            byte[] data = new byte[dataLength];
            reader.getBuffer().get(data);
            if (reader.hasRemaining()) {
                throw new IOException("Trailing bytes in raft snapshot file: " + file);
            }
            return new RaftSnapshot(lastIncludedIndex, lastIncludedTerm, data);
        } catch (BufferUnderflowException e) {
            throw new IOException("Truncated raft snapshot file: " + file, e);
        }
    }

    /**
     * Durably records a snapshot. The write is atomic: serialize to a sibling
     * temp file, fsync, then rename over the target. A no-op for the in-memory
     * variant.
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
        try (BinaryWriter writer = new BinaryWriter(tmp)) {
            writer.writeInt(MAGIC);
            writer.writeByte(VERSION);
            writer.writeInt(snapshot.lastIncludedIndex());
            writer.writeInt(snapshot.lastIncludedTerm());
            writer.writeInt(data.length);
            writer.writeBytes(data);
        }
        try (FileChannel channel = FileChannel.open(tmp, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
}
