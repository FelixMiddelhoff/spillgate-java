package io.github.felixmiddelhoff.spillgate.zstd;

import static io.github.felixmiddelhoff.spillgate.zstd.Z.*;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.felixmiddelhoff.spillgate.Kind;
import io.github.felixmiddelhoff.spillgate.SpillgateException;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;

/** Hostile headers and sizes: nothing is allocated before the header is validated; memory stays constant. */
class ZstdHostileTest {

    @Test
    void windowCapIsExactAtEveryPowerOfTwo() {
        // Covers: SG-R-CDC-05 SG-R-CDC-07
        for (int windowLog = 10; windowLog <= 27; windowLog++) {
            long cap = 1L << windowLog;
            byte[] frame = zeros(1000, windowLog);
            assertNull(runCap(frame, cap).kind, "window 2^" + windowLog);
            if (windowLog > 10) {
                assertEquals(Kind.MEMORY_LIMIT, runCap(frame, cap - 1).kind, "window 2^" + windowLog);
            }
        }
    }

    @Test
    void windowsWithAMantissaAreCheckedToo() {
        // Covers: SG-R-CDC-05
        byte[] frame = concat(header(h().windowLog(20).mantissa(3)), rawBlocks(ascii("abc"), 3, true));
        long window = (1L << 20) + ((1L << 20) / 8) * 3;
        assertNull(runCap(frame, window).kind);
        assertEquals(Kind.MEMORY_LIMIT, runCap(frame, window - 1).kind);
        // every mantissa of one exponent
        for (int m = 0; m < 8; m++) {
            byte[] f = concat(header(h().windowLog(12).mantissa(m)), rawBlocks(ascii("x"), 1, true));
            long w = 4096 + 512L * m;
            assertNull(runCap(f, w).kind, "mantissa " + m);
            assertEquals(Kind.MEMORY_LIMIT, runCap(f, w - 1).kind, "mantissa " + m);
        }
    }

    @Test
    void singleSegmentWindowIsTheDeclaredContentSizeInEverySizeForm() {
        // Covers: SG-R-CDC-05 SG-R-CDC-07 SG-I-04
        long[] sizes = {
            0, 1, 255, 256, 300, 65_791, 65_792, 70_000, (1L << 32) - 1, 1L << 32, 1L << 40, 1L << 53, (1L << 53) + 1,
            Long.MAX_VALUE, Long.MIN_VALUE, -1L
        };
        for (long size : sizes) {
            byte[] frame = concat(header(h().single().size(size)), rawBlocks(ascii("x"), 1, true));
            boolean huge = Long.compareUnsigned(size, 1L << 30) > 0;
            long cap = huge ? 1L << 30 : Math.max(size, 1024);
            Outcome o = runCap(frame, cap);
            assertTrue(o.kind == null || o.kind == Kind.CORRUPT || o.kind == Kind.MEMORY_LIMIT, size + " " + o);
            if (huge) {
                assertEquals(Kind.MEMORY_LIMIT, o.kind, "" + size);
            }
            if (Long.compareUnsigned(size, DEFAULT_MAX_WINDOW) > 0) {
                assertEquals(Kind.MEMORY_LIMIT, runCap(frame, DEFAULT_MAX_WINDOW).kind, "" + size);
            }
            if (size >= 2048 && size <= 1L << 30) {
                assertEquals(Kind.MEMORY_LIMIT, runCap(frame, size - 1).kind, "" + size);
            }
        }
    }

    @Test
    void hostileHeadersAllocateNothingBeforeTheyAreRefused() {
        // Covers: SG-R-CDC-05 SG-I-04 SG-R-MEM-01
        byte[] frame = concat(header(h().single().size(1L << 40)), new byte[20]);
        Count c = count(new ArrayInput(frame), DEFAULT_MAX_WINDOW, Long.MAX_VALUE);
        assertEquals(Kind.MEMORY_LIMIT, c.kind);
        assertEquals(0, c.reader.heapBytes());
        // the same for a declared window of 2^41 + mantissa and for the largest cap
        byte[] huge = concat(header(h().windowLog(41).mantissa(7)), new byte[20]);
        c = count(new ArrayInput(huge), 1L << 30, Long.MAX_VALUE);
        assertEquals(Kind.MEMORY_LIMIT, c.kind);
        assertEquals(0, c.reader.heapBytes());
        // a frame within the cap but declaring a 1 GiB window and a tiny block allocates a tiny window
        byte[] f = concat(windowHeader(30), rawBlocks(ascii("hello"), 5, true));
        c = count(new ArrayInput(f), 1L << 30, Long.MAX_VALUE);
        assertNull(c.kind);
        assertEquals(5, c.total);
        assertTrue(c.peakHeap < MIB, "lazy window: " + c.peakHeap + " bytes held");
    }

    @Test
    void theMemoryLimitErrorCarriesTheCap() throws IOException {
        // Covers: SG-R-CDC-05
        ZstdCodec codec = codec(1 << 19);
        var r = codec.newReader(new ArrayInput(zeros(10, 20)));
        SpillgateException e = assertThrows(SpillgateException.class, () -> r.read(new byte[16], 0, 16));
        assertEquals(Kind.MEMORY_LIMIT, e.kind());
        assertEquals(1 << 19, e.limit());
    }

    @Test
    void dictionariesReservedBitReservedAndOversizedBlocksAreCorrupt() {
        // Covers: SG-R-CDC-05
        byte[] body = rawBlocks(ascii("abc"), 3, true);
        assertEquals(Kind.CORRUPT, run(concat(header(h().windowLog(17).dict(1)), body)).kind);
        assertEquals(Kind.CORRUPT, run(concat(header(h().windowLog(17).dict(0x0100_0000L)), body)).kind);
        assertEquals(Kind.CORRUPT, run(concat(header(h().windowLog(17).dict(0xffff_ffffL)), body)).kind);
        assertNull(run(concat(header(h().windowLog(17).dict(0)), body)).kind);
        assertEquals(Kind.CORRUPT, run(concat(header(h().windowLog(17).reserved()), body)).kind);
        // reserved block type 3
        assertEquals(Kind.CORRUPT, run(concat(windowHeader(17), bytes(0b0000_0111, 0, 0))).kind);
        // 129 KiB block
        byte[] big = concat(windowHeader(17), blockHeader(true, 0, 129 * 1024), new byte[129 * 1024]);
        assertEquals(Kind.CORRUPT, run(big).kind);
        // a block larger than the window (window 1 KiB, block 2 KiB)
        assertEquals(Kind.CORRUPT, run(concat(windowHeader(10), rawBlocks(new byte[2048], 2048, true))).kind);
        // a block exactly as large as the window is fine
        Outcome exact = run(concat(windowHeader(10), rawBlocks(filled(1024, 7), 1024, true)));
        assertNull(exact.kind);
        assertArrayEquals(filled(1024, 7), exact.out);
    }

    @Test
    void aOneGibBombOfZerosRunsInConstantMemory() {
        // Covers: SG-R-LAY-02 SG-R-MEM-02 SG-I-08
        byte[] bomb = zeros(1L << 30, 23); // 1 GiB of zeros in about 32 KiB
        assertTrue(bomb.length < 40_000);
        Count c = count(new ArrayInput(bomb), DEFAULT_MAX_WINDOW, Long.MAX_VALUE);
        assertNull(c.kind);
        assertEquals(1L << 30, c.total);
        // the window (8 MiB at most) plus the block buffers
        long bound = 8L * MIB + 3L * 2 * BLOCK_MAX;
        assertTrue(c.peakHeap <= bound, c.peakHeap + " bytes held after 1 GiB");
        // the same, stopped after 64 MiB as a limiter would
        c = count(new ArrayInput(bomb), DEFAULT_MAX_WINDOW, 64L * MIB);
        assertNull(c.kind);
        assertTrue(c.total >= 64L * MIB);
        assertTrue(c.peakHeap <= bound);
    }

    @Test
    @Tag("bigheap")
    void bombsWithBigWindowsStayWithinWindowPlusBlocks() {
        // Covers: SG-R-MEM-02 SG-R-CDC-05
        byte[] bomb = zeros(256L * MIB, 26);
        Count c = count(new ArrayInput(bomb), 1 << 26, Long.MAX_VALUE);
        assertNull(c.kind);
        assertEquals(256L * MIB, c.total);
        assertTrue(c.peakHeap <= (1L << 26) + 3L * 2 * BLOCK_MAX + MIB, "" + c.peakHeap);
    }

    @Test
    void aBigCapWithTinyOutputStaysSmall() {
        // Covers: SG-R-CDC-05 SG-R-MEM-01
        // a 1 GiB cap and a 1 GiB declared window with a small payload must not allocate the window
        byte[] payload = mixed();
        byte[] stream = concat(windowHeader(30), rawBlocks(payload, BLOCK_MAX, true));
        Count c = count(new ArrayInput(stream), 1L << 30, Long.MAX_VALUE);
        assertNull(c.kind);
        assertEquals(payload.length, c.total);
        assertTrue(c.peakHeap < 4L * MIB, "" + c.peakHeap);
    }
}
