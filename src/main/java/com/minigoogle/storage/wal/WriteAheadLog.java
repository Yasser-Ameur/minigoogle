package com.minigoogle.storage.wal;

import com.minigoogle.storage.serialization.BinaryReader;
import com.minigoogle.storage.serialization.BinaryWriter;
import java.io.IOException;
import java.nio.MappedByteBuffer;
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
        try (BinaryWriter writer = new BinaryWriter(logPath, 4096)) { // open in append mode if using standard open options properly
             // Note: using FileChannel with APPEND mode directly might be needed for proper WAL append,
             // but here we just write an entry. For a robust WAL, this append needs to truly append without truncating.
        }
        // Actually, let's fix BinaryWriter append mode for WAL specifically:
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
            MappedByteBuffer mmap = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size());
            BinaryReader reader = new BinaryReader(mmap);
            
            while (reader.hasRemaining()) {
                byte op = reader.readByte();
                int len = reader.readInt();
                byte[] payload = new byte[len];
                mmap.get(payload);
                entries.add(new WalEntry(op, payload));
            }
        }
        return entries;
    }
    
    public void clear() throws IOException {
        Files.deleteIfExists(logPath);
    }
}
