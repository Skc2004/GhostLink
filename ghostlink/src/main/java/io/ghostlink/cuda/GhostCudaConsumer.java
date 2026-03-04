package io.ghostlink.cuda;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * CUDA VRAM-Backed 10x10 SDM Network Fabric Consumer.
 */
public class GhostCudaConsumer {

    private final int consumerId;
    private final MemorySegment vram;
    private final long[] localSeq = new long[10];

    public GhostCudaConsumer(int consumerId, GhostCudaFabric fabric) {
        this.consumerId = consumerId;
        this.vram = fabric.getSegment();
    }

    /**
     * Complete SDM network control block evaluation in 1 PCIe fetch round trip.
     */
    public long poll() {
        while (true) {
            // 1. Read the entire 800-byte Control Block using MemorySegment.asSlice()
            MemorySegment controlBlock = vram.asSlice(0, GhostCudaFabric.CONTROL_BLOCK_SIZE);

            // Loop over all 10 independent lane flags
            for (int pId = 0; pId < 10; pId++) {
                int index = pId * 10 + consumerId;
                long controlOffset = index * Long.BYTES;

                // 2. Compare against locally cached sequence flags
                long seq = controlBlock.get(ValueLayout.JAVA_LONG, controlOffset);
                if (seq > localSeq[pId]) {

                    // 3. If a flag is incremented, fetch the specific payload from the VRAM Data
                    // Block
                    long dataOffset = GhostCudaFabric.CONTROL_BLOCK_SIZE + (index * Long.BYTES);
                    long payload = vram.get(ValueLayout.JAVA_LONG, dataOffset);

                    localSeq[pId] = seq;
                    return payload;
                }
            }

            // Non-blocking spin
            Thread.onSpinWait();
        }
    }
}
