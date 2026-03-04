package io.ghostlink.core;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/**
 * Highly optimized, MPMC (Multi-Producer Multi-Consumer) lock-free ring buffer
 * Designed for sub-microsecond/nanosecond IPC between two JVMs via off-heap
 * shared memory.
 */
public class GhostRingBuffer {

    // Slot states for MPMC
    public static final long STATE_EMPTY = 0L;
    public static final long STATE_WRITING = 1L;
    public static final long STATE_READABLE = 2L;
    public static final long STATE_READING = 3L;

    public static final long HEADER_SIZE = Long.BYTES;

    // Cache line padding to prevent false sharing. 128 bytes handles typical
    // x86/ARM prefetchers.
    private static final long HEAD_OFFSET = 128;
    private static final long TAIL_OFFSET = 256;
    private static final long DATA_OFFSET = 384;

    private static final VarHandle RAW_LONG_VH = ValueLayout.JAVA_LONG.varHandle();

    // Slot stride size including header
    private final int slotStride;

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

    private long q09, q10, q11, q12, q13, q14, q15, q16;

    private final WaitStrategy waitStrategy;

    public GhostRingBuffer(MemorySegment segment, int capacity, int slotSize) {
        this(segment, capacity, slotSize, new AdaptiveBusyWaitStrategy());
    }

    public GhostRingBuffer(MemorySegment segment, int capacity, int slotSize, WaitStrategy waitStrategy) {
        if (Integer.bitCount(capacity) != 1) {
            throw new IllegalArgumentException("Capacity must be a power of 2");
        }
        this.segment = segment;
        this.capacity = capacity;
        this.capacityMask = capacity - 1;
        this.slotSize = slotSize;
        this.slotStride = slotSize + (int) HEADER_SIZE;
        this.waitStrategy = waitStrategy != null ? waitStrategy : new AdaptiveBusyWaitStrategy();
    }

    public static long calculateRequiredSize(int capacity, int slotSize) {
        return DATA_OFFSET + ((long) capacity * (slotSize + HEADER_SIZE));
    }

    // --- PRODUCER METHODS ---

    /**
     * Attempts to claim a slot for writing using CAS.
     * 
     * @return offset to the payload data of the claimed slot, or -1 if full.
     */
    public long tryClaim() {
        int casCounter = 0;
        while (true) {
            long currentTail = (long) RAW_LONG_VH.getVolatile(segment, TAIL_OFFSET);
            // We can cache the head locally to avoid hitting shared memory if we aren't
            // full
            long wrapPoint = currentTail - capacity;
            if (headCache <= wrapPoint) {
                headCache = (long) RAW_LONG_VH.getVolatile(segment, HEAD_OFFSET);
                if (headCache <= wrapPoint) {
                    return -1; // Buffer genuinely full
                }
            }

            // MPMC: Attempt to CAS the tail to claim this EXACT sequence number globally
            if (RAW_LONG_VH.compareAndSet(segment, TAIL_OFFSET, currentTail, currentTail + 1)) {
                // We successfully claimed 'currentTail' sequence index!

                long slotIndex = currentTail & capacityMask;
                long slotOffset = DATA_OFFSET + (slotIndex * slotStride);

                // Wait until consumer physically releases the slot layout to STATE_EMPTY.
                int stateCounter = 0;
                while ((long) RAW_LONG_VH.getVolatile(segment, slotOffset) != STATE_EMPTY) {
                    stateCounter = waitStrategy.spin(stateCounter);
                }

                // Mark writing so consumers on THIS sequence lap know it's not ready
                RAW_LONG_VH.setVolatile(segment, slotOffset, STATE_WRITING);
                return slotOffset + HEADER_SIZE;
            }
            // CAS failed, backoff and retry
            casCounter = waitStrategy.spin(casCounter);
        }
    }

    /**
     * Commits the slot, making it available for the consumer.
     */
    public void commit(long payloadOffset) {
        long headerOffset = payloadOffset - HEADER_SIZE;
        // Release semantics ensure the payload data is visible before state changes
        RAW_LONG_VH.setRelease(segment, headerOffset, STATE_READABLE);
    }

    // --- CONSUMER METHODS ---

    /**
     * Attempts to read a slot using CAS.
     * 
     * @return offset to the payload data, or -1 if empty.
     */
    public long tryPoll() {
        int casCounter = 0;
        while (true) {
            long currentHead = (long) RAW_LONG_VH.getVolatile(segment, HEAD_OFFSET);

            if (currentHead >= tailCache) {
                tailCache = (long) RAW_LONG_VH.getVolatile(segment, TAIL_OFFSET);
                if (currentHead >= tailCache) {
                    return -1; // Buffer genuinely empty
                }
            }

            // Attempt to claim this EXACT head sequence globally
            if (RAW_LONG_VH.compareAndSet(segment, HEAD_OFFSET, currentHead, currentHead + 1)) {
                // We own this sequence index!
                long slotIndex = currentHead & capacityMask;
                long slotOffset = DATA_OFFSET + (slotIndex * slotStride);

                // A producer claimed this sequence, but have they finished writing the payload?
                // Wait until they physically commit the payload and mark it STATE_READABLE.
                int stateCounter = 0;
                while ((long) RAW_LONG_VH.getVolatile(segment, slotOffset) != STATE_READABLE) {
                    stateCounter = waitStrategy.spin(stateCounter);
                }

                RAW_LONG_VH.setVolatile(segment, slotOffset, STATE_READING);
                return slotOffset + HEADER_SIZE;
            }
            // CAS failed, another consumer took 'currentHead', backoff and retry
            casCounter = waitStrategy.spin(casCounter);
        }
    }

    /**
     * Consumes the slot, freeing it for producers.
     */
    public void consume(long payloadOffset) {
        long headerOffset = payloadOffset - HEADER_SIZE;
        RAW_LONG_VH.setRelease(segment, headerOffset, STATE_EMPTY);
    }

    // Initialize only if you are the creator of the shared memory
    public void init() {
        RAW_LONG_VH.setRelease(segment, HEAD_OFFSET, 0L);
        RAW_LONG_VH.setRelease(segment, TAIL_OFFSET, 0L);
    }
}
