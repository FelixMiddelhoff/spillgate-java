package io.github.felixmiddelhoff.spillgate.brotli;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.felixmiddelhoff.spillgate.Decoder;
import io.github.felixmiddelhoff.spillgate.Kind;
import io.github.felixmiddelhoff.spillgate.Limits;
import io.github.felixmiddelhoff.spillgate.SpillgateException;
import io.github.felixmiddelhoff.spillgate.SpillgateInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Random;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;

/** SG-R-RDR-01 for brotli: failure does not cost the bytes already produced in the failing call. */
class BrotliFailureDeliveryTest {
    private static final int[] READS = {1, 7, 4096, 65536};
    private static final int[] CHUNKS = {1, 5, 4096, Integer.MAX_VALUE};

    private static void agree(byte[] stream, String what) {
        Support.Outcome ref = Support.run(stream, 24, Integer.MAX_VALUE, 65536, 64L << 20);
        for (int read : READS) {
            for (int chunk : CHUNKS) {
                Support.Outcome o = Support.run(stream, 24, chunk, read, 64L << 20);
                String w = what + " read " + read + " chunk " + chunk;
                assertEquals(ref.kind(), o.kind(), w);
                assertEquals(ref.bytes().length, o.bytes().length, w);
                assertArrayEquals(ref.bytes(), o.bytes(), w);
            }
        }
    }

    // Covers: SG-R-RDR-01
    @Test
    void the_reviewers_repro_delivers_the_same_bytes_then_corrupt() throws IOException {
        byte[] s;
        try (InputStream in = getClass().getResourceAsStream("/regress/rdr01_data_10.br")) {
            s = in.readAllBytes();
        }
        agree(s, "rdr01");
        Support.Outcome o = Support.run(s, 24, Integer.MAX_VALUE, 65536, 64L << 20);
        assertEquals(Kind.CORRUPT, o.kind());
        assertTrue(o.bytes().length > 0);
    }

    // Covers: SG-R-RDR-01 SG-R-CDC-06
    @Test
    void mutated_streams_deliver_the_same_bytes_and_outcome_for_every_read_size_and_chunking() {
        Random rnd = new Random(42);
        int corrupt = 0;
        int runs = 0;
        for (String[] g : Support.goldens()) {
            byte[] s = Support.resource(g[0]);
            if (s.length < 4) {
                continue;
            }
            for (int k = 0; k < 6; k++) {
                byte[] t = s.clone();
                for (int f = 0; f <= k; f++) {
                    t[rnd.nextInt(t.length)] ^= (byte) (1 << rnd.nextInt(8));
                }
                agree(t, g[0] + " mutation " + k);
                runs++;
                if (Support.run(t, 24, Integer.MAX_VALUE, 65536, 64L << 20).kind() == Kind.CORRUPT) {
                    corrupt++;
                }
            }
        }
        assertTrue(runs >= 300, "runs " + runs);
        assertTrue(corrupt > 50, "corrupt " + corrupt);
    }

    // Covers: SG-R-RDR-01 SG-R-RDR-05
    @Test
    void a_source_failure_in_a_brotli_stream_and_under_gzip_delivers_the_same_bytes() throws Exception {
        byte[] br = Support.resource("text.q5.w16.br");
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        try (GZIPOutputStream g = new GZIPOutputStream(bo)) {
            g.write(br);
        }
        byte[] gz = bo.toByteArray();
        Decoder d = Decoder.builder(Limits.maxOutput(1L << 26).withoutRatioLimit())
                .codec("br", BrotliCodec.create(BrotliOptions.defaults())).build();
        for (boolean zipped : new boolean[] {false, true}) {
            byte[] full = zipped ? gz : br;
            for (int cut : new int[] {full.length / 3, full.length / 2, full.length - 3}) {
                byte[] expected = null;
                for (int chunk : CHUNKS) {
                    for (int read : READS) {
                        byte[] got = failing(d, Arrays.copyOf(full, cut), chunk, read, zipped);
                        if (expected == null) {
                            expected = got;
                        }
                        assertArrayEquals(expected, got,
                                "zipped " + zipped + " cut " + cut + " chunk " + chunk + " read " + read);
                    }
                }
            }
        }
    }

    private static byte[] failing(Decoder d, byte[] head, int chunk, int read, boolean zipped) throws IOException {
        InputStream src = new InputStream() {
            private int pos;

            @Override
            public int read() {
                throw new UnsupportedOperationException();
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                if (pos >= head.length) {
                    throw new IOException("connection reset");
                }
                int n = Math.min(Math.min(len, chunk), head.length - pos);
                System.arraycopy(head, pos, b, off, n);
                pos += n;
                return n;
            }
        };
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[1 << 16];
        try (SpillgateInputStream s = zipped ? d.reader(src, "br", "gzip") : d.reader(src, "br")) {
            while (true) {
                int n = s.read(buf, 0, read);
                if (n < 0) {
                    break;
                }
                bo.write(buf, 0, n);
            }
        } catch (SpillgateException e) {
            assertTrue(e.kind() == Kind.SOURCE_ERROR || e.kind() == Kind.CORRUPT, "" + e.kind());
        }
        return bo.toByteArray();
    }
}
