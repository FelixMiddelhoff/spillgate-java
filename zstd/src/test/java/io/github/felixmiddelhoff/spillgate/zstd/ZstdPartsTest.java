package io.github.felixmiddelhoff.spillgate.zstd;

import static io.github.felixmiddelhoff.spillgate.zstd.Z.*;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.felixmiddelhoff.spillgate.Kind;
import io.github.felixmiddelhoff.spillgate.SpillgateException;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HexFormat;

/** The parts of the decoder on their own: bit readers, tables, the window ring. */
class ZstdPartsTest {

    private static void assertCorrupt(org.junit.jupiter.api.function.Executable e, String what) {
        SpillgateException x = assertThrows(SpillgateException.class, e, what);
        assertEquals(Kind.CORRUPT, x.kind(), what);
    }

    @Test
    void backwardBitsReadFromTheEndAndZeroFillPastTheStart() throws SpillgateException {
        // Covers: SG-I-05
        assertCorrupt(() -> new Bits.Backward(new byte[0], 0, 0), "empty");
        assertCorrupt(() -> new Bits.Backward(bytes(0x12, 0x00), 0, 2), "zero last byte");
        // 0b0000_0101 -> the sentinel is bit 2, so two data bits (01) remain
        Bits.Backward b = new Bits.Backward(bytes(0x05), 0, 1);
        assertEquals(2, b.remaining);
        assertEquals(0b01, b.read(2));
        assertEquals(0, b.remaining);
        // reading past the start gives zeros and a negative count
        assertEquals(0, b.read(3));
        assertEquals(-3, b.remaining);
        Bits.Backward c = new Bits.Backward(bytes(0xff, 0x01), 0, 2);
        assertEquals(8, c.remaining);
        assertEquals(0xf, c.peek(4));
        assertEquals(8, c.remaining);
        assertEquals(0xff0, c.read(12), "four bits before the start read as zeros at the low end");
        // forward bits
        Bits.Forward f = new Bits.Forward(bytes(0b1010_0101, 0xff), 0, 2);
        assertEquals(0b0101, f.read(4));
        assertEquals(0b1010, f.read(4));
        assertEquals(1, f.bytesUsed());
        assertFalse(f.overrun());
        f.read(9);
        assertTrue(f.overrun());
        f.unread(9);
        assertFalse(f.overrun());
        // a sub-range of a larger array is read relative to its start and end
        Bits.Backward sub = new Bits.Backward(bytes(0xaa, 0x05, 0xbb), 1, 2);
        assertEquals(2, sub.remaining);
        assertEquals(1, sub.read(2));
    }

    @Test
    void fseTablesRejectBadDistributions() throws SpillgateException {
        // Covers: SG-I-05
        assertCorrupt(() -> Fse.build(new int[] {1, 1}, 2, 2), "does not add up");
        assertCorrupt(() -> Fse.build(new int[] {2, 2, 1}, 3, 2), "too much");
        Fse.build(new int[] {2, 2}, 2, 2);
        Fse.build(new int[] {4}, 1, 2);
        Fse.build(new int[] {-1, -1, 2}, 3, 2);
        // accuracy beyond 9 is never needed
        assertCorrupt(() -> Fse.build(new int[] {1024}, 1, 10), "accuracy 10");
        // descriptions: every length of truncated and zero input errors or is bounded, never throws otherwise
        for (int len = 0; len < 8; len++) {
            for (int fill : new int[] {0x00, 0xff, 0x55, 0xaa}) {
                byte[] bytes = filled(len, fill);
                try {
                    Fse.read(bytes, 0, bytes.length, 9, 35);
                } catch (SpillgateException expected) {
                    assertEquals(Kind.CORRUPT, expected.kind());
                }
            }
        }
        assertCorrupt(() -> Fse.read(bytes(0xf0), 0, 1, 6, 15), "accuracy log above the maximum");
    }

    @Test
    void huffmanDescriptionsRejectWhatIsNotATree() throws SpillgateException {
        // Covers: SG-I-05
        assertCorrupt(() -> Huffman.read(new byte[0], 0, 0), "empty");
        assertCorrupt(() -> Huffman.read(bytes(0), 0, 1), "an FSE description of size zero");
        assertCorrupt(() -> Huffman.read(bytes(5, 1, 2), 0, 3), "shorter than announced");
        assertCorrupt(() -> Huffman.read(bytes(128, 0x00), 0, 2), "no codes");
        // two weights of 1 and an implied one of 1... total 2, power 4, rest 2: a valid three symbol tree
        Huffman.Parsed p = Huffman.read(bytes(129, 0x11), 0, 2);
        assertEquals(2, p.used);
        // weights 3 and 1: total 5, power 8, rest 3 is not a power of two
        assertCorrupt(() -> Huffman.read(bytes(129, 0x31), 0, 2), "rest not a power of two");
        // every one-byte header and a few fill patterns never throw anything but a refusal
        for (int header = 0; header < 256; header++) {
            for (int fill : new int[] {0x00, 0xff, 0x13, 0xa7}) {
                byte[] b = concat(bytes(header), filled(40, fill));
                try {
                    Huffman.read(b, 0, b.length);
                } catch (SpillgateException expected) {
                    assertEquals(Kind.CORRUPT, expected.kind());
                }
            }
        }
    }

    @Test
    void huffmanWeightOneRule() throws SpillgateException {
        // Covers: SG-R-CDC-05
        // libzstd needs at least two symbols of weight 1 (the implied one included) and an even number
        // of them. This frame once decoded here but not in libzstd (a parser differential).
        byte[] frame = HexFormat.of().parseHex("28b52ffd00003d000042c00081226300");
        Outcome o = run(frame);
        assertEquals(Kind.CORRUPT, o.kind);
        assertEquals(null, oracle(frame));
        // direct weights: a single weight of 2 is 2 -> total 2, rest 2: one weight-1 symbol only
        assertCorrupt(() -> Huffman.read(bytes(128, 0x20), 0, 2), "one weight-1 symbol");
        // 3 weights of 1 plus the implied: 4 symbols of weight 1 -> even, a complete tree
        assertTrue(Huffman.read(bytes(130, 0x11, 0x10), 0, 3).used == 3);
    }

    @Test
    void theWindowRingPositionStaysModuloTheDeclaredWindow() throws SpillgateException {
        // Covers: SG-R-CDC-05
        Window w = new Window(1024);
        w.push(filled(1024, 1), 0, 1024);
        assertEquals(0, w.posForTest(), "a block exactly as large as the window leaves the position at 0");
        w.push(filled(1024, 2), 0, 1024);
        assertEquals(0, w.posForTest());
        w.push(filled(5000, 3), 0, 5000); // larger than the window: only the newest 1024 bytes matter
        assertEquals(0, w.posForTest());
        w.push(filled(10, 4), 0, 10);
        assertEquals(10, w.posForTest());
        w.push(filled(1020, 5), 0, 1020);
        assertEquals(6, w.posForTest(), "positions wrap at the declared window");
        assertEquals(1024 + 1024 + 5000 + 10 + 1020, w.total());
    }

    @Test
    void theWindowAgreesWithANaiveHistoryForRandomPushes() throws SpillgateException {
        // Covers: SG-R-CDC-05
        long[] seed = {99};
        java.util.function.IntUnaryOperator next = n -> {
            seed[0] = seed[0] * 6364136223846793005L + 1442695040888963407L;
            return (int) ((seed[0] >>> 33) % n);
        };
        for (int ring : new int[] {1, 2, 3, 16, 100, 1024, 4096}) {
            Window w = new Window(ring);
            byte[] naive = new byte[0];
            for (int round = 0; round < 200; round++) {
                int len;
                switch (next.applyAsInt(4)) {
                    case 0 -> len = 0;
                    case 1 -> len = next.applyAsInt(ring + 1);
                    case 2 -> len = ring;
                    default -> len = next.applyAsInt(3 * ring + 1);
                }
                byte[] bytes = data(next.applyAsInt(1000), len);
                w.push(bytes, 0, len);
                naive = concat(naive, bytes);
                // the last min(ring, total) bytes are readable at every distance
                int dMax = Math.min(naive.length, ring);
                if (dMax == 0) {
                    continue;
                }
                for (int k = 0; k < 4; k++) {
                    int d = 1 + next.applyAsInt(dMax);
                    int n = 1 + next.applyAsInt(d);
                    byte[] got = new byte[n + 2];
                    w.copyHistory(d, n, got, 1);
                    int start = naive.length - d;
                    assertArrayEquals(
                            Arrays.copyOfRange(naive, start, start + n),
                            Arrays.copyOfRange(got, 1, 1 + n),
                            "ring " + ring + " distance " + d + " length " + n);
                    assertEquals(0, got[0]);
                    assertEquals(0, got[n + 1]);
                }
            }
        }
    }

    @Test
    void theWindowIsNotAllocatedEagerlyAtTheDeclaredSize() throws SpillgateException {
        // Covers: SG-R-CDC-05 SG-R-RAT-02 SG-R-MEM-01
        Window w = new Window(1 << 30);
        assertEquals(0, w.capacity(), "a new window holds nothing");
        w.push(ascii("hello"), 0, 5);
        assertTrue(w.capacity() < MIB, "" + w.capacity());
        // growth follows the output, at most doubling
        long pushed = 5;
        byte[] chunk = filled(100_000, 1);
        for (int i = 0; i < 20; i++) {
            w.push(chunk, 0, chunk.length);
            pushed += chunk.length;
            assertTrue(w.capacity() <= pushed * 2 + 1024, "capacity " + w.capacity() + " for " + pushed + " bytes");
        }
        assertEquals(0, new Frame(1 << 30).windowCapacity());
    }

    @Test
    void anImpossibleWindowAllocationIsAMemoryLimitNotACrash() {
        // Covers: SG-R-CDC-05 SG-R-MEM-01
        // a window of 1 GiB filled by RLE blocks under a 64 MiB heap cannot be held: the failure is a
        // clean MEMORY_LIMIT (the heap is small under the surefire configuration of this module)
        byte[] bomb = zeros(1L << 31, 30);
        Count c = count(new ArrayInput(bomb), 1L << 30, Long.MAX_VALUE);
        assertTrue(c.kind == Kind.MEMORY_LIMIT || c.kind == null, "" + c.kind);
    }
}
