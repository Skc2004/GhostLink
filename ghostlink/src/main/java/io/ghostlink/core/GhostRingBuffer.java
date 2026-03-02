package io.ghostlink.core;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/**
 * Highly optimized, SPSC (Single Producer Single Consumer) lock-free ring
 * buffer.
 * Designed for sub-microsecond/nanosecond IPC between two JVMs via off-heap
 * shared memory.
 */
public class GhostRingBuffer {

    // Cache line padding to prevent false sharing. 128 bytes handles typical
    // x86/ARM prefetchers.
    private static final long HEAD_OFFSET = 128;
    private static final long TAIL_OFFSET = 256;
    private static final long DATA_OFFSET = 384;

    private static final VarHandle RAW_LONG_VH = ValueLayout.JAVA_LONG.varHandle();

    private final MemorySegment segment;
    private final int capacity;
    private final int capacityMask;
    private final int slotSize;

    // Local cached sequences to avoid touching shared memory unnecessarily (reduces
    // L3 cache ping-pong)
    private long headCache = 0;

    // Cache-line padding to defeat false-sharing on the JVM instance itself
    private long p01, p02, p03, p04, p05, p06, p07, p08;
    private long p09, p10, p11, p12, p13, p14, p15, p16;

    private long tailCache = 0;

    private long q01, q02, q03, q04, q05, q06, q07, q08;
    private long q09, q10, q11, q12, q13, q14, q15, q16;

    public GhostRingBuffer(MemorySegment segment, int capacity, int slotSize) {
        if (Integer.bitCount(capacity) != 1) {
            throw new IllegalArgumentException("Capacity must be a power of 2");
        }
        this.segment = segment;
        this.capacity = capacity;
        this.capacityMask = capacity - 1;
        this.slotSize = slotSize;
    }

    public static long calculateRequiredSize(int capacity, int slotSize) {
        return DATA_OFFSET + ((long) capacity * slotSize);
    }

    // --- PRODUCER METHODS ---

    /**
     * Attempts to claim a slot for writing.
     * 
     * @return offset to the slot, or -1 if the buffer is full.
     */
    public long tryClaim() {
        long currentTail = tailCache;
        long wrapPoint = currentTail - capacity;

        // If we hit the cache capacity limit, we must re-read head from memory
        if (headCache <= wrapPoint) {
            headCache = (long) RAW_LONG_VH.getAcquire(segment, HEAD_OFFSET);
            if (headCache <= wrapPoint) {
                return -1; // Still full
            }
        }

        // Return the physical memory layout offset for this slot
        long slotIndex = currentTail & capacityMask;
        return DATA_OFFSET + (slotIndex * slotSize);
    }

    /**
     * Commits the slot, making it available for the consumer.
     */
    public void commit(long offset) {
        // We write the new tail with release semantics to ensure all data writes
        // leading up to this point are visible to the consumer.
        RAW_LONG_VH.setRelease(segment, TAIL_OFFSET, tailCache + 1);
        tailCache++;
    }

    // --- CONSUMER METHODS ---

    /**
     * Attempts to read a slot.
     * 
     * @return offset to the slot, or -1 if the buffer is empty.
     */
    public long tryPoll() {
        long currentHead = headCache;

        if (currentHead >= tailCache) {
            tailCache = (long) RAW_LONG_VH.getAcquire(segment, TAIL_OFFSET);
            if (currentHead >= tailCache) {
                return -1; // Still empty
            }
        }

        long slotIndex = currentHead & capacityMask;
        return DATA_OFFSET + (slotIndex * slotSize);
    }

    /**
     * Consumes the slot, freeing it for the producer.
     */
    public void consume(long offset) {
        RAW_LONG_VH.setRelease(segment, HEAD_OFFSET, headCache + 1);
        headCache++;
    }

    // Initialize only if you are the creator of the shared memory
    public void init() {
        RAW_LONG_VH.setRelease(segment, HEAD_OFFSET, 0L);
        RAW_LONG_VH.setRelease(segment, TAIL_OFFSET, 0L);
    }
}
