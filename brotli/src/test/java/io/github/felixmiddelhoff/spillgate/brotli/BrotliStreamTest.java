package io.github.felixmiddelhoff.spillgate.brotli;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.felixmiddelhoff.spillgate.CodecInput;
import io.github.felixmiddelhoff.spillgate.CodecReader;
import io.github.felixmiddelhoff.spillgate.Kind;
import io.github.felixmiddelhoff.spillgate.SpillgateException;
import java.io.IOException;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class BrotliStreamTest {
    @Test
    void goldenRoundTripsAtEveryReadAndChunkSize() {
        // Covers: SG-R-CDC-01 SG-R-CDC-04
        for (String[] g : Support.goldens()) {
            byte[] stream = Support.resource(g[0]);
            byte[] want = Support.resource(g[1]);
            int cap = BrotliCodec.windowBits(stream[0] & 0xff);
            for (int read : new int[] {1, 7, 4096, 100_000}) {
                for (int chunk : new int[] {1, 5, 4096, Integer.MAX_VALUE}) {
                    if (read == 1 && stream.length > 3000 && chunk == 1) {
                        continue;
                    }
                    Support.Outcome o = Support.run(stream, cap, chunk, read, Long.MAX_VALUE);
                    String m = g[0] + " read " + read + " chunk " + chunk;
                    assertNull(o.kind(), m);
                    assertArrayEquals(want, o.bytes(), m);
                    assertEquals(stream.length, o.consumed(), m);
                    assertEquals(0, o.violations(), m);
                    assertTrue(o.maxRead() <= 65536);
                }
            }
        }
    }

    @Test
    void exactInputAccountingAtTheEnd() {
        // Covers: SG-R-CDC-01 SG-R-RAT-02
        byte[] stream = Support.resource("mixed.q5.w16.br");
        for (int chunk : new int[] {1, 3, 36, 37, 4096}) {
            Support.ArrayInput in = new Support.ArrayInput(stream, chunk);
            Support.Outcome o = Support.run(stream, 24, chunk, 4096, Long.MAX_VALUE);
            assertEquals(stream.length, o.consumed());
            assertNotNull(in);
        }
    }

    @Test
    void lookaheadIsBounded() throws Exception {
        // Covers: SG-R-CDC-01
        // After the first read the decoder may have consumed only a small constant beyond what it used.
        byte[] stream = Support.stored(new byte[5000], 10);
        Support.ArrayInput[] h = new Support.ArrayInput[1];
        try (CodecReader r = Support.reader(stream, 24, Integer.MAX_VALUE, h)) { // first output after one ring (1 KiB)
            byte[] b = new byte[16];
            assertEquals(16, r.read(b, 0, 16));
            assertTrue(h[0].consumed <= 1024 + 300, "consumed " + h[0].consumed);
        }
    }

    @Test
    void truncationAtEveryOffsetIsCorrupt() {
        // Covers: SG-R-CDC-06
        for (String n : new String[] {"text.q5.w16.br", "mixed.q11.w24.br", "noise.q0.w10.br", "zeros.q0.w16.br", "one.q5.w16.br"}) {
            byte[] s = Support.resource(n);
            for (int cut = 0; cut < s.length; cut++) {
                byte[] t = Arrays.copyOf(s, cut);
                for (int chunk : new int[] {Integer.MAX_VALUE, 1}) {
                    Support.Outcome o = Support.run(t, 24, chunk, 4096, Long.MAX_VALUE);
                    assertEquals(Kind.CORRUPT, o.kind(), n + " cut " + cut + " chunk " + chunk);
                }
            }
        }
    }

    @Test
    void emptyInputIsCorrupt() {
        // Covers: SG-R-CDC-06
        assertEquals(Kind.CORRUPT, Support.run(new byte[0]).kind());
    }

    @Test
    void trailingBytesAndConcatenatedStreamsAreCorrupt() {
        // Covers: SG-R-CDC-04 SG-R-CDC-06
        for (String n : new String[] {"text.q5.w16.br", "empty.q5.w16.br", "zeros.q0.w24.br", "mixed.q0.w10.br", "noise.q11.w24.br"}) {
            byte[] s = Support.resource(n);
            byte[][] variants = {
                Arrays.copyOf(s, s.length + 1),
                concat(s, new byte[] {(byte) 0xff}),
                Arrays.copyOf(s, s.length + 1000),
                concat(s, s),
                concat(s, Support.resource("empty.q5.w16.br")),
            };
            for (byte[] v : variants) {
                for (int chunk : new int[] {Integer.MAX_VALUE, 1, 7}) {
                    for (int read : new int[] {4096, 1}) {
                        if (read == 1 && v.length > 5000) {
                            continue;
                        }
                        Support.Outcome o = Support.run(v, 24, chunk, read, Long.MAX_VALUE);
                        assertEquals(Kind.CORRUPT, o.kind(), n + " chunk " + chunk + " read " + read + " len " + v.length);
                        // all valid output is delivered first, whatever the read size
                        assertArrayEquals(Support.reference(s), o.bytes(), n);
                    }
                }
            }
        }
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] r = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }

    @Test
    void errorFreesStateAndRepeats() throws Exception {
        // Covers: SG-R-CDC-04 SG-R-CDC-06 SG-R-MEM-02
        byte[] s = Support.zeros(40L << 20, 24);
        byte[] cut = Arrays.copyOf(s, s.length - 1);
        BrotliReader r = new BrotliReader(new Support.ArrayInput(cut, Integer.MAX_VALUE), 24);
        byte[] buf = new byte[65536];
        SpillgateException first = null;
        boolean held = false;
        while (first == null) {
            try {
                assertTrue(r.read(buf, 0, buf.length) > 0);
                held |= r.holdsState();
            } catch (SpillgateException e) {
                first = e;
            }
        }
        assertTrue(held);
        assertEquals(Kind.CORRUPT, first.kind());
        assertFalse(r.holdsState());
        for (int i = 0; i < 3; i++) {
            assertEquals(Kind.CORRUPT, assertThrows(SpillgateException.class, () -> r.read(buf, 0, buf.length)).kind());
        }
        // trailing-data error is repeated too and never turns into a clean end
        byte[] t = concat(Support.resource("text.q5.w16.br"), new byte[] {1});
        BrotliReader r2 = new BrotliReader(new Support.ArrayInput(t, Integer.MAX_VALUE), 24);
        for (int i = 0; i < 5; i++) {
            try {
                int n = r2.read(buf, 0, buf.length);
                assertTrue(n > 0 && i == 0);
            } catch (SpillgateException e) {
                assertEquals(Kind.CORRUPT, e.kind());
            }
        }
        assertFalse(r2.holdsState());
        r2.close();
    }

    @Test
    void sourceFailurePassesThroughUnchangedAndSticks() {
        // Covers: SG-R-RDR-05
        IOException boom = new IOException("boom");
        CodecInput failing = new CodecInput() {
            int calls;

            @Override
            public int fill() throws IOException {
                if (calls++ > 0) {
                    throw boom;
                }
                return 1;
            }

            @Override
            public byte[] buffer() {
                return new byte[] {0x00};
            }

            @Override
            public int position() {
                return 0;
            }

            @Override
            public void consume(int n) {}
        };
        CodecReader r = new BrotliReader(failing, 24);
        byte[] b = new byte[10];
        assertEquals(boom, assertThrows(IOException.class, () -> r.read(b, 0, 10)));
        assertEquals(boom, assertThrows(IOException.class, () -> r.read(b, 0, 10)));
    }

    @Test
    void zeroLengthReadAndCleanEndAreSticky() throws Exception {
        // Covers: SG-R-CDC-06
        byte[] s = Support.resource("one.q5.w16.br");
        CodecReader r = new BrotliReader(new Support.ArrayInput(s, Integer.MAX_VALUE), 24);
        byte[] b = new byte[10];
        assertEquals(0, r.read(b, 0, 0));
        assertEquals(1, r.read(b, 0, 10));
        assertEquals(-1, r.read(b, 0, 10));
        assertEquals(-1, r.read(b, 0, 10));
    }

    @Test
    void manyTinyMetaBlocksStayCheapPerInputByte() {
        // Covers: SG-R-MEM-02 (CPU per input byte)
        Support.BitWriter w = new Support.BitWriter();
        Support.windowBits(w, 16);
        int blocks = 40_000;
        for (int i = 0; i < blocks; i++) {
            w.bits(0, 1).bits(3, 2).bits(0, 1).bits(0, 2).align(); // ISLAST 0, MNIBBLES 3 = metadata, reserved 0, 0 size bytes
        }
        w.bits(1, 1).bits(1, 1);
        byte[] s = w.finish();
        long t0 = System.nanoTime();
        Support.Outcome o = Support.run(s);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertNull(o.kind());
        assertEquals(0, o.bytes().length);
        assertTrue(ms < 5000, ms + " ms");
    }
}
