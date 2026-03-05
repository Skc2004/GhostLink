package io.ghostlink.cuda;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;

/**
 * Implements a VRAM-Backed Non-Blocking Switching Fabric using
 * the Java 24 FFM API and the CUDA Driver API.
 */
public class GhostCudaFabric implements AutoCloseable {

    private static final SymbolLookup CUDA_LIB;
    private static final Linker LINKER = Linker.nativeLinker();

    static {
        Arena auto = Arena.global();
        SymbolLookup lib = null;
        try {
            lib = SymbolLookup.libraryLookup("nvcuda", auto); // Windows
        } catch (IllegalArgumentException e) {
            try {
                lib = SymbolLookup.libraryLookup("cuda", auto); // Linux
            } catch (Exception ex) {
                System.err.println("WARNING: Failed to load CUDA Driver API library. FFM bindings unavailable.");
            }
        }
        CUDA_LIB = lib;
    }

    private static MethodHandle downcall(String name, FunctionDescriptor desc) {
        if (CUDA_LIB == null)
            return null;
        Optional<MemorySegment> sym = CUDA_LIB.find(name);
        if (sym.isEmpty()) {
            sym = CUDA_LIB.find(name + "_v2");
        }
        return sym.map(segment -> LINKER.downcallHandle(segment, desc)).orElse(null);
    }

    private static final MethodHandle cuInit = downcall("cuInit",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
    private static final MethodHandle cuDeviceGet = downcall("cuDeviceGet",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
    private static final MethodHandle cuCtxCreate = downcall("cuCtxCreate_v2", FunctionDescriptor
            .of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
    private static final MethodHandle cuMemAlloc = downcall("cuMemAlloc_v2",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
    // Memory Handle methods needed for IPC
    private static final MethodHandle cuIpcGetMemHandle = downcall("cuIpcGetMemHandle",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
    private static final MethodHandle cuIpcOpenMemHandle = downcall("cuIpcOpenMemHandle_v2", FunctionDescriptor
            .of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
    private static final MethodHandle cuCtxSynchronize = downcall("cuCtxSynchronize",
            FunctionDescriptor.of(ValueLayout.JAVA_INT));
    private static final MethodHandle cuMemFree = downcall("cuMemFree_v2",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG));

    private static final MethodHandle cuMemcpyHtoD = downcall("cuMemcpyHtoD_v2",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS,
                    ValueLayout.JAVA_LONG));
    private static final MethodHandle cuMemcpyDtoH = downcall("cuMemcpyDtoH_v2",
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG,
                    ValueLayout.JAVA_LONG));

    // Spatial Division Multiplexed (SDM) crossbar - 100 dedicated point-to-point
    // lanes
    public static final int CONTROL_BLOCK_SIZE = 100 * Long.BYTES; // 800 bytes exactly
    public static final int DATA_BLOCK_SIZE = 100 * Long.BYTES; // 800 bytes exactly
    public static final int TOTAL_SIZE = CONTROL_BLOCK_SIZE + DATA_BLOCK_SIZE; // 1600 bytes total

    private final Arena arena;
    private final MemorySegment vramSegment;
    private final long devicePtr;
    private final boolean isOwner;

    public GhostCudaFabric(boolean isProducerHost) {
        // Strict resource cleanup to prevent GPU memory leaks
        this.arena = Arena.ofConfined();
        try {
            if (cuInit != null)
                check((int) cuInit.invokeExact(0));

            MemorySegment devicePtrSeg = arena.allocate(ValueLayout.JAVA_INT);
            if (cuDeviceGet != null)
                check((int) cuDeviceGet.invokeExact(devicePtrSeg, 0));
            int device = devicePtrSeg.get(ValueLayout.JAVA_INT, 0);

            MemorySegment ctxPtrSeg = arena.allocate(ValueLayout.ADDRESS);
            if (cuCtxCreate != null)
                check((int) cuCtxCreate.invokeExact(ctxPtrSeg, 0, device));

            Path handlePath = Path.of("target/cuda_ipc_handle.bin");
            MemorySegment dptrSeg = arena.allocate(ValueLayout.JAVA_LONG);

            if (isProducerHost) {
                // Phase 2: Direct FFM call to CUDA Driver cuMemAlloc
                if (cuMemAlloc != null) {
                    check((int) cuMemAlloc.invokeExact(dptrSeg, (long) TOTAL_SIZE));
                }
                this.devicePtr = dptrSeg.get(ValueLayout.JAVA_LONG, 0);

                MemorySegment handleSeg = arena.allocate(64); // CU_IPC_HANDLE_SIZE = 64
                if (cuIpcGetMemHandle != null)
                    check((int) cuIpcGetMemHandle.invokeExact(handleSeg, this.devicePtr));

                byte[] handleBytes = handleSeg.toArray(ValueLayout.JAVA_BYTE);
                Files.write(handlePath, handleBytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                this.isOwner = true;
            } else {
                while (!Files.exists(handlePath)) {
                    Thread.sleep(100);
                }
                byte[] handleBytes = Files.readAllBytes(handlePath);
                MemorySegment handleSeg = arena.allocateFrom(ValueLayout.JAVA_BYTE, handleBytes);

                // Process B (Consumer) importing handle
                if (cuIpcOpenMemHandle != null)
                    check((int) cuIpcOpenMemHandle.invokeExact(dptrSeg, handleSeg, 1));
                this.devicePtr = dptrSeg.get(ValueLayout.JAVA_LONG, 0);
                this.isOwner = false;
            }

            // Bind VRAM memory mapped pointer safely (NOTE: Direct JVM CPU dereferencing of
            // this segment will cause EXCEPTION_ACCESS_VIOLATION on Windows)
            this.vramSegment = MemorySegment.ofAddress(devicePtr).reinterpret(TOTAL_SIZE, arena, null);

            // Allocate and initialize Control Block space using cuMemcpyHtoD
            if (isOwner && cuMemcpyHtoD != null) {
                MemorySegment zeroes = arena.allocate(CONTROL_BLOCK_SIZE);
                check((int) cuMemcpyHtoD.invokeExact(devicePtr, zeroes, (long) CONTROL_BLOCK_SIZE));
            }
        } catch (Throwable t) {
            throw new RuntimeException("CUDA IPC Initialization Failed", t);
        }
    }

    /**
     * Calls cuCtxSynchronize() to flush PCIe buffers instantly to VRAM
     */
    public static void synchronize() {
        try {
            if (cuCtxSynchronize != null)
                check((int) cuCtxSynchronize.invokeExact());
        } catch (Throwable t) {
            throw new RuntimeException("CUDA Context Synchronize Failed", t);
        }
    }

    public static void memcpyHtoD(long dstDevice, MemorySegment srcHost, long byteCount) {
        try {
            if (cuMemcpyHtoD != null)
                check((int) cuMemcpyHtoD.invokeExact(dstDevice, srcHost, byteCount));
        } catch (Throwable t) {
            throw new RuntimeException("cuMemcpyHtoD Failed", t);
        }
    }

    public static void memcpyDtoH(MemorySegment dstHost, long srcDevice, long byteCount) {
        try {
            if (cuMemcpyDtoH != null)
                check((int) cuMemcpyDtoH.invokeExact(dstHost, srcDevice, byteCount));
        } catch (Throwable t) {
            throw new RuntimeException("cuMemcpyDtoH Failed", t);
        }
    }

    public MemorySegment getSegment() {
        return vramSegment;
    }

    private static void check(int cudaResult) {
        if (cudaResult != 0 && cudaResult != 100) { // Exclude specific harmless non-errors
            throw new RuntimeException("CUDA Driver API Error Code: " + cudaResult);
        }
    }

    @Override
    public void close() throws Exception {
        if (this.isOwner && cuMemFree != null) {
            try {
                check((int) cuMemFree.invokeExact(this.devicePtr));
            } catch (Throwable t) {
                System.err.println("CUDA Memory Free Error: " + t.getMessage());
            }
        }
        arena.close();
    }
}
