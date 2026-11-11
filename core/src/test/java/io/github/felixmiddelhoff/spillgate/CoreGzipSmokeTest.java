package io.github.felixmiddelhoff.spillgate;

import static io.github.felixmiddelhoff.spillgate.CoreSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * A smoke test of the core with the real built-in codecs (the codecs themselves are tested by the
 * deflate part). It is skipped only while the temporary stubs of the codecs are in place.
 */
class CoreGzipSmokeTest {
    private static byte[] gzip(byte[] data) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (GZIPOutputStream out = new GZIPOutputStream(bos)) {
            out.write(data);
        }
        return bos.toByteArray();
    }

    private static byte[] zlib(byte[] data) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (DeflaterOutputStream out = new DeflaterOutputStream(bos)) {
            out.write(data);
        }
        return bos.toByteArray();
    }

    private static byte[] raw(byte[] data) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (DeflaterOutputStream out = new DeflaterOutputStream(bos, new Deflater(Deflater.DEFAULT_COMPRESSION, true))) {
            out.write(data);
        }
        return bos.toByteArray();
    }

    private static void assumeRealCodecs() throws Exception {
        try {
            Decoder.of(out(1000)).decodeAll(in(gzip("x".getBytes())), "gzip");
        } catch (SpillgateException e) {
            Assumptions.assumeFalse(e.getMessage().contains("stub"), "the gzip codec is still the temporary stub");
        }
    }

    private static byte[] hex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        }
        return out;
    }

    // Covers: SG-R-CDC-02 SG-R-CDC-03 SG-R-CDC-10
    @Test
    void the_built_in_codecs_decode_through_the_core() throws Exception {
        assumeRealCodecs();
        byte[] data = payload(50_000);
        Decoder d = Decoder.of(out(1 << 20));
        assertArrayEquals(data, d.decodeAll(in(gzip(data)), "gzip"));
        assertArrayEquals(data, d.decodeAll(in(gzip(data)), "x-gzip"));
        assertArrayEquals(data, d.decodeAll(in(zlib(data)), "deflate"));
        assertArrayEquals(data, d.decodeAll(in(raw(data)), "deflate"));
        assertArrayEquals(data, d.decodeAll(in(gzip(zlib(data))), "deflate, gzip"));
        assertArrayEquals(data, d.decodeAll(in(gzip(gzip(data))), "gzip", "gzip"));
        assertArrayEquals(data, d.decodeAll(in(data), "identity"));
        // read sizes do not matter
        for (int size : new int[] {1, 3, 4096, 1 << 20}) {
            Outcome o = run(d, gzip(data), "gzip", size);
            assertNull(o.kind);
            assertArrayEquals(data, o.bytes);
        }
        // the output limit at the boundary with a real codec
        byte[] g = gzip(data);
        assertArrayEquals(data, Decoder.of(out(data.length)).decodeAll(in(g), "gzip"));
        assertEquals(Kind.OUTPUT_LIMIT, assertThrows(SpillgateException.class,
                () -> Decoder.of(out(data.length - 1)).decodeAll(in(g), "gzip")).kind());
        // a bad trailer after exactly maxOutput bytes is corrupt
        byte[] bad = g.clone();
        bad[bad.length - 5] ^= 1;
        assertEquals(Kind.CORRUPT, run(Decoder.of(out(data.length)), bad, "gzip", 4096).kind);
        // garbage is corrupt, not an internal error
        assertEquals(Kind.CORRUPT, assertThrows(SpillgateException.class,
                () -> d.decodeAll(in(data), "gzip", "identity")).kind());
        // a gzip bomb is a ratio or output error
        byte[] zeros = gzip(new byte[10_000_000]);
        Outcome o = run(Decoder.of(Limits.maxOutput(100L << 20)), zeros, "gzip", 1 << 16);
        assertEquals(Kind.RATIO_LIMIT, o.kind);
        o = run(Decoder.of(out(1 << 20)), zeros, "gzip", 1 << 16);
        assertEquals(Kind.OUTPUT_LIMIT, o.kind);
    }

    // Covers: SG-R-RDR-01 SG-R-CDC-03 (trap 1 and 2 of the other ports)
    @Test
    void the_traps_of_the_other_ports() throws Exception {
        assumeRealCodecs();
        Decoder d = Decoder.of(out(1 << 20));
        // trap 1: code 16 after 17/18 is no bug with zlib
        for (int size : new int[] {1, 2, 7, 4096}) {
            Outcome o = run(d, hex("05c0250100000000a0ad04f9ff0004"), "deflate", size);
            assertNull(o.kind, "read size " + size);
            assertArrayEquals("a".getBytes(), o.bytes, "read size " + size);
        }
        // trap 2: bytes delivered before a corrupt error are the same for every read size
        Outcome reference = run(d, hex("4b4c4c043e00"), "deflate", 1);
        for (int size : new int[] {1, 2, 3, 4096, 1 << 20}) {
            Outcome other = run(d, hex("4b4c4c043e00"), "deflate", size);
            assertEquals(reference, other, "size " + size);
        }
        assertEquals(Kind.CORRUPT, reference.kind);
        assertArrayEquals("aaa".getBytes(), reference.bytes);
    }
}
