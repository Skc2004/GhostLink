package io.ghostlink.core;  // <--- This line is critical!

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;

public class MappedArena {
    private final MappedByteBuffer buffer;

    public MappedArena(String filePath, long size) throws Exception {
        File file = new File(filePath);
        try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
            raf.setLength(size);
            this.buffer = raf.getChannel().map(FileChannel.MapMode.READ_WRITE, 0, size);
        }
    }

    public MappedByteBuffer getBuffer() {
        return buffer;
    }
}