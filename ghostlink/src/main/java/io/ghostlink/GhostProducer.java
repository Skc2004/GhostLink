package io.ghostlink;

import io.ghostlink.core.GhostRingBuffer;
import io.ghostlink.core.MappedArena;
import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

public class GhostProducer implements AutoCloseable {

    private final MappedArena arena;
    private final GhostRingBuffer ringBuffer;
    private final MemorySegment segment;

    public GhostProducer(String filePath, int capacity, int slotSize, boolean init) throws IOException {
        long size = GhostRingBuffer.calculateRequiredSize(capacity, slotSize);
        this.arena = new MappedArena(filePath, size);
        this.segment = arena.getSegment();
        this.ringBuffer = new GhostRingBuffer(segment, capacity, slotSize);
        if (init) {
            this.ringBuffer.init();
        }
    }

    public void publish(long value) {
        long offset;
        int idleCounter = 0;
        while ((offset = ringBuffer.tryClaim()) < 0) {
            if (idleCounter < 100) {
                Thread.onSpinWait();
            } else if (idleCounter < 1000) {
                Thread.yield();
            } else {
                java.util.concurrent.locks.LockSupport.parkNanos(1);
            }
            idleCounter++;
        }

        segment.set(ValueLayout.JAVA_LONG, offset, value);
        ringBuffer.commit(offset);
    }

    @Override
    public void close() {
        arena.close();
    }
}
