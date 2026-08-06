package com.minigoogle.cluster;

import com.minigoogle.storage.wal.WriteAheadLog;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * The Raft replicated log.
 *
 * <p>Indexes are 1-based; the in-memory list carries a dummy entry at index 0
 * so that {@code entries.get(index) == index}. A {@link WriteAheadLog} may back
 * the log for durability: every append is fsynced before it is acknowledged,
 * and a fresh instance on the same WAL replays the persisted prefix on
 * construction. Without a WAL the log is memory-only ({@link #inMemory()}),
 * which keeps the pre-existing consensus constructors byte-for-byte
 * compatible.</p>
 *
 * <p>Wire framing for a payload is {@code [4-byte big-endian int term][payload]},
 * embedded in the WAL record's payload. The framing is opaque to the transport,
 * so the cluster protocol stays at version 1.</p>
 */
public class RaftLog {

    /** WAL operation type for a replicated Raft entry. */
    public static final byte RAFT_ENTRY_OP = 0x01;

    private final WriteAheadLog wal;
    private final List<LogEntry> entries = new ArrayList<>();

    /**
     * Creates a memory-only log. Entries are not persisted.
     */
    public RaftLog() {
        this.wal = null;
        this.entries.add(new LogEntry(0, 0, new byte[0]));
    }

    /**
     * Creates a log backed by the given WAL. Any persisted entries are
     * replayed in order on construction.
     *
     * @param wal The write-ahead log, or {@code null} for a memory-only log.
     * @throws IOException If persisted entries cannot be replayed.
     */
    public RaftLog(WriteAheadLog wal) throws IOException {
        this.wal = wal;
        this.entries.add(new LogEntry(0, 0, new byte[0]));
        if (wal != null) {
            for (WriteAheadLog.WalEntry walEntry : wal.readAll()) {
                if (walEntry.operationType() == RAFT_ENTRY_OP) {
                    byte[] frame = walEntry.payload();
                    entries.add(new LogEntry(entries.size(), termFromFrame(frame), payloadFromFrame(frame)));
                }
            }
        }
    }

    /**
     * @return A memory-only log.
     */
    public static RaftLog inMemory() {
        return new RaftLog();
    }

    /**
     * @return The index of the last entry, or 0 when the log is empty.
     */
    public int lastIndex() {
        return entries.size() - 1;
    }

    /**
     * @return The term of the last entry, or 0 when the log is empty.
     */
    public int lastTerm() {
        return entries.get(entries.size() - 1).term();
    }

    /**
     * @param index A 1-based index.
     * @return The term at {@code index}, or 0 if the index is out of range.
     */
    public int termAt(int index) {
        if (index < 0 || index >= entries.size()) {
            return 0;
        }
        return entries.get(index).term();
    }

    /**
     * @param index A 1-based index.
     * @return The payload at {@code index}, or {@code null} if out of range.
     */
    public byte[] payloadAt(int index) {
        if (index < 0 || index >= entries.size()) {
            return null;
        }
        return entries.get(index).payload();
    }

    /**
     * Appends an entry. The entry is fsynced to the WAL before it becomes
     * visible, so a crash after this returns never loses it. A persistence
     * failure aborts the append with {@link UncheckedIOException}.
     *
     * @param term    The term of the entry (normally the leader's current term).
     * @param payload The opaque payload.
     * @return The new entry's 1-based index.
     */
    public int append(int term, byte[] payload) {
        int index = entries.size();
        if (wal != null) {
            try {
                wal.append(RAFT_ENTRY_OP, toFrame(term, payload));
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to persist raft log entry", e);
            }
        }
        entries.add(new LogEntry(index, term, payload));
        return index;
    }

    /**
     * Drops every entry at {@code index} or beyond, retaining the prefix
     * {@code [1, index-1]}. The WAL is rewritten with the retained prefix. A
     * persistence failure aborts the truncation with
     * {@link UncheckedIOException}.
     *
     * @param index The first entry to drop.
     */
    public void truncateFrom(int index) {
        if (index <= 1 || index >= entries.size()) {
            return;
        }
        if (wal != null) {
            try {
                wal.clear();
                for (int i = 1; i < index; i++) {
                    LogEntry entry = entries.get(i);
                    wal.append(RAFT_ENTRY_OP, toFrame(entry.term(), entry.payload()));
                }
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to persist raft log truncation", e);
            }
        }
        while (entries.size() > index) {
            entries.remove(entries.size() - 1);
        }
    }

    /**
     * Returns up to {@code max} wire frames starting at {@code fromIndex},
     * each encoded as {@code [4-byte term][payload]}.
     *
     * @param fromIndex The first 1-based index to include.
     * @param max       The maximum number of entries to include.
     * @return The encoded frames; empty when {@code fromIndex} is past the end.
     */
    public List<byte[]> entriesFrom(int fromIndex, int max) {
        List<byte[]> frames = new ArrayList<>();
        int start = Math.max(1, fromIndex);
        int end = Math.min(entries.size(), start + Math.max(0, max));
        for (int i = start; i < end; i++) {
            LogEntry entry = entries.get(i);
            frames.add(toFrame(entry.term(), entry.payload()));
        }
        return frames;
    }

    /**
     * @return The entries currently in the log (excluding the index-0 sentinel).
     */
    public List<LogEntry> snapshot() {
        return List.copyOf(entries.subList(1, entries.size()));
    }

    /**
     * Encodes a term and payload as a wire frame: {@code [4-byte term][payload]}.
     */
    public static byte[] toFrame(int term, byte[] payload) {
        ByteBuffer buffer = ByteBuffer.allocate(4 + payload.length);
        buffer.putInt(term);
        buffer.put(payload);
        return buffer.array();
    }

    /**
     * Reads the term from the first 4 bytes of a wire frame.
     */
    public static int termFromFrame(byte[] frame) {
        return ByteBuffer.wrap(frame, 0, 4).getInt();
    }

    /**
     * Reads the payload from a wire frame (everything after the 4-byte term).
     */
    public static byte[] payloadFromFrame(byte[] frame) {
        byte[] payload = new byte[frame.length - 4];
        System.arraycopy(frame, 4, payload, 0, payload.length);
        return payload;
    }
}
