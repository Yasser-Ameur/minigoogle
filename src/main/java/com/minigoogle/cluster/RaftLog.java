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
 * <p>Indexes are 1-based; the in-memory list carries a dummy entry at position
 * 0 so that {@code entries.get(position)} lines up with an absolute index. A
 * {@link WriteAheadLog} may back the log for durability: every append is
 * fsynced before it is acknowledged, and a fresh instance on the same WAL
 * replays the persisted prefix on construction. Without a WAL the log is
 * memory-only ({@link #inMemory()}), which keeps the pre-existing consensus
 * constructors byte-for-byte compatible.</p>
 *
 * <p>After a state-machine snapshot compacts the log, the dummy entry becomes
 * the snapshot's {@code baseIndex}/{@code baseTerm}: the retained tail is
 * still addressed by its original absolute indexes, so replication never needs
 * to renumber. {@link #compact(int, int)} drops a known prefix and
 * {@link #resetTo(int, int)} replaces the whole log (an installed snapshot
 * that does not match the local log).</p>
 *
 * <p>Wire framing for a payload is {@code [4-byte big-endian int term][payload]},
 * embedded in the WAL record's payload. The framing is opaque to the transport,
 * so the cluster protocol stays at version 1.</p>
 */
public class RaftLog {

    /** WAL operation type for a replicated Raft entry. */
    public static final byte RAFT_ENTRY_OP = 0x01;

    /**
     * WAL operation type for the base marker: {@code [baseIndex:4][baseTerm:4]}.
     *
     * <p>WAL records carry a term and a payload but not their absolute index, so
     * replay infers each index from the record's position. That inference is only
     * valid if the reader knows which absolute index the first record represents.
     * Compaction writes this marker as the first record of the same atomic
     * replacement that drops the prefix, so the base and the entries can never
     * disagree — including after a crash, where the file is entirely the old
     * version or entirely the new one.</p>
     *
     * <p>Its absence means a log that was never compacted, which starts at 1.</p>
     */
    public static final byte RAFT_BASE_OP = 0x02;

    private final WriteAheadLog wal;
    private final List<LogEntry> entries = new ArrayList<>();
    private int baseIndex;
    private int baseTerm;

    /**
     * Creates a memory-only log. Entries are not persisted.
     */
    public RaftLog() {
        this.wal = null;
        this.baseIndex = 0;
        this.baseTerm = 0;
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
        this(wal, 0, 0);
    }

    /**
     * Creates a log backed by the given WAL whose retained entries start at
     * absolute index {@code baseIndex + 1}. Used when a durable snapshot has
     * already consumed the prefix {@code [1 .. baseIndex]}.
     *
     * @param wal       The write-ahead log, or {@code null} for a memory-only log.
     * @param baseIndex The absolute index the dummy entry represents (the
     *                  snapshot's last included index).
     * @param baseTerm  The term at {@code baseIndex}.
     * @throws IOException If persisted entries cannot be replayed.
     */
    public RaftLog(WriteAheadLog wal, int baseIndex, int baseTerm) throws IOException {
        this.wal = wal;
        List<WriteAheadLog.WalEntry> persisted =
                wal == null ? List.of() : wal.readAll();

        // A persisted base marker is authoritative: it was written atomically
        // with the entries beneath it, so it always describes this exact file.
        // The caller-supplied base applies only when the log carries no marker
        // (never compacted, or written before markers existed).
        int effectiveBase = baseIndex;
        int effectiveBaseTerm = baseTerm;
        for (WriteAheadLog.WalEntry walEntry : persisted) {
            if (walEntry.operationType() == RAFT_BASE_OP) {
                ByteBuffer marker = ByteBuffer.wrap(walEntry.payload());
                effectiveBase = marker.getInt();
                effectiveBaseTerm = marker.getInt();
            }
        }

        this.baseIndex = effectiveBase;
        this.baseTerm = effectiveBaseTerm;
        this.entries.add(new LogEntry(effectiveBase, effectiveBaseTerm, new byte[0]));
        for (WriteAheadLog.WalEntry walEntry : persisted) {
            if (walEntry.operationType() == RAFT_ENTRY_OP) {
                byte[] frame = walEntry.payload();
                entries.add(new LogEntry(entries.size() + effectiveBase,
                        termFromFrame(frame), payloadFromFrame(frame)));
            }
        }
    }

    /** Encodes the base marker record placed at the head of a replaced log. */
    private static WriteAheadLog.WalEntry baseMarker(int index, int term) {
        ByteBuffer buffer = ByteBuffer.allocate(8);
        buffer.putInt(index);
        buffer.putInt(term);
        return new WriteAheadLog.WalEntry(RAFT_BASE_OP, buffer.array());
    }

    /**
     * @return A memory-only log.
     */
    public static RaftLog inMemory() {
        return new RaftLog();
    }

    /**
     * @return The absolute index of the first retained entry, or
     *         {@code lastIndex() + 1} when the tail is empty. Entries at or
     *         below this index have been consumed by a snapshot.
     */
    public int firstIndex() {
        return baseIndex + 1;
    }

    /**
     * @return The index of the last entry, or {@code baseIndex} when the tail
     *         is empty (the snapshot's last included index).
     */
    public int lastIndex() {
        return baseIndex + entries.size() - 1;
    }

    /**
     * @return The term of the last entry, or the base term when the tail is
     *         empty.
     */
    public int lastTerm() {
        return entries.get(entries.size() - 1).term();
    }

    /**
     * @param index A 1-based index.
     * @return The term at {@code index}: the base term at {@code baseIndex},
     *         {@code 0} below the base (a stale AppendEntries then fails the
     *         log-consistency check and the leader falls back to snapshots),
     *         or {@code 0} past the end.
     */
    public int termAt(int index) {
        if (index == baseIndex) {
            return baseTerm;
        }
        if (index < baseIndex || index >= baseIndex + entries.size()) {
            return 0;
        }
        return entries.get(index - baseIndex).term();
    }

    /**
     * @param index A 1-based index.
     * @return The payload at {@code index}, or {@code null} if out of range or
     *         at or below the base (snapshot-compacted entries have no payload).
     */
    public byte[] payloadAt(int index) {
        if (index <= baseIndex || index >= baseIndex + entries.size()) {
            return null;
        }
        return entries.get(index - baseIndex).payload();
    }

    /**
     * Appends an entry. The entry is fsynced to the WAL before it becomes
     * visible, so a crash after this returns never loses it. A persistence
     * failure aborts the append with {@link UncheckedIOException}.
     *
     * @param term    The term of the entry (normally the leader's current term).
     * @param payload The opaque payload.
     * @return The new entry's 1-based absolute index.
     */
    public int append(int term, byte[] payload) {
        int index = baseIndex + entries.size();
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
     * Drops every entry at absolute index {@code index} or beyond, retaining
     * the prefix {@code [firstIndex(), index-1]}. Truncating at exactly
     * {@code firstIndex()} drops the whole tail, which is what a follower
     * whose retained entries diverge from the leader from the first entry
     * needs. The WAL is rewritten with the retained prefix. A persistence
     * failure aborts the truncation with {@link UncheckedIOException}.
     *
     * @param index The first entry to drop.
     */
    public void truncateFrom(int index) {
        if (index < firstIndex() || index > lastIndex()) {
            return;
        }
        if (wal != null) {
            // The retained prefix [firstIndex(), index-1] contains committed
            // entries. Rewriting it as clear-then-append would leave a window in
            // which the log is deleted and the survivors are not yet written, so
            // a crash there destroyed committed state. replaceAll swaps the file
            // atomically: a crash leaves either the old log or the new one.
            List<WriteAheadLog.WalEntry> retained = new ArrayList<>();
            retained.add(baseMarker(baseIndex, baseTerm));
            for (int i = firstIndex(); i < index; i++) {
                LogEntry entry = entries.get(i - baseIndex);
                retained.add(new WriteAheadLog.WalEntry(
                        RAFT_ENTRY_OP, toFrame(entry.term(), entry.payload())));
            }
            try {
                wal.replaceAll(retained);
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to persist raft log truncation", e);
            }
        }
        while (entries.size() > index - baseIndex) {
            entries.remove(entries.size() - 1);
        }
    }

    /**
     * Drops the prefix {@code [firstIndex() .. snapshotIndex]}, re-basing the
     * log at {@code snapshotIndex}/{@code snapshotTerm} and retaining the
     * absolute tail {@code [snapshotIndex+1 .. lastIndex()]}. The WAL is
     * replaced atomically with the retained tail. A no-op when
     * {@code snapshotIndex} is at or below the current base.
     *
     * <p>Crash safety comes from the atomic replacement, not from the tail being
     * uncommitted: a crash leaves either the complete pre-compaction log or the
     * complete post-compaction log. Entries at or below the snapshot are covered
     * by the durable snapshot, and the retained tail survives intact either way.
     * (The previous clear-then-rewrite made the weaker claim that only the
     * uncommitted tail was at risk; that reasoning did not hold for
     * {@link #truncateFrom(int)}, whose retained prefix is committed.)</p>
     *
     * @param snapshotIndex The absolute index the snapshot covers.
     * @param snapshotTerm  The term at {@code snapshotIndex}.
     */
    public void compact(int snapshotIndex, int snapshotTerm) {
        if (snapshotIndex <= baseIndex) {
            return;
        }
        List<LogEntry> tail = new ArrayList<>();
        for (int i = snapshotIndex + 1; i <= lastIndex(); i++) {
            tail.add(entries.get(i - baseIndex));
        }
        if (wal != null) {
            // Atomic swap for the same reason as truncateFrom: the retained tail
            // must never be observable as "deleted but not yet rewritten".
            List<WriteAheadLog.WalEntry> retained = new ArrayList<>();
            retained.add(baseMarker(snapshotIndex, snapshotTerm));
            for (LogEntry entry : tail) {
                retained.add(new WriteAheadLog.WalEntry(
                        RAFT_ENTRY_OP, toFrame(entry.term(), entry.payload())));
            }
            try {
                wal.replaceAll(retained);
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to persist raft log compaction", e);
            }
        }
        baseIndex = snapshotIndex;
        baseTerm = snapshotTerm;
        entries.clear();
        entries.add(new LogEntry(snapshotIndex, snapshotTerm, new byte[0]));
        entries.addAll(tail);
    }

    /**
     * Replaces the entire log with an installed snapshot: drops every retained
     * entry, clears the WAL, and re-bases at {@code snapshotIndex}/
     * {@code snapshotTerm}. Used when a follower installs a snapshot that does
     * not match its local log (the local entries are uncommitted and the
     * leader will re-send anything beyond the snapshot). A no-op when
     * {@code snapshotIndex} is at or below the current base.
     *
     * @param snapshotIndex The absolute index the snapshot covers.
     * @param snapshotTerm  The term at {@code snapshotIndex}.
     */
    public void resetTo(int snapshotIndex, int snapshotTerm) {
        if (snapshotIndex < baseIndex) {
            return;
        }
        if (wal != null) {
            // An empty log still has to record where it starts: after an
            // installed snapshot the next entry is snapshotIndex + 1, and a
            // deleted file would recover as a log starting at 1. Written through
            // replaceAll so the re-base is atomic like every other rewrite.
            try {
                wal.replaceAll(List.of(baseMarker(snapshotIndex, snapshotTerm)));
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to persist raft log reset", e);
            }
        }
        baseIndex = snapshotIndex;
        baseTerm = snapshotTerm;
        entries.clear();
        entries.add(new LogEntry(snapshotIndex, snapshotTerm, new byte[0]));
    }

    /**
     * Returns up to {@code max} wire frames starting at {@code fromIndex},
     * each encoded as {@code [4-byte term][payload]}. Requests below the base
     * are clamped to {@code firstIndex()}.
     *
     * @param fromIndex The first 1-based index to include.
     * @param max       The maximum number of entries to include.
     * @return The encoded frames; empty when {@code fromIndex} is past the end.
     */
    public List<byte[]> entriesFrom(int fromIndex, int max) {
        List<byte[]> frames = new ArrayList<>();
        int start = Math.max(firstIndex(), fromIndex);
        int end = Math.min(baseIndex + entries.size(), start + Math.max(0, max));
        for (int i = start; i < end; i++) {
            LogEntry entry = entries.get(i - baseIndex);
            frames.add(toFrame(entry.term(), entry.payload()));
        }
        return frames;
    }

    /**
     * @return The entries currently in the log (excluding the base sentinel).
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
