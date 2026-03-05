package io.ghostlink.cuda;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * CUDA VRAM-Backed 10x10 SDM Network Fabric Consumer.
 */
public class GhostCudaConsumer {

    private final int consumerId;
    private final MemorySegment vram;
    private final long[] localSeq = new long[10];
    private final MemorySegment stagingControlBlock;
    private final MemorySegment stagingData;

    public GhostCudaConsumer(int consumerId, GhostCudaFabric fabric) {
        this.consumerId = consumerId;
        this.vram = fabric.getSegment();
        this.stagingControlBlock = Arena.global().allocate(GhostCudaFabric.CONTROL_BLOCK_SIZE);
        this.stagingData = Arena.global().allocate(Long.BYTES);
    }

    /**
     * Complete SDM network control block evaluation in 1 PCIe fetch round trip.
     */
    public long poll() {
        // 1. Copy the entire 800-byte Control Block to host memory
        GhostCudaFabric.memcpyDtoH(stagingControlBlock, vram.address(), GhostCudaFabric.CONTROL_BLOCK_SIZE);

        // Loop over all 10 independent lane flags
        for (int pId = 0; pId < 10; pId++) {
            int index = pId * 10 + consumerId;
            long controlOffset = index * Long.BYTES;

            // 2. Compare against locally cached sequence flags in native off-heap memory
            long seq = stagingControlBlock.get(ValueLayout.JAVA_LONG, controlOffset);
            if (seq > localSeq[pId]) {

                // 3. If a flag is incremented, fetch the specific payload from the VRAM Data
                // Block
                long dataOffset = GhostCudaFabric.CONTROL_BLOCK_SIZE + (index * Long.BYTES);
                GhostCudaFabric.memcpyDtoH(stagingData, vram.address() + dataOffset, Long.BYTES);
                long payload = stagingData.get(ValueLayout.JAVA_LONG, 0);

                localSeq[pId] = seq;
                return payload;
            }
        }

        return 0; // Return 0 to indicate no message
    }
}
