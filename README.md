# GhostLink
GhostLink is a high-performance messaging library designed for Java applications that require sub-microsecond latency. By leveraging **Memory-Mapped Files (mmap)** and **Off-Heap memory**, it allows two separate JVM processes to communicate via shared memory, bypassing the networking stack and the Java Garbage Collector.

###Technical Objectives (The "Why")

- **Zero-Copy:** Data is written once into shared memory and read directly by the consumer. No intermediate buffers or serialization.

- **GC-Free:** No objects are allocated during the message-passing loop, ensuring zero Garbage Collection overhead.

- **Lock-Free:** Coordination between processes uses Atomic CAS (Compare-And-Swap) operations instead of OS-level mutexes or `synchronized` blocks.

- **Inter-Process:** Enables communication between completely different Java instances on the same machine.