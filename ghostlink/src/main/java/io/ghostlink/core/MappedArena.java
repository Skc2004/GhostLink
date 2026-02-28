package io.ghostlink.core; // <--- This line is critical!

import java.io.File;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;

public class MappedArena {
    private final MemorySegment segment;
    private final Arena arena;

    public MappedArena(String filePath, long size) throws IOException {
        File file = new File(filePath);
        if (!file.exists()) {
            file.createNewFile();
        }

        this.arena = Arena.ofShared();
        try (FileChannel channel = FileChannel.open(file.toPath(),
                StandardOpenOption.READ,
                StandardOpenOption.WRITE)) {

            // Map the entire file into shared memory segment
            this.segment = channel.map(FileChannel.MapMode.READ_WRITE, 0, size, arena);
        }
    }

    public MemorySegment getSegment() {
        return segment;
    }

    public void close() {
        if (arena.scope().isAlive()) {
            arena.close();
        }
    }
}