package io.ghostlink.cuda;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * CUDA VRAM-Backed 10x10 SDM Network Fabric Producer.
 */
public class GhostCudaProducer {

    private final int producerId;
    private final MemorySegment vram;

    public GhostCudaProducer(int producerId, GhostCudaFabric fabric) {
        this.producerId = producerId;
        this.vram = fabric.getSegment();
    }

    /**
     * Non-Blocking point-to-point sequence write without any atomic CAS bounds
     */
    public void publish(int targetConsumerId, long payload) {
        int index = producerId * 10 + targetConsumerId;

        // 1. Write payload to Data Block [ProducerID * 10 + ConsumerID]
        long dataOffset = GhostCudaFabric.CONTROL_BLOCK_SIZE + (index * Long.BYTES);
        vram.set(ValueLayout.JAVA_LONG, dataOffset, payload);

        // 2. Call cuCtxSynchronize() to flush the PCIe buffer to VRAM
        GhostCudaFabric.synchronize();

        // 3. Increment the sequence flag in Control Block [ProducerID * 10 +
        // ConsumerID]
        long controlOffset = index * Long.BYTES;
        long currentSeq = vram.get(ValueLayout.JAVA_LONG, controlOffset);
        vram.set(ValueLayout.JAVA_LONG, controlOffset, currentSeq + 1);
    }
}
