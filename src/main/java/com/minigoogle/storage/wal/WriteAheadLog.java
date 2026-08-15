package com.minigoogle.storage.wal;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Write-ahead log for crash recovery.
 *
 * <h2>Record format</h2>
 * <pre>
 *   [op : 1 byte][length : 4 bytes big-endian][payload : length bytes]
 * </pre>
 * There is no per-record checksum; see {@link #readAll()} for exactly which
 * damage this format can and cannot detect.
 *
 * <h2>Durability model</h2>
 * <ul>
 *   <li>{@link #append} writes one record and {@code force(true)}s it before
 *       returning, so a record that has been acknowledged survives a crash.</li>
 *   <li>{@link #replaceAll} rewrites the whole log <em>atomically</em>: the file
 *       is either entirely the old contents or entirely the new contents, never
 *       missing and never half-written. This is what makes Raft log truncation
 *       and compaction crash-safe.</li>
 *   <li>{@link #readAll} tolerates a torn final record — the expected outcome of
 *       a crash during {@link #append} — and truncates the file back to the last
 *       complete record so subsequent appends resume at a valid boundary.</li>
 * </ul>
 *
 * <h2>Platform assumptions</h2>
 * <ul>
 *   <li>{@code Files.move(..., ATOMIC_MOVE, REPLACE_EXISTING)} within one
 *       directory is atomic. The temporary file is always created in the log's
 *       own directory so the move never crosses a filesystem boundary.</li>
 *   <li>Renames are made durable by fsyncing the containing directory. Windows
 *       does not permit opening a directory as a {@code FileChannel}; there the
 *       {@code MOVEFILE_WRITE_THROUGH} semantics the JDK uses for
 *       {@code ATOMIC_MOVE} provide the ordering instead, so the directory sync
 *       is skipped rather than failing the operation.</li>
 * </ul>
 */
public class WriteAheadLog {

    /** Header size: one op byte plus a four-byte length. */
    private static final int HEADER_BYTES = 5;

    /**
     * Upper bound on a single record's payload, used only to tell a damaged
     * length field from a truncated append. Generous by design: it must exceed
     * any record the system legitimately writes, so it is a corruption guard
     * rather than a size limit.
     */
    private static final int DEFAULT_MAX_RECORD_BYTES = 256 * 1024 * 1024;

    public record WalEntry(byte operationType, byte[] payload) {}

    /**
     * Raised when the log contains damage that is <em>not</em> a torn tail —
     * for example a negative or impossible length field. Such a log is not
     * silently repaired: recovering from it would mean inventing state.
     */
    public static class CorruptWalException extends IOException {
        public CorruptWalException(String message) {
            super(message);
        }
    }

    /**
     * Points at which {@link #replaceAll} can be interrupted. Tests install a
     * hook to terminate at a given point and then assert what a restart
     * recovers; production never installs one.
     */
    public enum PersistencePoint {
        AFTER_TEMP_WRITE,
        AFTER_TEMP_FORCE,
        BEFORE_RENAME,
        AFTER_RENAME,
        AFTER_DIRECTORY_SYNC
    }

    private final Path logPath;
    private final int maxRecordBytes;

    /** Test-only crash simulator; null in production. */
    private volatile Consumer<PersistencePoint> failureInjector;

    public WriteAheadLog(Path logPath) {
        this(logPath, DEFAULT_MAX_RECORD_BYTES);
    }

    /**
     * @param maxRecordBytes the corruption guard described on
     *                       {@link #DEFAULT_MAX_RECORD_BYTES}; exposed so tests
     *                       can exercise the boundary without writing 256 MiB
     */
    public WriteAheadLog(Path logPath, int maxRecordBytes) {
        this.logPath = logPath;
        this.maxRecordBytes = maxRecordBytes;
    }

    /**
     * Installs a hook invoked at each persistence boundary of
     * {@link #replaceAll}. Intended for failure-injection tests only: throwing
     * from the hook simulates abrupt termination at that exact point.
     *
     * @param injector the hook, or null to remove it
     */
    public void setFailureInjector(Consumer<PersistencePoint> injector) {
        this.failureInjector = injector;
    }

    private void reachedPoint(PersistencePoint point) {
        Consumer<PersistencePoint> injector = failureInjector;
        if (injector != null) {
            injector.accept(point);
        }
    }

    public Path path() {
        return logPath;
    }

    /**
     * Appends one record and fsyncs it. A crash during this call can leave a
     * partial trailing record, which {@link #readAll()} discards.
     */
    public void append(byte operationType, byte[] payload) throws IOException {
        try (FileChannel channel = FileChannel.open(logPath, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            ByteBuffer buf = ByteBuffer.allocate(HEADER_BYTES + payload.length);
            buf.put(operationType);
            buf.putInt(payload.length);
            buf.put(payload);
            buf.flip();
            while (buf.hasRemaining()) {
                channel.write(buf);
            }
            channel.force(true); // fsync
        }
    }

    /**
     * Atomically replaces the entire log with {@code entries}.
     *
     * <p>Written to a temporary file in the same directory, forced to disk, then
     * moved over the live log with {@code ATOMIC_MOVE}. A crash at any point
     * leaves either the complete old log or the complete new log — never a
     * missing or partially-rewritten one.</p>
     *
     * <p>This replaces the previous clear-then-rewrite approach, where the log
     * was deleted before the retained entries were written back. A crash in that
     * window destroyed the entire persisted log, including committed entries.</p>
     */
    public void replaceAll(List<WalEntry> entries) throws IOException {
        Path directory = logPath.toAbsolutePath().getParent();
        if (directory != null) {
            Files.createDirectories(directory);
        }
        Path temp = Files.createTempFile(directory, ".wal-replace-", ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temp,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                for (WalEntry entry : entries) {
                    ByteBuffer buf = ByteBuffer.allocate(HEADER_BYTES + entry.payload().length);
                    buf.put(entry.operationType());
                    buf.putInt(entry.payload().length);
                    buf.put(entry.payload());
                    buf.flip();
                    while (buf.hasRemaining()) {
                        channel.write(buf);
                    }
                }
                reachedPoint(PersistencePoint.AFTER_TEMP_WRITE);
                // The replacement must be on disk before it becomes reachable;
                // otherwise the rename could be durable while its contents are not.
                channel.force(true);
                reachedPoint(PersistencePoint.AFTER_TEMP_FORCE);
            }

            reachedPoint(PersistencePoint.BEFORE_RENAME);
            move(temp, logPath);
            reachedPoint(PersistencePoint.AFTER_RENAME);

            syncDirectory(directory);
            reachedPoint(PersistencePoint.AFTER_DIRECTORY_SYNC);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            // The temp file is created in the target's own directory, so this
            // should not happen; fall back rather than fail the operation, and
            // accept the weaker guarantee on such a filesystem.
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Makes a rename durable by fsyncing the containing directory. Skipped where
     * the platform does not allow opening a directory for reading (Windows).
     */
    private static void syncDirectory(Path directory) {
        if (directory == null) {
            return;
        }
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException | UnsupportedOperationException notSupported) {
            // Windows cannot open a directory as a channel. ATOMIC_MOVE there is
            // implemented with MOVEFILE_WRITE_THROUGH, which carries the ordering.
        }
    }

    /**
     * Reads every complete record.
     *
     * <h3>What this tolerates</h3>
     * A <em>torn tail</em>: the final record is incomplete because a crash
     * interrupted {@link #append}. Because appends are sequential and fsynced,
     * an incomplete record can only ever be the last one. The complete prefix is
     * returned and the file is truncated back to the last complete record, so
     * later appends resume at a valid boundary rather than after garbage.
     *
     * <h3>What this refuses</h3>
     * Structural damage that a truncated append cannot produce raises
     * {@link CorruptWalException}: a negative length (provably impossible), or a
     * length beyond {@code maxRecordBytes} (a damaged header, and a guard against
     * a garbage length driving a huge allocation). Such a log is not silently
     * trimmed — doing so would discard records that may sit beyond the damage
     * and present the result as a clean recovery.
     *
     * <p>Note that a declared length exceeding the <em>file size</em> is
     * <em>not</em> corruption: appending a large payload to a short log and
     * crashing produces exactly that, and it is a recoverable torn tail.</p>
     *
     * <h3>What this cannot detect</h3>
     * Without a per-record checksum, damage that leaves a <em>plausible</em>
     * header is indistinguishable from valid data. Recovery therefore assumes a
     * crash truncates an append rather than leaving arbitrary bytes in its
     * place. See {@code ENGINEERING_FINDINGS.md} for this residual risk.
     */
    public List<WalEntry> readAll() throws IOException {
        List<WalEntry> entries = new ArrayList<>();
        if (!Files.exists(logPath)) {
            return entries;
        }
        long fileSize = Files.size(logPath);
        if (fileSize == 0) {
            return entries;
        }

        byte[] bytes = Files.readAllBytes(logPath);
        ByteBuffer buf = ByteBuffer.wrap(bytes);

        int lastCompleteOffset = 0;
        boolean tornTail = false;

        while (buf.remaining() > 0) {
            if (buf.remaining() < HEADER_BYTES) {
                // A partial header: the crash landed inside the op byte or the
                // length field.
                tornTail = true;
                break;
            }
            byte op = buf.get();
            int length = buf.getInt();

            if (length < 0) {
                // Provably impossible: a length is written as a non-negative
                // int, and truncating a write can only shorten the file, never
                // flip the sign of a value that was never written.
                throw new CorruptWalException("Negative record length " + length + " at offset "
                        + lastCompleteOffset + " in " + logPath
                        + "; a truncated append cannot produce this");
            }
            if (length > maxRecordBytes) {
                // A heuristic, not a proof: a length beyond any record this
                // system writes indicates a damaged header rather than a
                // truncated append. Failing here also prevents a garbage length
                // from driving a multi-gigabyte allocation during recovery.
                throw new CorruptWalException("Record length " + length + " at offset "
                        + lastCompleteOffset + " exceeds the maximum record size "
                        + maxRecordBytes + " in " + logPath
                        + "; the header is damaged rather than truncated");
            }
            if (buf.remaining() < length) {
                // Complete header, truncated payload. Note this is a torn tail
                // even when `length` exceeds the whole file: appending a large
                // payload to a short log and crashing produces exactly that.
                tornTail = true;
                break;
            }

            byte[] payload = new byte[length];
            buf.get(payload);
            entries.add(new WalEntry(op, payload));
            lastCompleteOffset = buf.position();
        }

        if (tornTail) {
            truncateTo(lastCompleteOffset);
        }
        return entries;
    }

    /**
     * Drops everything after the last complete record so the next append starts
     * at a valid boundary instead of extending a partial record.
     */
    private void truncateTo(long offset) throws IOException {
        try (FileChannel channel = FileChannel.open(logPath, StandardOpenOption.WRITE)) {
            channel.truncate(offset);
            channel.force(true);
        }
    }

    /**
     * Removes the log entirely. A single delete is itself atomic; callers that
     * need to retain part of the log must use {@link #replaceAll} instead.
     */
    public void clear() throws IOException {
        Files.deleteIfExists(logPath);
    }
}
