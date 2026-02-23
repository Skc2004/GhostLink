package io.ghostlink; // <--- Note the package is different here

import io.ghostlink.core.MappedArena;
import java.nio.MappedByteBuffer;

public class App {
    public static void main(String[] args) {
        try {
            System.out.println("Starting Low-Level Memory Test...");
            
            // Allocate 10 MB
            MappedArena arena = new MappedArena("ghost.data", 10 * 1024 * 1024);
            MappedByteBuffer buffer = arena.getBuffer();
            
            // Write to the first 8 bytes
            buffer.putLong(0, 0xCAFEBABE);
            
            System.out.printf("Success! Read value: 0x%X%n", buffer.getLong(0));
            
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}