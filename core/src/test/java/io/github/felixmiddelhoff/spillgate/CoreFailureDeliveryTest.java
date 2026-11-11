package io.github.felixmiddelhoff.spillgate;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Random;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;

/**
 * SG-R-RDR-01: the bytes delivered before a failure of the source (or of a lower layer) do not depend
 * on the read size or on how the source chops its data.
 */
class CoreFailureDeliveryTest {
    private static final int[] CHUNKS = {1, 3, 7, 4096, Integer.MAX_VALUE};
    private static final int[] READS = {1, 3, 4096, 65536, 0 /* random */};

    /** Delivers {@code head} in pieces of at most {@code chunk} bytes, then throws. */
    static final class FailingSource extends InputStream {
        private final byte[] head;
        private final int chunk;
        private int pos;

        FailingSource(byte[] head, int chunk) {
            this.head = head;
            this.chunk = chunk;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n < 0 ? -1 : one[0] & 0xff;
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
    }

    static byte[] payload() {
        byte[] data = new byte[200000];
        Random r = new Random(1);
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) ((i % 1000 < 500) ? 'a' + r.nextInt(3) : data[Math.max(0, i - 700)]);
        }
        return data;
    }

    static byte[] gzip(byte[] d) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        try (GZIPOutputStream g = new GZIPOutputStream(bo)) {
            g.write(d);
        }
        return bo.toByteArray();
    }

    static byte[] zlib(byte[] d) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        try (DeflaterOutputStream z = new DeflaterOutputStream(bo)) {
            z.write(d);
        }
        return bo.toByteArray();
    }

    static CoreSupport.Outcome run(Decoder d, byte[] head, int chunk, int read, String... header) throws IOException {
        SpillgateInputStream s = d.reader(new FailingSource(head, chunk), header);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        Random rnd = new Random(7);
        byte[] buf = new byte[1 << 20];
        Kind kind = null;
        try {
            while (true) {
                int n = s.read(buf, 0, read == 0 ? 1 + rnd.nextInt(3000) : read);
                if (n < 0) {
                    break;
                }
                bo.write(buf, 0, n);
            }
        } catch (SpillgateException e) {
            kind = e.kind();
        } finally {
            s.close();
        }
        return new CoreSupport.Outcome(bo.toByteArray(), kind, 0, 0);
    }

    /** All read sizes and source chunkings must agree on bytes and kind; returns the common outcome. */
    static CoreSupport.Outcome allAgree(Decoder d, byte[] head, String... header) throws IOException {
        CoreSupport.Outcome expected = null;
        for (int chunk : CHUNKS) {
            for (int read : READS) {
                CoreSupport.Outcome o = run(d, head, chunk, read, header);
                if (expected == null) {
                    expected = o;
                }
                assertEquals(expected.kind, o.kind, "kind, chunk " + chunk + " read " + read);
                assertEquals(expected.bytes.length, o.bytes.length, "delivered bytes, chunk " + chunk + " read " + read);
                assertArrayEquals(expected.bytes, o.bytes, "chunk " + chunk + " read " + read);
            }
        }
        return expected;
    }

    private static Decoder decoder() throws SpillgateException {
        return Decoder.of(Limits.maxOutput(1L << 26).withoutRatioLimit());
    }

    // Covers: SG-R-RDR-01 SG-R-RDR-05
    @Test
    void gzip_source_error_delivers_the_same_bytes_for_every_read_size_and_chunking() throws Exception {
        byte[] data = payload();
        byte[] full = gzip(data);
        for (int cut : new int[] {full.length / 3, full.length / 2, full.length - 9}) {
            CoreSupport.Outcome o = allAgree(decoder(), Arrays.copyOf(full, cut), "gzip");
            assertEquals(Kind.SOURCE_ERROR, o.kind);
            assertArrayEquals(Arrays.copyOf(data, o.bytes.length), o.bytes, "a prefix of the data");
            if (cut == full.length - 9) {
                assertEquals(data.length, o.bytes.length, "everything but the trailer is delivered");
            } else {
                assertTrue(o.bytes.length > 0);
            }
        }
    }

    // Covers: SG-R-RDR-01 SG-R-RDR-05
    @Test
    void deflate_source_error_delivers_the_same_bytes_for_every_read_size_and_chunking() throws Exception {
        byte[] data = payload();
        byte[] full = zlib(data);
        for (int cut : new int[] {full.length / 3, full.length / 2, full.length - 4}) {
            CoreSupport.Outcome o = allAgree(decoder(), Arrays.copyOf(full, cut), "deflate");
            assertEquals(Kind.SOURCE_ERROR, o.kind);
            assertArrayEquals(Arrays.copyOf(data, o.bytes.length), o.bytes, "a prefix of the data");
            if (cut == full.length - 4) {
                assertEquals(data.length, o.bytes.length);
            }
        }
    }

    // Covers: SG-R-RDR-01 SG-R-RDR-05
    @Test
    void gzip_in_gzip_source_error_delivers_the_same_bytes() throws Exception {
        byte[] data = payload();
        byte[] full = gzip(gzip(data));
        for (int cut : new int[] {full.length / 2, full.length - 9}) {
            CoreSupport.Outcome o = allAgree(decoder(), Arrays.copyOf(full, cut), "gzip", "gzip");
            assertEquals(Kind.SOURCE_ERROR, o.kind);
            assertArrayEquals(Arrays.copyOf(data, o.bytes.length), o.bytes);
        }
    }

    /**
     * A codec that hides the failure of its input and goes on with what it holds, then meets the
     * failure again: what it holds must not be dropped by the guard.
     */
    static final class Swallower implements Codec {
        private final boolean endCleanly;

        Swallower(boolean endCleanly) {
            this.endCleanly = endCleanly;
        }

        @Override
        public CodecReader newReader(CodecInput input) {
            return new CodecReader() {
                private int left = 100;
                private boolean tried;

                @Override
                public int read(byte[] buf, int off, int len) throws IOException {
                    if (!tried) {
                        tried = true;
                        try {
                            input.fill();
                        } catch (IOException swallowed) {
                            // hidden on purpose
                        }
                    }
                    if (left == 0) {
                        if (endCleanly) {
                            return -1;
                        }
                        input.fill(); // meets the failure again
                        return -1;
                    }
                    int n = Math.min(left, len);
                    for (int i = 0; i < n; i++) {
                        buf[off + i] = (byte) (100 - left + i);
                    }
                    left -= n;
                    return n;
                }
            };
        }
    }

    // Covers: SG-R-RDR-01 SG-I-02
    @Test
    void a_swallowed_source_failure_does_not_drop_what_the_codec_still_holds() throws Exception {
        Decoder d = Decoder.builder(Limits.maxOutput(1 << 20).withoutRatioLimit())
                .codec("swallow", new Swallower(false)).build();
        for (int read : new int[] {1, 10, 99, 100, 4096}) {
            CoreSupport.Outcome o = run(d, new byte[0], 1, read, "swallow");
            assertEquals(Kind.SOURCE_ERROR, o.kind, "read " + read);
            assertEquals(100, o.bytes.length, "read " + read);
            for (int i = 0; i < 100; i++) {
                assertEquals((byte) i, o.bytes[i]);
            }
        }
    }

    // Covers: SG-I-02 SG-R-RDR-05
    @Test
    void a_swallowed_source_failure_followed_by_a_clean_end_still_fails_with_that_failure() throws Exception {
        Decoder d = Decoder.builder(Limits.maxOutput(1 << 20).withoutRatioLimit())
                .codec("swallow", new Swallower(true)).build();
        for (int read : new int[] {1, 10, 4096}) {
            CoreSupport.Outcome o = run(d, new byte[0], 1, read, "swallow");
            assertEquals(Kind.SOURCE_ERROR, o.kind, "read " + read);
            assertEquals(100, o.bytes.length, "read " + read);
        }
        assertNotNull(d);
    }
}
