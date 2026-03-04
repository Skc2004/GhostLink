package io.ghostlink;

import io.ghostlink.cuda.GhostCudaFabric;
import io.ghostlink.cuda.GhostCudaProducer;
import io.ghostlink.cuda.GhostCudaConsumer;

/**
 * Benchmark simulation of Phase 1 / 2/ 3 CUDA FFM IPC mechanism.
 */
public class CudaApp {
    public static void main(String[] args) throws Exception {
        boolean isProducerProcess = args.length > 0 && args[0].equalsIgnoreCase("producer");
        boolean isConsumerProcess = args.length > 0 && args[0].equalsIgnoreCase("consumer");

        if (isProducerProcess) {
            System.out.println("Starting GhostCuda Process A (Producers)...");

            // Arena.ofConfined() mapped context specifically on this single thread
            try (GhostCudaFabric fabric = new GhostCudaFabric(true)) {
                GhostCudaProducer[] producers = new GhostCudaProducer[10];
                for (int i = 0; i < 10; i++) {
                    producers[i] = new GhostCudaProducer(i, fabric);
                }

                System.out.println(
                        "Producers mapped SDM crossbar. Exported CUDA IPC handle to target/cuda_ipc_handle.bin");

                // Blast simulated crossbar metrics for multiple consumers over dedicated PCIe
                // lanes
                for (int cId = 0; cId < 10; cId++) {
                    producers[0].publish(cId, 1000L + cId);
                    producers[3].publish(cId, 3000L + cId);
                }

                System.out.println("VRAM Network sequence published heavily via cuCtxSynchronize()");
                Thread.sleep(60000); // 1 minute delay keeping Arena.ofConfined open for Consumer process
            }
        } else if (isConsumerProcess) {
            System.out.println("Starting GhostCuda Process B (Consumers)...");

            try (GhostCudaFabric fabric = new GhostCudaFabric(false)) {
                GhostCudaConsumer c0 = new GhostCudaConsumer(0, fabric);
                GhostCudaConsumer c1 = new GhostCudaConsumer(1, fabric);

                System.out.println("Consumer IPC mapping complete. Non-blocking poll online.");

                for (int i = 0; i < 4; i++) {
                    long val0 = c0.poll();
                    System.out.println("[Process B] Consumer 0 received PCIe SDM payload: " + val0);

                    long val1 = c1.poll();
                    System.out.println("[Process B] Consumer 1 received PCIe SDM payload: " + val1);
                }
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
