package io.github.felixmiddelhoff.spillgate;

import static io.github.felixmiddelhoff.spillgate.DeflateTestKit.*;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.zip.Deflater;
import org.junit.jupiter.api.Test;

class DeflateCodecTest {
    private static final int[] READS = {1, 2, 3, 5, 4096};
    private static final int[] CHUNKS = {1, 2, 7, 4096};

    private static Result run(boolean strict, byte[] in, int read, int chunk) {
        return drive(new DeflateCodec(strict), in, read, chunk);
    }

    // Covers: SG-R-CDC-03
    @Test
    void zlibAndRawRoundTrip() {
        for (int size : new int[] {0, 1, 100, 70000}) {
            byte[] data = text(size, size);
            for (int read : READS) {
                for (int chunk : CHUNKS) {
                    Result z = run(false, zlib(data), read, chunk);
                    assertNull(z.error, z.toString());
                    assertArrayEquals(data, z.output);
                    Result r = run(false, raw(data), read, chunk);
                    assertNull(r.error, r.toString());
                    assertArrayEquals(data, r.output);
                }
            }
        }
    }

    // Covers: SG-R-CDC-03
    @Test
    void strictZlibRefusesRaw() {
        byte[] data = text(1000, 1);
        assertNull(run(true, zlib(data), 100, 100).error);
        Result r = run(true, raw(data), 100, 100);
        assertEquals(Kind.CORRUPT, r.error);
        assertEquals(0, r.output.length);
    }

    // Covers: SG-R-CDC-03
    @Test
    void trapRawStreamDecodesToA() {
        // code 16 after 17/18 trap of the other ports
        Result r = run(false, hex("05c0250100000000a0ad04f9ff0004"), 1, 1);
        assertNull(r.error, r.toString());
        assertArrayEquals(new byte[] {'a'}, r.output);
    }

    // Covers: SG-R-CDC-03, SG-R-RDR-01
    @Test
    void trapBytesBeforeErrorDoNotDependOnReadSize() {
        for (int read : READS) {
            for (int chunk : CHUNKS) {
                Result r = run(false, hex("4b4c4c043e00"), read, chunk);
                assertEquals(Kind.CORRUPT, r.error, "read " + read + " chunk " + chunk);
                assertArrayEquals(new byte[] {'a', 'a', 'a'}, r.output, "read " + read + " chunk " + chunk);
            }
        }
    }

    // Covers: SG-R-CDC-03
    @Test
    void fdictIsCorruptNotRaw() {
        // CMF 78, FLG with FDICT: 0x78 0xbb (0x78bb % 31 == 0, bit 5 set)
        int flg = -1;
        for (int f = 0; f < 256; f++) {
            if (((0x78 << 8) | f) % 31 == 0 && (f & 0x20) != 0) {
                flg = f;
                break;
            }
        }
        byte[] in = concat(new byte[] {0x78, (byte) flg, 0, 0, 0, 1}, raw(text(100, 3)), new byte[4]);
        for (boolean strict : new boolean[] {false, true}) {
            Result r = run(strict, in, 4096, 4096);
            assertEquals(Kind.CORRUPT, r.error);
            assertEquals(0, r.output.length);
        }
    }

    // Covers: SG-R-CDC-03
    @Test
    void adlerMismatchAndTrailingAndTruncation() {
        byte[] data = text(5000, 5);
        byte[] ok = zlib(data);
        byte[] badAdler = ok.clone();
        badAdler[badAdler.length - 1] ^= 1;
        Result r = run(false, badAdler, 64, 5);
        assertEquals(Kind.CORRUPT, r.error);
        assertArrayEquals(data, r.output); // all data was delivered before the trailer was judged

        for (byte[] stream : new byte[][] {ok, raw(data)}) {
            Result t = run(false, concat(stream, new byte[] {0}), 4096, 3);
            assertEquals(Kind.CORRUPT, t.error);
            assertArrayEquals(data, t.output);
            // trailing zero padding too, and a second stream is trailing data
            assertEquals(Kind.CORRUPT, run(false, concat(stream, stream), 4096, 4096).error);
            assertEquals(Kind.CORRUPT, run(false, concat(stream, new byte[100]), 7, 4096).error);
            // truncation: every proper prefix is corrupt, never a clean end
            for (int cut = 0; cut < stream.length; cut++) {
                byte[] cutStream = java.util.Arrays.copyOf(stream, cut);
                Result c = run(false, cutStream, 4096, 4096);
                // a raw prefix may happen to be... never a valid complete stream shorter than the whole
                assertEquals(Kind.CORRUPT, c.error, "cut " + cut + " of " + stream.length);
                assertTrue(c.output.length <= data.length);
            }
        }
    }

    // Covers: SG-R-CDC-03
    @Test
    void exactInputAccounting() {
        byte[] data = text(3000, 9);
        for (byte[] stream : new byte[][] {zlib(data), raw(data)}) {
            for (int chunk : CHUNKS) {
                // trailing bytes make it corrupt, but every stream byte was consumed and not more
                byte[] in = concat(stream, new byte[] {1, 2, 3});
                Result r = run(false, in, 4096, chunk);
                assertEquals(Kind.CORRUPT, r.error);
                assertEquals(stream.length, r.consumed, "chunk " + chunk);
                Result ok = run(false, stream, 4096, chunk);
                assertEquals(stream.length, ok.consumed);
            }
        }
    }

    private static boolean rawTwoBytesAreCompleteEmptyStream(byte[] in) {
        java.util.zip.Inflater inf = new java.util.zip.Inflater(true);
        try {
            inf.setInput(in);
            byte[] out = new byte[8];
            int n = inf.inflate(out);
            return inf.finished() && n == 0 && inf.getRemaining() == 0;
        } catch (java.util.zip.DataFormatException e) {
            return false;
        } finally {
            inf.end();
        }
    }

    private static boolean oracleZlib(int b0, int b1) {
        return (b0 & 15) == 8 && (b0 >> 4) < 8 && (b0 * 256 + b1) % 31 == 0;
    }

    // Covers: SG-R-CDC-03
    @Test
    void allTwoBytePrefixesAgainstOracle() {
        // stored empty final block as a raw stream body: 01 00 00 ff ff
        byte[] rawBody = hex("010000ffff");
        for (int b0 = 0; b0 < 256; b0++) {
            for (int b1 = 0; b1 < 256; b1++) {
                boolean z = oracleZlib(b0, b1);
                byte[] in = {(byte) b0, (byte) b1};
                // Input of exactly two bytes: a zlib header alone is truncated (corrupt); non-zlib
                // is strict-corrupt, and in the non-strict mode a raw start that is not a complete
                // stream is truncated: corrupt as well. What differs is the message, which names the cause.
                Result strict = run(true, in, 16, 16);
                Result lax = run(false, in, 16, 16);
                assertEquals(Kind.CORRUPT, strict.error);
                if (!z && rawTwoBytesAreCompleteEmptyStream(in)) {
                    assertNull(lax.error, "raw " + b0 + " " + b1);
                    assertEquals(0, lax.output.length);
                    continue;
                }
                assertEquals(Kind.CORRUPT, lax.error);
                boolean strictSaysNotZlib = strict.message.contains("not a zlib stream");
                assertEquals(!z, strictSaysNotZlib, "strict " + b0 + " " + b1);
                if (z && (b1 & 0x20) != 0) {
                    assertTrue(lax.message.contains("dictionar"), "fdict " + b0 + " " + b1);
                }
                if (z && (b1 & 0x20) == 0) {
                    assertTrue(lax.message.contains("truncated"), "z " + b0 + " " + b1);
                }
            }
        }
        // And the decision decides the content: zlib-valid header + stored block decodes as zlib.
        byte[] data = {1, 2, 3};
        assertArrayEquals(data, run(false, zlib(data), 16, 16).output);
        assertTrue(rawBody.length == 5);
    }

    // Covers: SG-R-CDC-03
    @Test
    void rawStreamThatLooksLikeZlibHeaderIsNotMisreadAsData() {
        // raw deflate stream whose first two bytes are 78 01 (a valid zlib header): fails, never garbage
        byte[] in = hex("7801") ;
        Result r = run(false, concat(in, raw(text(50, 2))), 4096, 4096);
        assertEquals(Kind.CORRUPT, r.error);
    }

    // Covers: SG-R-CDC-03, SG-R-CDC-06
    @Test
    void emptyInputIsCorrupt() {
        assertEquals(Kind.CORRUPT, run(false, new byte[0], 16, 16).error);
        assertEquals(Kind.CORRUPT, run(false, new byte[1], 16, 16).error);
    }

    // Covers: SG-R-CDC-03
    @Test
    void levelsAndStrategies() {
        byte[] data = concat(text(30000, 4), random(5000, 4), new byte[20000]);
        for (int level = 0; level <= 9; level++) {
            for (int strategy : new int[] {Deflater.DEFAULT_STRATEGY, Deflater.FILTERED, Deflater.HUFFMAN_ONLY}) {
                for (boolean nowrap : new boolean[] {false, true}) {
                    byte[] c = deflate(data, level, strategy, nowrap);
                    Result r = run(false, c, 1000, 333);
                    assertNull(r.error, level + " " + strategy + " " + nowrap + " " + r);
                    assertArrayEquals(data, r.output);
                    assertEquals(c.length, r.consumed);
                }
            }
        }
    }

    // Covers: SG-R-CDC-03
    @Test
    void closeBeforeAndAfterIsSafe() throws Exception {
        CodecReader r = new DeflateCodec(false).newReader(new ArrayInput(zlib(text(10, 1)), 4));
        r.close();
        r.close();
        ArrayInput in = new ArrayInput(zlib(text(100000, 1)), 4096);
        CodecReader r2 = new DeflateCodec(false).newReader(in);
        byte[] b = new byte[10];
        assertEquals(10, r2.read(b, 0, 10));
        r2.close();
        r2.close();
    }
}
