package io.github.felixmiddelhoff.spillgate.zstd;

import static io.github.felixmiddelhoff.spillgate.zstd.Z.*;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Round trips against zstd-jni as the reference encoder: levels, strategies, windows, sizes, checks. */
class ZstdDifferentialTest {

    private static Map<String, byte[]> payloads() {
        Map<String, byte[]> m = new LinkedHashMap<>();
        m.put("empty", new byte[0]);
        m.put("one byte", ascii("a"));
        m.put("text", text());
        m.put("noise", data(5, 70_000));
        m.put("zeros", new byte[300_000]);
        m.put("mixed", mixed());
        return m;
    }

    private static void assertDecodes(byte[] stream, byte[] expected, long cap, String what) {
        Outcome o = runCap(stream, cap);
        assertNull(o.kind, what);
        assertTrue(java.util.Arrays.equals(expected, o.out), what + ": wrong output");
    }

    @Test
    void roundTripEveryLevel() {
        // Covers: SG-R-CDC-05 SG-R-RDR-01
        for (Map.Entry<String, byte[]> e : payloads().entrySet()) {
            byte[] payload = e.getValue();
            List<Integer> levels = new ArrayList<>();
            if (payload.length > 100_000) {
                for (int l = 1; l <= 22; l += 3) {
                    levels.add(l);
                }
                levels.add(19);
                levels.add(22);
            } else {
                for (int l = 1; l <= 22; l++) {
                    levels.add(l);
                }
            }
            for (int level : levels) {
                for (boolean checksum : new boolean[] {false, true}) {
                    byte[] stream = compress(payload, level, params().checksum(checksum));
                    assertDecodes(stream, payload, 1L << 27, e.getKey() + " level " + level + " checksum " + checksum);
                }
            }
        }
    }

    @Test
    void roundTripAllLevelsOnMixed() {
        // Covers: SG-R-CDC-05
        byte[] payload = mixed();
        for (int level = 1; level <= 22; level++) {
            assertDecodes(compress(payload, level, params().checksum(true)), payload, 1L << 27, "level " + level);
        }
    }

    @Test
    void roundTripNegativeAndFastLevels() {
        // Covers: SG-R-CDC-05
        byte[] payload = mixed();
        for (int level : new int[] {-131072, -5, -1, 0}) {
            assertDecodes(compress(payload, level, params()), payload, 1L << 27, "level " + level);
        }
    }

    @Test
    void everyStrategyAndWindow() {
        // Covers: SG-R-CDC-05
        byte[] payload = mixed();
        for (int strategy = 1; strategy <= 9; strategy++) {
            for (int windowLog : new int[] {10, 12, 15, 17, 20, 23}) {
                byte[] stream = compress(payload, 6, params().strategy(strategy).windowLog(windowLog));
                assertDecodes(stream, payload, 1L << 24, "strategy " + strategy + " window " + windowLog);
            }
        }
    }

    @Test
    void everyWindowLogUpToTheDefaultCap() {
        // Covers: SG-R-CDC-05
        byte[] payload = mixed();
        for (int windowLog = 10; windowLog <= 23; windowLog++) {
            byte[] stream = compress(payload, 9, params().windowLog(windowLog).checksum(true));
            assertDecodes(stream, payload, DEFAULT_MAX_WINDOW, "window " + windowLog);
        }
    }

    @Test
    void contentSizeOnOffAndLongDistanceMatching() {
        // Covers: SG-R-CDC-05
        byte[] payload = mixed();
        Params[] all = {
            params().contentSize(false),
            params().contentSize(true),
            params().ldm().windowLog(23),
            params().ldm().windowLog(23).checksum(true).contentSize(false),
        };
        for (int i = 0; i < all.length; i++) {
            assertDecodes(compress(payload, 5, all[i]), payload, 1L << 24, "variant " + i);
        }
    }

    @Test
    void singleSegmentFramesFromTheReference() {
        // Covers: SG-R-CDC-05 SG-R-CDC-07
        // small payloads with a known content size make the reference emit single-segment frames
        for (int size : new int[] {0, 1, 100, 255, 256, 257, 1000, 65_791, 65_792, 70_000}) {
            byte[] payload = data(size, size);
            byte[] stream = compress(payload, 3, params().contentSize(true));
            assertDecodes(stream, payload, 1L << 20, "size " + size);
        }
    }

    @Test
    void checksumHashAgreesWithTheReferenceForManySizes() {
        // Covers: SG-R-CDC-05
        assertEquals(0x51d8e999L, new Xxh64().digest32()); // XXH64("") = 0xEF46DB3751D8E999, low 32 bits
        for (int size : new int[] {
            0, 1, 3, 4, 7, 8, 9, 31, 32, 33, 63, 64, 65, 100, 1000, 4097, 131_072, 131_073, 300_000
        }) {
            byte[] payload = data(size, size);
            assertDecodes(checksummed(payload, 3), payload, 1L << 27, "size " + size);
        }
    }

    @Test
    void xxh64KnownVectorsAndSplitUpdates() {
        // Covers: SG-R-CDC-05
        Xxh64 h = new Xxh64();
        h.update(new byte[0], 0, 0);
        assertEquals(0xef46db3751d8e999L, h.digest());
        h = new Xxh64();
        h.update(ascii("a"), 0, 1);
        assertEquals(0xd24ec4f1a98c6e5bL, h.digest());
        h = new Xxh64();
        h.update(ascii("abc"), 0, 3);
        assertEquals(0x44bc2cf5ad770999L, h.digest());
        // any split of the input gives the same digest as one update
        byte[] bytes = data(77, 300);
        Xxh64 whole = new Xxh64();
        whole.update(bytes, 0, bytes.length);
        for (int a = 0; a < bytes.length; a++) {
            for (int b : new int[] {a, Math.min(a + 1, bytes.length), Math.min(a + 33, bytes.length), bytes.length}) {
                Xxh64 parts = new Xxh64();
                parts.update(bytes, 0, a);
                parts.update(bytes, a, b - a);
                parts.update(bytes, b, bytes.length - b);
                assertEquals(whole.digest(), parts.digest(), "split at " + a + "/" + b);
            }
        }
    }

    @Test
    void matchesAcrossTheRingBoundaryAgreeWithTheReference() {
        // Covers: SG-R-CDC-05
        // small windows force every match to straddle the ring end many times
        byte[] payload = new byte[200_000];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (((i % 701) * 31 + (i / 701) % 5) & 0xff);
        }
        for (int windowLog = 10; windowLog <= 14; windowLog++) {
            for (int level : new int[] {1, 3, 9, 19}) {
                byte[] stream = compress(payload, level, params().windowLog(windowLog).checksum(true));
                assertDecodes(stream, payload, 1L << 20, "window " + windowLog + " level " + level);
            }
        }
    }

    @Test
    void chainsWithOtherCodecsStillSeeAPlainStream() {
        // Covers: SG-R-LAY-01
        // zstd inside zstd: the output of one is the input of the other
        byte[] inner = compress(text());
        byte[] outer = compress(inner);
        Outcome middle = run(outer);
        assertNull(middle.kind);
        assertArrayEquals(inner, middle.out);
        Outcome last = run(middle.out);
        assertNull(last.kind);
        assertArrayEquals(text(), last.out);
    }

    @Test
    void windowSizedBlockRegression() {
        // Covers: SG-R-CDC-05
        // Found by the independent review of the TypeScript port: after a block of exactly the window
        // size the write position must be 0, or a later match reads the wrong bytes.
        byte[] big = new byte[1024];
        for (int i = 0; i < big.length; i++) {
            big[i] = (byte) ((i * 13 + 7) & 255);
        }
        byte[] sequence = bytes(0x00, 0x01, 0x00, 0, 0, 2);
        byte[] frame = concat(
                bytes(0x28, 0xb5, 0x2f, 0xfd, 0x00, 0x00),
                blockHeader(false, 0, 3),
                ascii("XYZ"),
                blockHeader(false, 0, 1024),
                big,
                blockHeader(true, 2, sequence.length),
                sequence);
        Outcome o = run(frame);
        assertNull(o.kind);
        assertArrayEquals(oracle(frame), o.out);
    }

    @Test
    void zeroSequenceBlockMustEndAfterTheCount() {
        // Covers: SG-R-CDC-05
        // Found by the bit flip differential test: with no sequences the block must end at the count.
        // raw literals "abc", zero sequences
        byte[] noTail = concat(bytes(0x18), ascii("abc"), bytes(0x00));
        for (byte[] tail : new byte[][] {new byte[0], bytes(0x04), bytes(0x00, 0x00)}) {
            byte[] content = concat(noTail, tail);
            byte[] frame = concat(windowHeader(17), blockHeader(true, 2, content.length), content);
            Outcome o = run(frame);
            if (tail.length == 0) {
                assertNull(o.kind);
                assertArrayEquals(ascii("abc"), o.out);
                assertArrayEquals(ascii("abc"), oracle(frame));
            } else {
                assertEquals(io.github.felixmiddelhoff.spillgate.Kind.CORRUPT, o.kind);
            }
        }
        byte[] one = concat(noTail, bytes(0x04));
        assertNull(oracle(concat(windowHeader(17), blockHeader(true, 2, one.length), one)));
    }
}
