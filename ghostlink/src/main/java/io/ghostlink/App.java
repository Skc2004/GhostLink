package io.ghostlink;

import java.io.File;

public class App {

    private static final String DATA_FILE = "target/ghost_ipc.data";
    private static final int MSG_COUNT = 5_000_000;
    private static final int WARMUP_COUNT = 1_000_000;

    public static void main(String[] args) throws Exception {
        System.out.println("Starting GhostLink Latency Benchmark...");

        // Clean old data
        File f = new File(DATA_FILE);
        if (f.exists())
            f.delete();
        f.getParentFile().mkdirs();

        int capacity = 1048576; // 1M power of two slots
        int slotSize = Long.BYTES;

        Thread producerThread = new Thread(() -> {
            try (GhostProducer producer = new GhostProducer(DATA_FILE, capacity, slotSize, true)) {
                System.out.println("Producer started...");

                for (int i = 0; i < WARMUP_COUNT; i++) {
                    producer.publish(0);
                }

                System.out.println("Producer Warmup Finish");
                Thread.sleep(100);

                System.out.println("Producer Benchmark Run Started");
                for (int i = 0; i < MSG_COUNT; i++) {
                    long timestamp = System.nanoTime();
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

                long minLatency = Long.MAX_VALUE;
                long maxLatency = 0;
                long totalLatency = 0;

                long value;
                while ((value = consumer.poll()) != -1) {
                    long now = System.nanoTime();
                    long latency = now - value;
                    if (latency < minLatency && latency > 0)
                        minLatency = latency;
                    if (latency > maxLatency)
                        maxLatency = latency;
                    totalLatency += latency;
                }

                double avgLatency = (double) totalLatency / MSG_COUNT;

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
        Thread.sleep(500); // Allow consumer init
        producerThread.start();

        producerThread.join();
        consumerThread.join();
    }
}