package io.github.felixmiddelhoff.spillgate.brotli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.felixmiddelhoff.spillgate.Kind;
import org.junit.jupiter.api.Test;

/**
 * A compressed meta-block of 700 bytes with MLEN = 1 declares 256 literal and 256 distance trees. The
 * decoder must not allocate megabytes per meta-block for it.
 */
class BrotliHostileCpuTest {
    static final int METABLOCKS = 4000;

    private static void varLen255(Support.BitWriter w) {
        w.bits(1, 1).bits(7, 3).bits(127, 7);
    }

    private static void simple1(Support.BitWriter w, int alphabet, int symbol) {
        int mb = 0;
        int c = alphabet - 1;
        while (c != 0) {
            c >>= 1;
            mb++;
        }
        w.bits(1, 2).bits(0, 2).bits(symbol, mb);
    }

    private static void hostileMeta(Support.BitWriter w) {
        w.bits(0, 1).bits(0, 2).bits(0, 16).bits(0, 1); // ISLAST=0, 4 nibbles, MLEN=1, compressed
        w.bits(0, 1).bits(0, 1).bits(0, 1); // one block type of each kind
        w.bits(0, 2).bits(0, 4); // NPOSTFIX, NDIRECT
        w.bits(0, 2); // context mode
        varLen255(w); // NTREESL = 256
        w.bits(0, 1);
        simple1(w, 256, 0);
        w.bits(0, 1);
        varLen255(w); // NTREESD = 256
        w.bits(0, 1);
        simple1(w, 256, 0);
        w.bits(0, 1);
        for (int i = 0; i < 256; i++) {
            simple1(w, 256, 65);
        }
        simple1(w, 704, 8);
        for (int i = 0; i < 256; i++) {
            simple1(w, 64, 0);
        }
    }

    static byte[] hostile(int n) {
        Support.BitWriter w = new Support.BitWriter();
        w.bits(0, 1); // window 16
        for (int i = 0; i < n; i++) {
            hostileMeta(w);
        }
        w.bits(3, 2); // ISLAST, ISLASTEMPTY
        return w.finish();
    }

    // Covers: SG-R-MEM-03
    @Test
    void hostile_meta_blocks_are_cheap() {
        byte[] s = hostile(METABLOCKS);
        long best = Long.MAX_VALUE;
        Support.Outcome o = null;
        for (int round = 0; round < 5; round++) {
            long t0 = System.nanoTime();
            o = Support.run(s, 24, Integer.MAX_VALUE, 65536, Long.MAX_VALUE);
            best = Math.min(best, System.nanoTime() - t0);
        }
        System.out.printf("brotli hostile meta-blocks: %d blocks, %d bytes, best %.3f s (%.0f ns/input byte)%n",
                METABLOCKS, s.length, best / 1e9, (double) best / s.length);
        assertEquals(METABLOCKS, o.bytes().length, "one output byte per meta-block; kind " + o.kind());
        assertEquals(null, o.kind());
        assertTrue(best < BOUND_NANOS, "took " + best / 1e6 + " ms");
    }

    static final long BOUND_NANOS = 500_000_000L;
}
