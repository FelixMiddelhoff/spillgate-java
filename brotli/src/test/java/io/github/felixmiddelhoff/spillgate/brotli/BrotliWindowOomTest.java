package io.github.felixmiddelhoff.spillgate.brotli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.felixmiddelhoff.spillgate.Kind;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Runs in the surefire execution with a 12 MiB heap (see pom.xml, group smallheap). */
@Tag("smallheap")
class BrotliWindowOomTest {
    // Covers: SG-R-MEM-03
    @Test
    void a_window_the_heap_cannot_hold_is_memory_limit() {
        assertTrue(Runtime.getRuntime().maxMemory() < 64L << 20, "the small heap is in effect");
        Support.BitWriter w = new Support.BitWriter();
        Support.windowBits(w, 24);
        // ISLAST=0, 6 nibbles, MLEN = 16 MiB, ISUNCOMPRESSED=1
        w.bits(0, 1).bits(2, 2).bits(0xFFFFFF, 24).bits(1, 1).align();
        byte[] stream = w.finish();
        for (int read : new int[] {1, 4096, 65536}) {
            Support.Outcome o = Support.run(stream, 24, Integer.MAX_VALUE, read, Long.MAX_VALUE);
            assertEquals(Kind.MEMORY_LIMIT, o.kind());
            assertEquals(0, o.bytes().length);
        }
    }
}
