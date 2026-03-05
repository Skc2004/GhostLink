package io.ghostlink;

import java.io.File;
import java.util.concurrent.CyclicBarrier;

public class App {

    private static final String DATA_FILE = "target/ghost_ipc.data";
    private static final int MSG_COUNT_PER_PRODUCER = 10_000;
    private static final int WARMUP_COUNT = 1_000;

    // MPMC Configuration:
    private static final int PRODUCER_COUNT = 10;
    private static final int CONSUMER_COUNT = 10;

    public static void main(String[] args) throws Exception {
        System.out.println("Starting GhostLink Latency Benchmark...");

        File f = new File(DATA_FILE);
        if (f.exists())
            f.delete();
        f.getParentFile().mkdirs();

        int capacity = 65536; // Increased capacity to give 10 producers room to breathe
        int slotSize = Long.BYTES;

        java.util.concurrent.CountDownLatch consumerReady = new java.util.concurrent.CountDownLatch(CONSUMER_COUNT);

        Thread[] producers = new Thread[PRODUCER_COUNT];
        for (int pId = 0; pId < PRODUCER_COUNT; pId++) {
            final int id = pId;
            producers[pId] = new Thread(() -> {
                boolean init = (id == 0);
                try {
                    // Wait for consumer to map file and optionally initialize based on id
                    if (!init)
                        consumerReady.await();

                    try (GhostProducer producer = new GhostProducer(DATA_FILE, capacity, slotSize, init)) {
                        System.out.println("Producer " + id + " started...");

                        if (init)
                            consumerReady.await();

                        for (int i = 0; i < WARMUP_COUNT; i++) {
                            producer.publish(0);
                        }

                        if (id == 0)
                            System.out.println("MPMC Benchmark Run Started");

                        int mask = 8191;
                        for (int i = 0; i < MSG_COUNT_PER_PRODUCER; i++) {
                            long timestamp = (i & mask) == 0 ? System.nanoTime() : 0L;
                            producer.publish(timestamp);
                        }

                        if (id == 0) {
                            for (int c = 0; c < CONSUMER_COUNT; c++) {
                                producer.publish(-1);
                            }
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
            producers[pId].setPriority(Thread.MAX_PRIORITY);
        }

        Thread[] consumers = new Thread[CONSUMER_COUNT];
        for (int cId = 0; cId < CONSUMER_COUNT; cId++) {
            final int id = cId;
            consumers[cId] = new Thread(() -> {
                try {
                    // Small sleep so Producer 0 touches the file first to create it
                    Thread.sleep(100);

                    try (GhostConsumer consumer = new GhostConsumer(DATA_FILE, capacity, slotSize)) {
                        System.out.println("Consumer " + id + " started...");
                        consumerReady.countDown(); // Signal we are bound

                        for (int i = 0; i < WARMUP_COUNT; i++) {
                            long val = consumer.poll();
                            if (val == -1)
                                break;
                        }

                        long minLatency = Long.MAX_VALUE;
                        long maxLatency = 0;
                        long totalLatency = 0;
                        int measuredCount = 0;

                        while (true) {
                            long value = consumer.poll();
                            if (value == -1) {
                                break;
                            }

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

                        System.out.println("--- Consumer " + id + " Results ---");
                        System.out.println(String.format("Average Latency: %,.2f ns", avgLatency));
                        System.out.println(String.format("Minimum Latency: %,d ns", minLatency));

                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
            consumers[cId].setPriority(Thread.MAX_PRIORITY);
        }

        for (Thread c : consumers)
            c.start();
        // Slight delay to ensure consumers map the file after producer 0 creates it
        Thread.sleep(500);
        for (Thread p : producers)
            p.start();

        for (Thread p : producers)
            p.join();

        // After producers are done and have sent end signals, we wait for consumers
        for (Thread c : consumers)
            c.join();
    }
}