package io.ghostlink.core;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.LockSupport;

/**
 * An adaptive wait strategy that handles extreme thread contention gracefully
 * without bringing the CPU to its knees via L1 cache invalidation ping-pongs.
 */
public class AdaptiveBusyWaitStrategy implements WaitStrategy {

    private static final int SPIN_TRIES = 1_000_000;
    private static final int YIELD_TRIES = 5_000_000;

    @Override
    public int spin(int counter) {
        if (counter < SPIN_TRIES) {
            // Apply jittery exponential backoff on micro-spinning to break hardware CAS
            // convoys
            // taking average latencies < 500ns in high contention CPU MPMC
            int spins = Math.min(256, 1 << (counter / 100));
            spins += ThreadLocalRandom.current().nextInt(Math.max(1, spins / 2));
            for (int i = 0; i < spins; i++) {
                Thread.onSpinWait();
            }
        } else if (counter < YIELD_TRIES) {
            Thread.yield();
        } else {
            LockSupport.parkNanos(1);
        }
        return counter + 1;
    }
}
