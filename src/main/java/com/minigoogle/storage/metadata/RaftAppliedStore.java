package com.minigoogle.storage.metadata;

import com.minigoogle.storage.serialization.BinaryReader;
import com.minigoogle.storage.serialization.BinaryWriter;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.BufferUnderflowException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * Crash-consistent store for the Raft apply watermark: the highest log index
 * whose entry has been applied to the state machine.
 *
 * <p>Writes are atomic, mirroring {@link RaftMetadataStore}: the value is
 * serialized to a sibling temp file with {@link BinaryWriter}, fsynced, and
 * moved over the target with an atomic rename. A missing file yields 0; a
 * corrupt file fails fast so a state machine is never silently rebuilt from a
 * wrong prefix.
 *
 * <p>The in-memory variant ({@link #inMemory()}) is a no-op used by nodes and
 * tests that do not opt into persistence.
 *
 * <p>File format: {@code magic (4 bytes, "RAPP")} + {@code version (1 byte, 1)}
 * + {@code lastApplied (4 bytes)}.
 */
public class RaftAppliedStore {

    private static final int MAGIC = 0x52415050; // "RAPP"
    private static final byte VERSION = 1;

    private final Path file;
    private final boolean persistent;

    /**
     * Creates a store that persists to the given file.
     *
     * @param file The target applied-index file path.
     */
    public RaftAppliedStore(Path file) {
        this.file = file;
        this.persistent = true;
    }

    private RaftAppliedStore() {
        this.file = null;
        this.persistent = false;
    }

    /**
     * @return A no-op store: {@link #load()} returns 0 and
     *         {@link #persist(int)} writes nothing.
     */
    public static RaftAppliedStore inMemory() {
        return new RaftAppliedStore();
    }

    /**
     * Reads the persisted apply watermark. A missing or empty file yields 0. A
     * corrupt, truncated, or version-mismatched file raises {@link IOException}
     * so startup fails fast.
     *
     * @return The last applied log index.
     * @throws IOException If the file exists but cannot be parsed.
     */
    public int load() throws IOException {
        if (!persistent || !Files.exists(file) || Files.size(file) == 0) {
            return 0;
        }
        byte[] bytes = Files.readAllBytes(file);
        try {
            BinaryReader reader = new BinaryReader(ByteBuffer.wrap(bytes));
            if (reader.readInt() != MAGIC) {
                throw new IOException("Invalid raft applied file (bad magic): " + file);
            }
            if (reader.readByte() != VERSION) {
                throw new IOException("Unsupported raft applied version in file: " + file);
            }
            int lastApplied = reader.readInt();
            if (lastApplied < 0) {
                throw new IOException("Negative raft applied index in file: " + file);
            }
            return lastApplied;
        } catch (BufferUnderflowException e) {
            throw new IOException("Truncated raft applied file: " + file, e);
        }
    }

    /**
     * Durably records the apply watermark. The write is atomic: serialize to a
     * sibling temp file, fsync, then rename over the target. A no-op for the
     * in-memory variant.
     *
     * @param lastApplied The last applied log index.
     * @throws IOException If the record cannot be persisted.
     */
    public synchronized void persist(int lastApplied) throws IOException {
        if (!persistent) {
            return;
        }
        Path target = file;
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        try (BinaryWriter writer = new BinaryWriter(tmp)) {
            writer.writeInt(MAGIC);
            writer.writeByte(VERSION);
            writer.writeInt(lastApplied);
        }
        try (FileChannel channel = FileChannel.open(tmp, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
}
