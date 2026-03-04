package io.ghostlink.core;

import java.util.concurrent.locks.LockSupport;

/**
 * An adaptive wait strategy that handles extreme thread contention gracefully
 * without bringing the CPU to its knees via L1 cache invalidation ping-pongs.
 *
 * It utilizes a phased backoff approach:
 * 1. Micro-Spin (Phase 1): Extremely tight loop using JVM pause instruction.
 * Optimal for ultra-low latency cases where the lock is freed in nanoseconds.
 * 2. Yielding (Phase 2): Hints to the OS to de-schedule this thread temporarily
 * to let the "winning" thread finish processing its volatile write.
 * 3. Parking (Phase 3): Fully release the core for 1 microsecond to prevent
 * hardware thermal throttling and cache thrashing.
 */
public class AdaptiveBusyWaitStrategy implements WaitStrategy {

    private static final int SPIN_TRIES = 200;
    private static final int YIELD_TRIES = 1000;

    @Override
    public int spin(int counter) {
        if (counter < SPIN_TRIES) {
            // Phase 1: Micro-spin (JVM PAUSE instruction)
            Thread.onSpinWait();
        } else if (counter < YIELD_TRIES) {
            // Phase 2: Give up our CPU time slice to let the thread holding the CAS finish
            Thread.yield();
        } else {
            // Phase 3: Hardware is catastrophically stalled. Park the JVM thread.
            LockSupport.parkNanos(1);
        }
        return counter + 1;
    }
}
