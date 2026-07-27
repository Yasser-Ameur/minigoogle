package com.minigoogle.storage.serialization;

import org.junit.jupiter.api.Test;
import java.io.File;
import java.io.IOException;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import static org.junit.jupiter.api.Assertions.*;

/** Tests for binary serialization/deserialization functionality. */
class BinarySerializationTest {
    @Test
    void testRoundTrip() throws IOException {
        File tempFile = File.createTempFile("bin-test", ".bin");
        tempFile.deleteOnExit();
        Path path = tempFile.toPath();

        try (BinaryWriter writer = new BinaryWriter(path)) {
            writer.writeInt(42);
            writer.writeLong(123456789L);
            writer.writeString("Hello Storage");
            writer.writeByte((byte) 7);
        }

        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            MappedByteBuffer mmap = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size());
            BinaryReader reader = new BinaryReader(mmap);
            
            assertEquals(42, reader.readInt());
            assertEquals(123456789L, reader.readLong());
            assertEquals("Hello Storage", reader.readString());
            assertEquals((byte) 7, reader.readByte());
            assertFalse(reader.hasRemaining());
        }
    }
}
