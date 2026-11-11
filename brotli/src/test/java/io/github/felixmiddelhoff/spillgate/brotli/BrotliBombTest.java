package io.github.felixmiddelhoff.spillgate.brotli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.felixmiddelhoff.spillgate.CodecReader;
import java.lang.management.ManagementFactory;
import org.junit.jupiter.api.Test;

class BrotliBombTest {
    private static final long READ_CAP = 768L << 20;

    private static void bomb(long size, int wbits) throws Exception {
        byte[] stream = Support.zeros(size, wbits);
        com.sun.management.ThreadMXBean mx = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long tid = Thread.currentThread().getId();
        Support.ArrayInput[] h = new Support.ArrayInput[1];
        long before = mx.getThreadAllocatedBytes(tid);
        long total = 0;
        int maxRead = 0;
        try (CodecReader r = Support.reader(stream, 24, 4096, h)) {
            byte[] buf = new byte[1 << 20];
            while (total < READ_CAP) {
                int n = r.read(buf, 0, buf.length);
                assertTrue(n > 0, "the bomb ended early at " + total);
                maxRead = Math.max(maxRead, n);
                total += n;
            }
        }
        long allocated = mx.getThreadAllocatedBytes(tid) - before;
        assertTrue(maxRead <= 64 * 1024, "read of " + maxRead);
        assertEquals(64 * 1024, maxRead);
        // constant memory: the window (at most 16 MiB) plus a small constant, nothing per output byte
        long window = (1L << wbits) + 16;
        assertTrue(allocated < window + (6 << 20), "allocated " + allocated + " for window " + window);
    }

    @Test
    void oneGibBomb() throws Exception {
        // Covers: SG-R-MEM-01 SG-R-MEM-02 SG-R-LAY-04
        bomb(1L << 30, 24);
    }

    @Test
    void fourGibBombSmallWindow() throws Exception {
        // Covers: SG-R-MEM-01 SG-R-MEM-02
        bomb(4L << 30, 16);
    }

    @Test
    void sixtyFourGibBomb() throws Exception {
        // Covers: SG-R-MEM-01 SG-R-MEM-02 SG-R-CDC-07
        bomb(64L << 30, 22);
    }
}
