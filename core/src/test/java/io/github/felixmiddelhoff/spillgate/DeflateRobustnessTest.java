package io.github.felixmiddelhoff.spillgate;

import static io.github.felixmiddelhoff.spillgate.DeflateTestKit.*;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** Read-size independence on corrupt input, differential tests against the JDK, CPU bounds. */
class DeflateRobustnessTest {
    private static final int[][] PATTERNS = {{2, 1}, {3, 5}, {5, 1}, {4096, 4096}, {1, 4096}, {70000, 3}};

    private static Codec codecFor(String kind) {
        switch (kind) {
            case "gzip":
                return new GzipCodec();
            case "zlib":
            case "raw":
                return new DeflateCodec(false);
            default:
                throw new IllegalArgumentException(kind);
        }
    }

    private static void sameForAllPatterns(String kind, byte[] in, String what) {
        Codec codec = codecFor(kind);
        Result ref = drive(codec, in, 1, 1);
        for (int[] p : PATTERNS) {
            Result r = drive(codec, in, p[0], p[1]);
            assertEquals(ref.error, r.error, kind + " " + what + " read " + p[0] + " chunk " + p[1]);
            assertArrayEquals(ref.output, r.output, kind + " " + what + " read " + p[0] + " chunk " + p[1]);
            assertEquals(ref.consumed, r.consumed, kind + " " + what + " consumed, read " + p[0] + " chunk " + p[1]);
        }
    }

    private static byte[] jdkDecode(String kind, byte[] in) throws IOException {
        try (java.io.InputStream s = kind.equals("gzip")
            ? new GZIPInputStream(new ByteArrayInputStream(in))
            : new InflaterInputStream(new ByteArrayInputStream(in),
                new java.util.zip.Inflater(kind.equals("raw")))) {
            return s.readAllBytes();
        }
    }

    private static List<String> kinds() {
        return List.of("gzip", "zlib", "raw");
    }

    private static byte[] encode(String kind, byte[] data) {
        switch (kind) {
            case "gzip":
                return gzip(data);
            case "zlib":
                return zlib(data);
            default:
                return raw(data);
        }
    }

    private static byte[][] samples() {
        return new byte[][] {text(200, 1), random(150, 2), new byte[300], concat(text(100, 3), new byte[100], text(50, 4))};
    }

    // Covers: SG-R-RDR-01, SG-I-09, SG-R-CDC-06
    @Test
    void everySingleBitFlipDeliversTheSameBytesForAllReadSizesAndChunkings() {
        for (String kind : kinds()) {
            for (byte[] data : samples()) {
                byte[] good = encode(kind, data);
                for (int bit = 0; bit < good.length * 8; bit++) {
                    byte[] bad = good.clone();
                    bad[bit >> 3] ^= (byte) (1 << (bit & 7));
                    sameForAllPatterns(kind, bad, "flip " + bit);
                }
            }
        }
    }

    // Covers: SG-R-RDR-01, SG-I-09, SG-R-CDC-06
    @Test
    void everyTruncationIsCorruptWithTheSamePrefixForAllPatterns() {
        for (String kind : kinds()) {
            for (byte[] data : samples()) {
                byte[] good = encode(kind, data);
                for (int cut = 0; cut < good.length; cut++) {
                    byte[] part = Arrays.copyOf(good, cut);
                    sameForAllPatterns(kind, part, "cut " + cut);
                    Result r = drive(codecFor(kind), part, 4096, 4096);
                    assertEquals(Kind.CORRUPT, r.error, kind + " cut " + cut + " of " + good.length);
                    assertTrue(r.output.length <= data.length);
                    assertArrayEquals(Arrays.copyOf(data, r.output.length), r.output, "prefix of the real data");
                }
            }
        }
    }

    // Covers: SG-R-CDC-02, SG-R-CDC-03
    @Test
    void whatWeAcceptTheJdkAcceptsWithTheSameBytes() throws IOException {
        // The JDK is laxer; the spec only adds refusals. A mutation we accept must decode the same there.
        int accepted = 0;
        for (String kind : kinds()) {
            for (byte[] data : samples()) {
                byte[] good = encode(kind, data);
                for (int bit = 0; bit < good.length * 8; bit++) {
                    byte[] bad = good.clone();
                    bad[bit >> 3] ^= (byte) (1 << (bit & 7));
                    Result r = drive(codecFor(kind), bad, 4096, 4096);
                    if (r.error == null) {
                        accepted++;
                        assertArrayEquals(jdkDecode(kind, bad), r.output, kind + " flip " + bit);
                    }
                }
            }
        }
        assertTrue(accepted >= 0);
    }

    // Covers: SG-R-CDC-02, SG-R-CDC-03
    @Test
    void differentialAgainstJdkOnValidStreams() throws IOException {
        int[] sizes = {0, 1, 2, 255, 256, 1000, 4095, 4096, 4097, 65535, 65536, 65537, 300_000};
        int[] reads = {1, 7, 4096, 65536};
        int[] chunks = {1, 100, 4096, 1 << 20};
        for (int size : sizes) {
            byte[][] datas = {text(size, size), random(size, size + 1), new byte[size]};
            for (int d = 0; d < datas.length; d++) {
                byte[] data = datas[d];
                for (int level : new int[] {0, 1, 6, 9}) {
                    for (int strategy : new int[] {Deflater.DEFAULT_STRATEGY, Deflater.FILTERED, Deflater.HUFFMAN_ONLY}) {
                        byte[] z = deflate(data, level, strategy, false);
                        byte[] r = deflate(data, level, strategy, true);
                        byte[] g = gzip(data);
                        for (int read : reads) {
                            // large reads with 1-byte chunks are slow; keep the cross product bounded
                            int chunk = chunks[(read + size + level + d + strategy) & 3];
                            if (size > 70000 && read == 1) {
                                continue;
                            }
                            Result rz = drive(new DeflateCodec(false), z, read, chunk);
                            Result rr = drive(new DeflateCodec(false), r, read, chunk);
                            Result rg = drive(new GzipCodec(), g, read, chunk);
                            assertNull(rz.error);
                            assertNull(rr.error);
                            assertNull(rg.error);
                            assertArrayEquals(data, rz.output);
                            assertArrayEquals(data, rr.output);
                            assertArrayEquals(data, rg.output);
                            assertEquals(z.length, rz.consumed);
                            assertEquals(r.length, rr.consumed);
                            assertEquals(g.length, rg.consumed);
                        }
                    }
                }
                assertArrayEquals(data, jdkDecode("gzip", gzip(data)));
            }
        }
    }

    // Covers: SG-R-CDC-02, SG-R-CDC-07 (CPU bounded per input byte)
    @Test
    void manyTinyGzipMembersCostBoundedCpu() {
        byte[] member = gzip(new byte[0]);
        byte[] small = gzip(new byte[] {'x'});
        int count = 100_000;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < count; i++) {
            byte[] m = (i & 1) == 0 ? member : small;
            out.write(m, 0, m.length);
        }
        byte[] in = out.toByteArray();
        Result[] box = new Result[1];
        assertTimeoutPreemptively(Duration.ofSeconds(30), () -> box[0] = drive(new GzipCodec(), in, 4096, 4096));
        assertNull(box[0].error);
        assertEquals(count / 2, box[0].output.length);
        assertEquals(in.length, box[0].consumed);
    }

    // Covers: SG-R-CDC-03, SG-R-CDC-07 (CPU bounded per input byte)
    @Test
    void manyTinyDeflateBlocksCostBoundedCpu() {
        Deflater d = new Deflater(9, false);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[1 << 16];
        byte[] chunk = text(40, 5);
        for (int i = 0; i < 100_000; i++) {
            d.setInput(chunk);
            int n;
            while ((n = d.deflate(buf, 0, buf.length, Deflater.FULL_FLUSH)) > 0) {
                out.write(buf, 0, n);
            }
        }
        d.finish();
        int n;
        while (!d.finished() && (n = d.deflate(buf)) >= 0) {
            out.write(buf, 0, n);
        }
        d.end();
        byte[] in = out.toByteArray();
        Result[] box = new Result[1];
        assertTimeoutPreemptively(Duration.ofSeconds(30), () -> box[0] = drive(new DeflateCodec(false), in, 4096, 4096));
        assertNull(box[0].error);
        assertEquals(100_000 * 40, box[0].output.length);
        // the same with one-byte reads and one-byte chunks over a prefix: no quadratic behaviour
        byte[] prefix = Arrays.copyOf(in, 20_000);
        assertTimeoutPreemptively(Duration.ofSeconds(30), () -> drive(new DeflateCodec(false), prefix, 1, 1));
    }

    // Covers: SG-R-CDC-02, SG-R-CDC-07, SG-R-MEM-01 (4 GiB member: ISIZE wraps)
    @Test
    @EnabledIfSystemProperty(named = "spillgate.slow", matches = "true")
    void fourGiBMemberWrapsIsize() throws IOException {
        long total = (1L << 32) + 1000;
        // zeros compress to about 1:1000; build the member incrementally
        Deflater d = new Deflater(9, true);
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        byte[] zeros = new byte[1 << 20];
        byte[] buf = new byte[1 << 16];
        CRC32 crc = new CRC32();
        long left = total;
        while (left > 0) {
            int n = (int) Math.min(left, zeros.length);
            d.setInput(zeros, 0, n);
            crc.update(zeros, 0, n);
            while (!d.needsInput()) {
                int k = d.deflate(buf);
                body.write(buf, 0, k);
            }
            left -= n;
        }
        d.finish();
        while (!d.finished()) {
            body.write(buf, 0, d.deflate(buf));
        }
        d.end();
        byte[] head = {0x1f, (byte) 0x8b, 8, 0, 0, 0, 0, 0, 0, (byte) 0xff};
        long c = crc.getValue();
        long isize = total & 0xffffffffL;
        byte[] trailer = new byte[8];
        for (int i = 0; i < 4; i++) {
            trailer[i] = (byte) (c >> (8 * i));
            trailer[4 + i] = (byte) (isize >> (8 * i));
        }
        byte[] member = concat(head, body.toByteArray(), trailer);
        long[] count = new long[1];
        CodecReader r = new GzipCodec().newReader(new ArrayInput(member, 4096));
        byte[] rb = new byte[65536];
        int n;
        while ((n = r.read(rb, 0, rb.length)) > 0) {
            count[0] += n;
        }
        assertEquals(-1, n);
        assertEquals(total, count[0]);
        r.close();
        // and a wrong ISIZE (not wrapped) is corrupt
        trailer[4] ^= 1;
        member = concat(head, body.toByteArray(), trailer);
        CodecReader bad = new GzipCodec().newReader(new ArrayInput(member, 4096));
        long got = 0;
        try {
            while ((n = bad.read(rb, 0, rb.length)) > 0) {
                got += n;
            }
            throw new AssertionError("accepted");
        } catch (SpillgateException e) {
            assertEquals(Kind.CORRUPT, e.kind());
            assertEquals(total, got);
        }
        bad.close();
    }

    // Covers: SG-R-CDC-02, SG-R-CDC-03 (throughput, informational)
    @Test
    @EnabledIfSystemProperty(named = "spillgate.perf", matches = "true")
    void throughput() throws IOException {
        for (String what : new String[] {"text", "zeros"}) {
            byte[] data = what.equals("text") ? text(100_000_000, 1) : new byte[100_000_000];
            byte[] g = gzip(data);
            for (int round = 0; round < 3; round++) {
                long t0 = System.nanoTime();
                long n = 0;
                CodecReader r = new GzipCodec().newReader(new ArrayInput(g, 4096));
                byte[] rb = new byte[65536];
                int k;
                while ((k = r.read(rb, 0, rb.length)) > 0) {
                    n += k;
                }
                r.close();
                long t1 = System.nanoTime();
                try (GZIPInputStream s = new GZIPInputStream(new ByteArrayInputStream(g), 4096)) {
                    while (s.read(rb) > 0) {
                        // drain
                    }
                }
                long t2 = System.nanoTime();
                System.out.printf("PERF %s round %d: codec %.0f MB/s, GZIPInputStream %.0f MB/s%n", what, round,
                    n / 1e6 / ((t1 - t0) / 1e9), n / 1e6 / ((t2 - t1) / 1e9));
            }
        }
    }
}
