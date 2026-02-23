# GhostLink
GhostLink is a high-performance messaging library designed for Java applications that require sub-microsecond latency. By leveraging **Memory-Mapped Files (mmap)** and **Off-Heap memory**, it allows two separate JVM processes to communicate via shared memory, bypassing the networking stack and the Java Garbage Collector.
