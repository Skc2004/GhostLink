package io.ghostlink.cuda;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * CUDA VRAM-Backed 10x10 SDM Network Fabric Producer.
 */
public class GhostCudaProducer {

    private final int producerId;
    private final MemorySegment vram;
    private final MemorySegment stagingBuffer;
    private final long[] localSeq = new long[10];

    public GhostCudaProducer(int producerId, GhostCudaFabric fabric) {
        this.producerId = producerId;
        this.vram = fabric.getSegment();
        this.stagingBuffer = Arena.global().allocate(Long.BYTES);
    }

    /**
     * Non-Blocking point-to-point sequence write without any atomic CAS bounds
     */
    public void publish(int targetConsumerId, long payload) {
        int index = producerId * 10 + targetConsumerId;

        // 1. Write payload to native off-heap staging buffer, then copy to Data Block
        long dataOffset = GhostCudaFabric.CONTROL_BLOCK_SIZE + (index * Long.BYTES);
        stagingBuffer.set(ValueLayout.JAVA_LONG, 0, payload);
        GhostCudaFabric.memcpyHtoD(vram.address() + dataOffset, stagingBuffer, Long.BYTES);

        // 2. Call cuCtxSynchronize() to flush the PCIe buffer to VRAM
        GhostCudaFabric.synchronize();

        // 3. Increment the sequence flag in Control Block
        long controlOffset = index * Long.BYTES;
        long nextSeq = ++localSeq[targetConsumerId];
        stagingBuffer.set(ValueLayout.JAVA_LONG, 0, nextSeq);
        GhostCudaFabric.memcpyHtoD(vram.address() + controlOffset, stagingBuffer, Long.BYTES);
    }
}
