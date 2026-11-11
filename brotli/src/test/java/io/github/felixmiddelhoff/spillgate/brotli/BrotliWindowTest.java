package io.github.felixmiddelhoff.spillgate.brotli;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.felixmiddelhoff.spillgate.CodecReader;
import io.github.felixmiddelhoff.spillgate.Kind;
import io.github.felixmiddelhoff.spillgate.SpillgateException;
import java.lang.management.ManagementFactory;
import org.junit.jupiter.api.Test;

class BrotliWindowTest {
    /** RFC 7932 section 9.1 read bit by bit, independent of BrotliCodec.windowBits. */
    private static int rfc(int first) {
        java.util.function.IntUnaryOperator bit = i -> (first >> i) & 1;
        if (bit.applyAsInt(0) == 0) {
            return 16;
        }
        int n = bit.applyAsInt(1) | bit.applyAsInt(2) << 1 | bit.applyAsInt(3) << 2;
        if (n != 0) {
            return 17 + n;
        }
        int m = bit.applyAsInt(4) | bit.applyAsInt(5) << 1 | bit.applyAsInt(6) << 2;
        if (m == 0) {
            return 17;
        }
        return m == 1 ? 0 : 8 + m;
    }

    @Test
    void windowBitsTableForAll256FirstBytes() {
        // Covers: SG-R-CDC-04
        for (int b = 0; b < 256; b++) {
            assertEquals(rfc(b), BrotliCodec.windowBits(b), "first byte " + b);
        }
        int large = 0;
        for (int b = 0; b < 256; b++) {
            if (BrotliCodec.windowBits(b) == 0) {
                large++;
                assertEquals(0x11, b & 0x7f);
            }
        }
        assertEquals(2, large);
    }

    @Test
    void encoderWritesTheWindowTheTableReads() {
        // Covers: SG-R-CDC-04
        for (int w = 10; w <= 24; w++) {
            if (w == 10 || w == 16 || w == 24) {
                assertEquals(w, BrotliCodec.windowBits(Support.resource("text.q5.w" + w + ".br")[0] & 0xff));
            }
            assertEquals(w, BrotliCodec.windowBits(Support.stored(new byte[] {1}, w)[0] & 0xff));
        }
    }

    @Test
    void capIsExactAtEverySize() {
        // Covers: SG-R-CDC-04
        for (int w = 10; w <= 24; w++) {
            byte[] stream = Support.stored(new byte[] {1, 2, 3}, w);
            for (int cap = 10; cap <= 24; cap++) {
                Support.Outcome o = Support.run(stream, cap, Integer.MAX_VALUE, 64, Long.MAX_VALUE);
                if (w <= cap) {
                    assertNull(o.kind(), "window " + w + " cap " + cap);
                    assertArrayEquals(new byte[] {1, 2, 3}, o.bytes());
                } else {
                    assertEquals(Kind.MEMORY_LIMIT, o.kind(), "window " + w + " cap " + cap);
                    assertEquals(0, o.bytes().length);
                    // refused after the first byte: nothing but the first window was looked at
                    assertEquals(0, o.consumed());
                }
            }
        }
    }

    @Test
    void largeWindowMarkerIsMemoryLimitAtEveryCap() {
        // Covers: SG-R-CDC-04
        for (int cap = 10; cap <= 24; cap++) {
            for (int first : new int[] {0x11, 0x91}) {
                byte[] s = new byte[54];
                s[0] = (byte) first;
                Support.Outcome o = Support.run(s, cap, Integer.MAX_VALUE, 64, Long.MAX_VALUE);
                assertEquals(Kind.MEMORY_LIMIT, o.kind(), "cap " + cap);
                assertEquals(0, o.bytes().length);
            }
        }
    }

    @Test
    void refusalAllocatesNothingBeyondTheReaderAndRepeats() throws Exception {
        // Covers: SG-R-CDC-04 SG-R-CDC-07 SG-R-MEM-01
        byte[] bomb = Support.zeros(1L << 30, 24);
        com.sun.management.ThreadMXBean mx = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long tid = Thread.currentThread().getId();
        Support.ArrayInput[] h = new Support.ArrayInput[1];
        byte[] buf = new byte[64];
        // warm up
        try (CodecReader r = Support.reader(bomb, 16, 4096, h)) {
            assertThrows(SpillgateException.class, () -> r.read(buf, 0, 64));
        }
        long before = mx.getThreadAllocatedBytes(tid);
        try (CodecReader r = Support.reader(bomb, 16, 4096, h)) {
            SpillgateException e = assertThrows(SpillgateException.class, () -> r.read(buf, 0, 64));
            assertEquals(Kind.MEMORY_LIMIT, e.kind());
            long used = mx.getThreadAllocatedBytes(tid) - before;
            assertEquals(true, used < 16 * 1024, "allocated " + used);
            for (int i = 0; i < 3; i++) {
                assertEquals(Kind.MEMORY_LIMIT, assertThrows(SpillgateException.class, () -> r.read(buf, 0, 64)).kind());
            }
        }
    }

    @Test
    void optionValidation() throws Exception {
        // Covers: SG-R-CDC-04 SG-R-CDC-09
        for (int bits = 10; bits <= 24; bits++) {
            assertNotNull(BrotliCodec.create(BrotliOptions.defaults().withMaxWindowBits(bits)));
        }
        for (int bits : new int[] {Integer.MIN_VALUE, -1, 0, 9, 25, 30, Integer.MAX_VALUE}) {
            SpillgateException e = assertThrows(
                    SpillgateException.class, () -> BrotliCodec.create(BrotliOptions.defaults().withMaxWindowBits(bits)));
            assertEquals(Kind.INVALID_OPTION, e.kind());
        }
        assertEquals(24, BrotliOptions.defaults().maxWindowBits());
    }
}
