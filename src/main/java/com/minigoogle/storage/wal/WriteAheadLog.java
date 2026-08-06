package com.minigoogle.storage.wal;

import com.minigoogle.storage.serialization.BinaryReader;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Write-ahead log for crash recovery of index operations.
 * Each entry stores an operation type byte and a variable-length payload.
 * Entries are appended with fsync guarantees and can be replayed on recovery.
 */
public class WriteAheadLog {
    
    public record WalEntry(byte operationType, byte[] payload) {}
    
    private final Path logPath;
    
    public WriteAheadLog(Path logPath) {
        this.logPath = logPath;
    }
    
    public void append(byte operationType, byte[] payload) throws IOException {
        try (FileChannel channel = FileChannel.open(logPath, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(1 + 4 + payload.length);
            buf.put(operationType);
            buf.putInt(payload.length);
            buf.put(payload);
            buf.flip();
            while(buf.hasRemaining()) {
                channel.write(buf);
            }
            channel.force(true); // fsync
        }
    }
    
    public List<WalEntry> readAll() throws IOException {
        List<WalEntry> entries = new ArrayList<>();
        if (!Files.exists(logPath) || Files.size(logPath) == 0) {
            return entries;
        }
        
        try (FileChannel channel = FileChannel.open(logPath, StandardOpenOption.READ)) {
            // readAllBytes (not mmap): a lingering mapped view would prevent a
            // subsequent clear()/truncate rewrite on some platforms (Windows).
            ByteBuffer buf = ByteBuffer.allocate(Math.toIntExact(channel.size()));
            while (buf.hasRemaining()) {
                if (channel.read(buf) < 0) {
                    break;
                }
            }
            buf.flip();
            BinaryReader reader = new BinaryReader(buf);

            while (reader.hasRemaining()) {
                byte op = reader.readByte();
                int len = reader.readInt();
                byte[] payload = new byte[len];
                buf.get(payload);
                entries.add(new WalEntry(op, payload));
            }
        }
        return entries;
    }
    
    public void clear() throws IOException {
        Files.deleteIfExists(logPath);
    }
}
