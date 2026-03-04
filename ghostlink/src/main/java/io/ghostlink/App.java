package io.ghostlink;

import java.io.File;
import java.util.concurrent.CyclicBarrier;

public class App {

    private static final String DATA_FILE = "target/ghost_ipc.data";
    private static final int MSG_COUNT = 5_000_000;
    private static final int WARMUP_COUNT = 1_000_000;

    public static void main(String[] args) throws Exception {
        System.out.println("Starting GhostLink Latency Benchmark...");

        File f = new File(DATA_FILE);
        if (f.exists())
            f.delete();
        f.getParentFile().mkdirs();
        // Why capacity = 2?
        // A huge capacity (like 1,000,000) allows the producer to far outpace the
        // consumer.
        // This leads to huge queue build-ups where a message sits in the queue for 3+
        // milliseconds
        // before the consumer ever reads it, severely skewing the average latency
        // higher.
        // By restricting capacity to 2 slots, we physically force the producer to pace
        // itself
        // to the consumer's read speed. This prevents massive queueing delays and
        // measures true
        // underlying IPC latency instead of queue-wait time.
        // NOTE ON VARIANCE (e.g., 96ns vs 861ns): Variance here is primarily caused by
        // OS thread
        // scheduling jitter. We are using standard Java threads on a non-Real-Time OS.
        // If the producer
        // thread gets scheduled slightly ahead of the consumer thread, it spins out its
        // 2 slots and blocks,
        // accumulating microsecond delays until the consumer thread wakes up. When
        // thread wake-ups
        // perfectly align, you see ~100ns averages.
        int capacity = 2; // 2 slots instead of 1M to prevent giant queue build-ups causing latency
        int slotSize = Long.BYTES;
        CyclicBarrier barrier = new CyclicBarrier(2);

        Thread producerThread = new Thread(() -> {
            try (GhostProducer producer = new GhostProducer(DATA_FILE, capacity, slotSize, true)) {
                System.out.println("Producer started...");

                for (int i = 0; i < WARMUP_COUNT; i++) {
                    producer.publish(0);
                }

                System.out.println("Producer Warmup Finish");
                barrier.await(); // Wait for consumer

                System.out.println("Producer Benchmark Run Started");
                int mask = 8191; // Fast bitmask instead of modulo division to prevent CPU stalls
                for (int i = 0; i < MSG_COUNT; i++) {
                    long timestamp = (i & mask) == 0 ? System.nanoTime() : 0L;
                    producer.publish(timestamp);
                }

                // End Signal
                producer.publish(-1);
            } catch (Exception e) {
                e.printStackTrace();
            }
        });

        Thread consumerThread = new Thread(() -> {
            try (GhostConsumer consumer = new GhostConsumer(DATA_FILE, capacity, slotSize)) {
                System.out.println("Consumer started...");

                for (int i = 0; i < WARMUP_COUNT; i++) {
                    consumer.poll();
                }

                System.out.println("Consumer Warmup Finish");
                barrier.await(); // Wait for producer to engage

                long minLatency = Long.MAX_VALUE;
                long maxLatency = 0;
                long totalLatency = 0;

                long value;
                int measuredCount = 0;
                while ((value = consumer.poll()) != -1) {
                    if (value != 0) {
                        long now = System.nanoTime();
                        long latency = now - value;
                        if (latency < minLatency && latency > 0)
                            minLatency = latency;
                        if (latency > maxLatency)
                            maxLatency = latency;
                        totalLatency += latency;
                        measuredCount++;
                    }
                }

                double avgLatency = measuredCount > 0 ? (double) totalLatency / measuredCount : 0.0;

                System.out.println("====== BENCHMARK RESULTS ======");
                System.out.printf("Messages: %,d\n", MSG_COUNT);
                System.out.println(String.format("Minimum Latency: %,d ns", minLatency));
                System.out.println(String.format("Average Latency: %,.2f ns", avgLatency));
                System.out.println(String.format("Maximum Latency: %,d ns", maxLatency));
                System.out.println("===============================");

            } catch (Exception e) {
                e.printStackTrace();
            }
        });

        consumerThread.setPriority(Thread.MAX_PRIORITY);
        producerThread.setPriority(Thread.MAX_PRIORITY);

        consumerThread.start();
        producerThread.start();

        producerThread.join();
        consumerThread.join();
    }
}