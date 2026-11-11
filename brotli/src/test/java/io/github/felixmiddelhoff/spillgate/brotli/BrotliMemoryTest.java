package io.github.felixmiddelhoff.spillgate.brotli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.felixmiddelhoff.spillgate.Kind;
import io.github.felixmiddelhoff.spillgate.SpillgateException;
import java.lang.management.ManagementFactory;
import org.junit.jupiter.api.Test;

class BrotliMemoryTest {
    private static final com.sun.management.ThreadMXBean MX =
            (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();

    /** Bytes allocated by one decode of {@code stream} until its error. */
    private static long hog(byte[] stream, int cap) {
        long tid = Thread.currentThread().getId();
        BrotliReader r = new BrotliReader(new Support.ArrayInput(stream, Integer.MAX_VALUE), cap);
        long before = MX.getThreadAllocatedBytes(tid);
        SpillgateException e = assertThrows(SpillgateException.class, () -> r.read(new byte[64], 0, 64));
        long used = MX.getThreadAllocatedBytes(tid) - before;
        assertEquals(Kind.CORRUPT, e.kind());
        assertFalse(r.holdsState());
        return used;
    }

    @Test
    void hostileHeaderTablesStayUnderTheMemoryBound() {
        // Covers: SG-R-MEM-01 SG-R-MEM-02 SG-R-MEM-03 SG-R-CDC-07 SG-I-04
        byte[] w10 = Support.tableHog(10, 256);
        byte[] w24 = Support.tableHog(24, 256);
        hog(w10, 10);
        hog(w24, 24);
        long small = hog(w10, 10);
        long big = hog(w24, 24);
        System.out.println("brotli hostile header: " + small + " bytes allocated in total at window 2^10, " + big
                + " at window 2^24 (the first meta-block declares 64 KiB, so the ring stays 64 KiB); live peak is about 3.3 MiB "
                + "(measured: the decode survives -Xmx5m, not -Xmx4m)");
        // total allocation (live tables of about 3.3 MiB plus short-lived scratch arrays) bounds the peak
        assertTrue(small < 6L << 20, "allocated " + small);
        assertTrue(big < 6L << 20, "allocated " + big);
    }

    @Test
    void nothingIsRetainedAcrossManyFailedDecodes() {
        // Covers: SG-R-MEM-02
        byte[] w24 = Support.tableHog(24, 256);
        byte[] bomb = Support.zeros(1L << 30, 24);
        System.gc();
        long before = used();
        for (int i = 0; i < 20; i++) {
            hog(w24, 24);
            Support.run(bomb, 24, 4096, 65536, 64L << 20);
        }
        System.gc();
        long held = used() - before;
        assertTrue(held < 4 << 20, "held " + held);
    }

    private static long used() {
        Runtime rt = Runtime.getRuntime();
        return rt.totalMemory() - rt.freeMemory();
    }

    @Test
    void refusedWindowCreatesNoDecoderState() {
        // Covers: SG-R-CDC-04 SG-R-CDC-07
        BrotliReader r = new BrotliReader(new Support.ArrayInput(new byte[] {0x0b, 0, 0}, 4096), 21);
        assertEquals(Kind.MEMORY_LIMIT, assertThrows(SpillgateException.class, () -> r.read(new byte[8], 0, 8)).kind());
        assertFalse(r.holdsState());
        assertEquals(Kind.MEMORY_LIMIT, assertThrows(SpillgateException.class, () -> r.read(new byte[8], 0, 8)).kind());
    }
}
