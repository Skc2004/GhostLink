package io.ghostlink.core;

/**
 * Strategy interface for handling contention when a thread needs to wait
 * for a sequence or state change in the ring buffer.
 */
public interface WaitStrategy {

    /**
     * Called when a thread experiences contention and needs to wait.
     * 
     * @param counter The number of consecutive times wait has been called
     *                without success. Useful for exponential backoff logic.
     * @return An updated counter value (usually counter + 1).
     */
    int spin(int counter);
}
