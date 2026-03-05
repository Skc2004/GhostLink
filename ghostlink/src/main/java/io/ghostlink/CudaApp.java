package io.ghostlink;

import io.ghostlink.cuda.GhostCudaFabric;
import io.ghostlink.cuda.GhostCudaProducer;
import io.ghostlink.cuda.GhostCudaConsumer;

/**
 * Benchmark simulation of Phase 1 / 2/ 3 CUDA FFM IPC mechanism.
 */
public class CudaApp {
    private static final int MSG_COUNT = 100_000;
    private static final int WARMUP_COUNT = 10_000;

    public static void main(String[] args) throws Exception {
        boolean isProducerProcess = args.length > 0 && args[0].equalsIgnoreCase("producer");
        boolean isConsumerProcess = args.length > 0 && args[0].equalsIgnoreCase("consumer");

        if (isProducerProcess) {
            System.out.println("Starting GhostCuda Process A (Single Threaded Multiplexed Producers)...");

            // Arena.ofConfined() mapped context specifically on this single thread
            try (GhostCudaFabric fabric = new GhostCudaFabric(true)) {
                GhostCudaProducer[] producers = new GhostCudaProducer[10];
                for (int i = 0; i < 10; i++) {
                    producers[i] = new GhostCudaProducer(i, fabric);
                }

                System.out.println(
                        "Producers mapped SDM crossbar. Exported CUDA IPC handle to target/cuda_ipc_handle.bin");
                System.out.println("Waiting 5 seconds for Consumers to attach...");
                Thread.sleep(5000);

                System.out.println("Starting Benchmark Warmup...");
                for (int i = 0; i < WARMUP_COUNT; i++) {
                    int pId = i % 10;
                    int cId = i % 10;
                    producers[pId].publish(cId, 1L); // warmup payload
                }

                System.out.println("Starting Benchmark Run...");
                for (int i = 0; i < MSG_COUNT; i++) {
                    int pId = i % 10;
                    int cId = i % 10;
                    long timestamp = System.nanoTime();
                    producers[pId].publish(cId, timestamp);
                    // Add slight backpressure (10us) so Consumer has a chance to sample a decent
                    // number of ticks
                    long end = System.nanoTime() + 10_000;
                    while (System.nanoTime() < end) {
                    }
                }

                // Send EXIT signal
                for (int cId = 0; cId < 10; cId++) {
                    producers[cId].publish(cId, -1L);
                }

                System.out.println("VRAM Network sequence published heavily via cuCtxSynchronize()");
                Thread.sleep(5000); // delay keeping Arena.ofConfined open for Consumer process
            }
        } else if (isConsumerProcess) {
            System.out.println("Starting GhostCuda Process B (Single Threaded Multiplexed Consumers)...");

            try (GhostCudaFabric fabric = new GhostCudaFabric(false)) {
                GhostCudaConsumer[] consumers = new GhostCudaConsumer[10];
                for (int i = 0; i < 10; i++) {
                    consumers[i] = new GhostCudaConsumer(i, fabric);
                }

                System.out.println("Consumer IPC mapping complete. Non-blocking poll online.");

                System.out.println("Warming up...");
                int activeLanes = 10;
                while (activeLanes > 0) {
                    for (int cId = 0; cId < 10; cId++) {
                        long val = consumers[cId].poll();
                        if (val == 1L) {
                            activeLanes--;
                        }
                    }
                }

                System.out.println("Running Benchmark...");
                long minLatency = Long.MAX_VALUE;
                long maxLatency = 0;
                long totalLatency = 0;
                int msgsReceived = 0;

                boolean running = true;
                while (running) {
                    for (int cId = 0; cId < 10; cId++) {
                        long val = consumers[cId].poll();
                        if (val == -1L) {
                            running = false;
                        } else if (val > 1L) { // Actual timestamp
                            long receiveTime = System.nanoTime();
                            long latency = receiveTime - val;
                            if (latency < minLatency)
                                minLatency = latency;
                            if (latency > maxLatency)
                                maxLatency = latency;
                            totalLatency += latency;
                            msgsReceived++;
                        }
                    }
                }

                System.out.println("====== VRAM CROSSBAR RESULTS ======");
                System.out
                        .println("Ticks Processed: " + msgsReceived + " (Others skipped cleanly in Latest-Value SDM)");
                System.out.println("Lanes:           10 Producers x 10 Consumers (100 Active SDM Lanes)");
                if (msgsReceived > 0) {
                    System.out.println("Min Lat.:        " + minLatency + " ns");
                    System.out.println("Avg Lat.:        " + (totalLatency / msgsReceived) + " ns");
                    System.out.println("Max Lat.:        " + maxLatency + " ns");
                }
                System.out.println("===================================");
            }
        } else {
            System.out.println("Usage:");
            System.out.println(
                    "  java --enable-native-access=ALL-UNNAMED -cp target/classes io.ghostlink.CudaApp producer");
            System.out.println(
                    "  java --enable-native-access=ALL-UNNAMED -cp target/classes io.ghostlink.CudaApp consumer");
        }
    }
}
