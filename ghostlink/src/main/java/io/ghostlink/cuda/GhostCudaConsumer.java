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
    private final MemorySegment stagingAll;

    public GhostCudaConsumer(int consumerId, GhostCudaFabric fabric) {
        this.consumerId = consumerId;
        this.vram = fabric.getSegment();
        this.stagingAll = Arena.global().allocate(GhostCudaFabric.TOTAL_SIZE);
    }

    /**
     * Complete SDM network control block evaluation in 1 PCIe fetch round trip.
     */
    public long poll() {
        // 1. Copy the entire 1.6KB VRAM topology (Control AND Data) to host memory
        GhostCudaFabric.memcpyDtoH(stagingAll, vram.address(), GhostCudaFabric.TOTAL_SIZE);

        // Loop over all 10 independent lane flags
        for (int pId = 0; pId < 10; pId++) {
            int index = pId * 10 + consumerId;
            long controlOffset = index * Long.BYTES;

            // 2. Compare against locally cached sequence flags in native off-heap memory
            long seq = stagingAll.get(ValueLayout.JAVA_LONG, controlOffset);
            if (seq > localSeq[pId]) {

                // 3. Extract payload directly from identical sequential DMA fetch
                long dataOffset = GhostCudaFabric.CONTROL_BLOCK_SIZE + (index * Long.BYTES);
                long payload = stagingAll.get(ValueLayout.JAVA_LONG, dataOffset);

                localSeq[pId] = seq;
                return payload;
            }
        }

        return 0; // Return 0 to indicate no message
    }
}
