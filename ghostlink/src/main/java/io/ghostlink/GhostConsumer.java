package io.ghostlink;

import io.ghostlink.core.GhostRingBuffer;
import io.ghostlink.core.MappedArena;
import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

public class GhostConsumer implements AutoCloseable {

    private final MappedArena arena;
    private final GhostRingBuffer ringBuffer;
    private final MemorySegment segment;

    public GhostConsumer(String filePath, int capacity, int slotSize) throws IOException {
        long size = GhostRingBuffer.calculateRequiredSize(capacity, slotSize);
        this.arena = new MappedArena(filePath, size);
        this.segment = arena.getSegment();
        this.ringBuffer = new GhostRingBuffer(segment, capacity, slotSize);
    }

    /**
     * Polls a single long value or blocks/spins until available.
     * 
     * @return the consumed value
     */
    public long poll() {
        long offset;
        while ((offset = ringBuffer.tryPoll()) < 0) {
            Thread.onSpinWait(); // Busy-spin for lowest latency
        }

        long value = segment.get(ValueLayout.JAVA_LONG, offset);
        ringBuffer.consume(offset);
        return value;
    }

    @Override
    public void close() {
        arena.close();
    }
}
