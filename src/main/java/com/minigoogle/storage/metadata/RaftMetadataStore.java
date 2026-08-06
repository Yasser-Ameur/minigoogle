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
 * Crash-consistent store for the two Raft fields that must survive a restart:
 * {@code currentTerm} and {@code votedFor}.
 *
 * <p>Writes are atomic: the record is serialized to a sibling temp file with
 * {@link BinaryWriter}, fsynced, and moved over the target with an atomic
 * rename, so a torn or truncated file is never visible. Loading a missing file
 * yields the initial state; loading a corrupt file fails fast so a vote is
 * never silently reset.
 *
 * <p>The in-memory variant ({@link #inMemory()}) is a no-op used by nodes and
 * tests that do not opt into persistence; it keeps the pre-existing
 * {@code RaftConsensus} and {@code ClusterNode} constructors byte-for-byte
 * compatible.
 *
 * <p>File format: {@code magic (4 bytes, "RMET")} + {@code version (1 byte, 1)}
 * + {@code currentTerm (4 bytes)} + {@code votedFor} as a 1-byte presence flag
 * followed by a length-prefixed UTF-8 string when present.
 */
public class RaftMetadataStore {

    private static final int MAGIC = 0x524D4554; // "RMET"
    private static final byte VERSION = 1;

    private final Path file;
    private final boolean persistent;

    /**
     * Creates a store that persists to the given file.
     *
     * @param file The target metadata file path.
     */
    public RaftMetadataStore(Path file) {
        this.file = file;
        this.persistent = true;
    }

    private RaftMetadataStore() {
        this.file = null;
        this.persistent = false;
    }

    /**
     * @return A no-op store: {@link #load()} returns the initial state and
     *         {@link #persist(int, String)} writes nothing.
     */
    public static RaftMetadataStore inMemory() {
        return new RaftMetadataStore();
    }

    /**
     * Reads the persisted metadata. A missing or empty file yields the initial
     * state ({@code term 0}, no vote). A corrupt, truncated, or version-mismatched
     * file raises {@link IOException} so startup fails fast rather than silently
     * forgetting a vote.
     *
     * @return The persisted {@link RaftMetadata}.
     * @throws IOException If the file exists but cannot be parsed.
     */
    public RaftMetadata load() throws IOException {
        if (!persistent || !Files.exists(file) || Files.size(file) == 0) {
            return RaftMetadata.empty();
        }
        // readAllBytes (not mmap): the file is tiny, and a lingering mapped
        // view would prevent the atomic rename in persist() on some platforms.
        byte[] bytes = Files.readAllBytes(file);
        try {
            BinaryReader reader = new BinaryReader(ByteBuffer.wrap(bytes));
            if (reader.readInt() != MAGIC) {
                throw new IOException("Invalid raft metadata file (bad magic): " + file);
            }
            if (reader.readByte() != VERSION) {
                throw new IOException("Unsupported raft metadata version in file: " + file);
            }
            int term = reader.readInt();
            String votedFor = null;
            if (reader.readByte() == 1) {
                votedFor = reader.readString();
            }
            return new RaftMetadata(term, votedFor);
        } catch (BufferUnderflowException e) {
            throw new IOException("Truncated raft metadata file: " + file, e);
        }
    }

    /**
     * Durably records the current term and vote. The write is atomic: serialize
     * to a sibling temp file, fsync, then rename over the target. A no-op for
     * the in-memory variant.
     *
     * @param term     The highest term observed.
     * @param votedFor The candidate voted for in {@code term}, or {@code null}.
     * @throws IOException If the record cannot be persisted.
     */
    public synchronized void persist(int term, String votedFor) throws IOException {
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
            writer.writeInt(term);
            if (votedFor == null) {
                writer.writeByte((byte) 0);
            } else {
                writer.writeByte((byte) 1);
                writer.writeString(votedFor);
            }
        }
        try (FileChannel channel = FileChannel.open(tmp, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
}
