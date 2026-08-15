package com.minigoogle.storage.wal;

import com.minigoogle.storage.wal.WriteAheadLog.CorruptWalException;
import com.minigoogle.storage.wal.WriteAheadLog.WalEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Torn-tail and corruption recovery, driven by writing real bytes to disk rather
 * than by calling methods in sequence.
 *
 * <p>A crash during {@link WriteAheadLog#append} leaves a partial trailing
 * record. Because appends are sequential and fsynced, an incomplete record can
 * only ever be the last one — so an incomplete tail is recoverable, while
 * structural damage a truncated append cannot produce is not.</p>
 */
class WriteAheadLogRecoveryTest {

    private static final byte OP = 0x01;

    @TempDir
    Path tempDir;

    private Path logPath() {
        return tempDir.resolve("raft-log.bin");
    }

    /** Encodes a complete record exactly as {@link WriteAheadLog#append} does. */
    private static byte[] record(byte op, byte[] payload) {
        ByteBuffer buf = ByteBuffer.allocate(5 + payload.length);
        buf.put(op).putInt(payload.length).put(payload);
        return buf.array();
    }

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    /** Writes raw bytes, simulating whatever a crash left behind. */
    private void writeRaw(byte[]... chunks) throws IOException {
        try (var channel = java.nio.channels.FileChannel.open(logPath(),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            for (byte[] chunk : chunks) {
                ByteBuffer buf = ByteBuffer.wrap(chunk);
                while (buf.hasRemaining()) {
                    channel.write(buf);
                }
            }
        }
    }

    // ── Case A: valid records then a partial final record ──

    @Test
    void validRecordsReplayAndATornFinalRecordIsDiscarded() throws IOException {
        byte[] full = record(OP, utf8("entry-one"));
        byte[] second = record(OP, utf8("entry-two"));
        byte[] torn = record(OP, utf8("entry-three"));
        // Keep only the first 8 bytes of the third record: a complete header
        // (5 bytes) plus 3 payload bytes of 11.
        byte[] partial = new byte[8];
        System.arraycopy(torn, 0, partial, 0, 8);

        writeRaw(full, second, partial);

        WriteAheadLog wal = new WriteAheadLog(logPath());
        List<WalEntry> entries = wal.readAll();

        assertEquals(2, entries.size(), "the two complete records must replay");
        assertArrayEquals(utf8("entry-one"), entries.get(0).payload());
        assertArrayEquals(utf8("entry-two"), entries.get(1).payload());

        // The file must be truncated back to the last complete boundary, so a
        // later append does not extend the partial record.
        assertEquals(full.length + second.length, Files.size(logPath()),
                "the torn tail must be removed from the file");
    }

    @Test
    void appendAfterTornTailRecoveryProducesAReadableLog() throws IOException {
        byte[] full = record(OP, utf8("kept"));
        byte[] tornHeaderOnly = {OP, 0, 0, 0};
        writeRaw(full, tornHeaderOnly);

        WriteAheadLog wal = new WriteAheadLog(logPath());
        assertEquals(1, wal.readAll().size(), "recovery drops the torn tail");

        wal.append(OP, utf8("after-recovery"));

        List<WalEntry> entries = wal.readAll();
        assertEquals(2, entries.size(), "the post-recovery append must be readable");
        assertArrayEquals(utf8("kept"), entries.get(0).payload());
        assertArrayEquals(utf8("after-recovery"), entries.get(1).payload());
    }

    // ── Case B: partial record header ──

    @Test
    void partialHeaderIsTreatedAsATornTail() throws IOException {
        byte[] full = record(OP, utf8("complete"));
        // Op byte plus two of the four length bytes.
        writeRaw(full, new byte[]{OP, 0, 0});

        WriteAheadLog wal = new WriteAheadLog(logPath());
        List<WalEntry> entries = wal.readAll();

        assertEquals(1, entries.size());
        assertArrayEquals(utf8("complete"), entries.get(0).payload());
        assertEquals(full.length, Files.size(logPath()));
    }

    @Test
    void aLoneOpByteIsATornTail() throws IOException {
        writeRaw(new byte[]{OP});

        WriteAheadLog wal = new WriteAheadLog(logPath());
        assertTrue(wal.readAll().isEmpty(), "a single stray byte yields no records");
        assertEquals(0, Files.size(logPath()), "the file is truncated to empty");
    }

    // ── Case C: complete header, incomplete payload ──

    @Test
    void completeHeaderWithTruncatedPayloadIsATornTail() throws IOException {
        byte[] full = record(OP, utf8("first"));
        // Header declares 100 bytes; only 10 follow.
        ByteBuffer header = ByteBuffer.allocate(5 + 10);
        header.put(OP).putInt(100).put(new byte[10]);

        writeRaw(full, header.array());

        WriteAheadLog wal = new WriteAheadLog(logPath());
        List<WalEntry> entries = wal.readAll();

        assertEquals(1, entries.size(), "only the complete record replays");
        assertEquals(full.length, Files.size(logPath()));
    }

    // ── Case D: structural damage that a truncated append cannot produce ──

    @Test
    void negativeLengthIsCorruptionNotATornTail() throws IOException {
        byte[] full = record(OP, utf8("first"));
        ByteBuffer bad = ByteBuffer.allocate(5);
        bad.put(OP).putInt(-7);
        writeRaw(full, bad.array());

        WriteAheadLog wal = new WriteAheadLog(logPath());
        CorruptWalException e = assertThrows(CorruptWalException.class, wal::readAll);
        assertTrue(e.getMessage().contains("Negative record length"), e.getMessage());
    }

    @Test
    void lengthBeyondTheMaximumRecordSizeIsCorruptionNotATornTail() throws IOException {
        // A length exceeding the file size is NOT corruption -- see
        // completeHeaderWithTruncatedPayloadIsATornTail. What marks a header as
        // damaged is a length beyond any record the system could have written,
        // which also stops a garbage length driving a huge allocation.
        byte[] full = record(OP, utf8("first"));
        ByteBuffer bad = ByteBuffer.allocate(5 + 4);
        bad.put(OP).putInt(Integer.MAX_VALUE).put(new byte[4]);
        writeRaw(full, bad.array());

        WriteAheadLog wal = new WriteAheadLog(logPath(), 4096);
        CorruptWalException e = assertThrows(CorruptWalException.class, wal::readAll);
        assertTrue(e.getMessage().contains("maximum record size"), e.getMessage());
    }

    @Test
    void aTornTailIsRecoverableEvenWhenItsDeclaredLengthExceedsTheFile() throws IOException {
        // Appending a large payload to a short log and crashing leaves exactly
        // this shape. Misreading it as corruption would refuse to start a node
        // whose log is perfectly recoverable.
        byte[] full = record(OP, utf8("kept"));
        ByteBuffer partial = ByteBuffer.allocate(5 + 3);
        partial.put(OP).putInt(50_000).put(new byte[3]);
        writeRaw(full, partial.array());

        WriteAheadLog wal = new WriteAheadLog(logPath());
        List<WalEntry> entries = wal.readAll();

        assertEquals(1, entries.size(), "the complete prefix must survive");
        assertArrayEquals(utf8("kept"), entries.get(0).payload());
        assertEquals(full.length, Files.size(logPath()));
    }

    @Test
    void corruptionIsNotSilentlyTrimmedToTheLastGoodRecord() throws IOException {
        // Records after the damage must not be silently discarded and the result
        // presented as a clean recovery.
        byte[] first = record(OP, utf8("first"));
        ByteBuffer bad = ByteBuffer.allocate(5);
        bad.put(OP).putInt(-1);
        byte[] third = record(OP, utf8("third"));
        writeRaw(first, bad.array(), third);

        WriteAheadLog wal = new WriteAheadLog(logPath());
        assertThrows(CorruptWalException.class, wal::readAll);

        // The file is left untouched for inspection rather than repaired.
        assertEquals(first.length + 5 + third.length, Files.size(logPath()),
                "a corrupt log must not be rewritten behind the operator's back");
    }

    // ── Baseline ──

    @Test
    void aCleanLogReplaysEveryRecordAndIsNotRewritten() throws IOException {
        WriteAheadLog wal = new WriteAheadLog(logPath());
        wal.append(OP, utf8("a"));
        wal.append(OP, utf8("bb"));
        wal.append(OP, utf8("ccc"));
        long sizeBefore = Files.size(logPath());

        List<WalEntry> entries = wal.readAll();

        assertEquals(3, entries.size());
        assertArrayEquals(utf8("ccc"), entries.get(2).payload());
        assertEquals(sizeBefore, Files.size(logPath()), "a clean log must not be truncated");
    }

    @Test
    void emptyAndMissingLogsReadAsEmpty() throws IOException {
        WriteAheadLog missing = new WriteAheadLog(tempDir.resolve("absent.bin"));
        assertTrue(missing.readAll().isEmpty());

        Files.createFile(logPath());
        assertTrue(new WriteAheadLog(logPath()).readAll().isEmpty());
    }

    @Test
    void zeroLengthPayloadsAreValidRecords() throws IOException {
        // Raft's leadership no-op is an empty payload, so this must round-trip.
        WriteAheadLog wal = new WriteAheadLog(logPath());
        wal.append(OP, new byte[0]);
        wal.append(OP, utf8("after"));

        List<WalEntry> entries = wal.readAll();
        assertEquals(2, entries.size());
        assertEquals(0, entries.get(0).payload().length);
        assertArrayEquals(utf8("after"), entries.get(1).payload());
    }
}
