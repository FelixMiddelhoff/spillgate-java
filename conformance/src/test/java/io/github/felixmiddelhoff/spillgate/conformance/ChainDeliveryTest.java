package io.github.felixmiddelhoff.spillgate.conformance;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.felixmiddelhoff.spillgate.Decoder;
import io.github.felixmiddelhoff.spillgate.Kind;
import io.github.felixmiddelhoff.spillgate.Limits;
import io.github.felixmiddelhoff.spillgate.SpillgateException;
import io.github.felixmiddelhoff.spillgate.SpillgateInputStream;
import io.github.felixmiddelhoff.spillgate.brotli.BrotliCodec;
import io.github.felixmiddelhoff.spillgate.zstd.ZstdCodec;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * SG-R-RDR-01 over chains of real codecs: bytes delivered before an error are independent of the read
 * size and of the source chunking. The streams are the reviewer's repros: they end corrupt, with
 * different byte counts for different read sizes on the old code.
 */
class ChainDeliveryTest {
    private static final int[] CHUNKS = {1, 5, 4096, Integer.MAX_VALUE};
    private static final int[] READS = {1, 7, 4096, 65536, 0};

    private static byte[] resource(String name) throws IOException {
        try (InputStream in = ChainDeliveryTest.class.getResourceAsStream("/regress/" + name)) {
            return in.readAllBytes();
        }
    }

    private static final class Source extends InputStream {
        private final byte[] data;
        private final int chunk;
        private final boolean failAtEnd;
        private int pos;

        Source(byte[] data, int chunk, boolean failAtEnd) {
            this.data = data;
            this.chunk = chunk;
            this.failAtEnd = failAtEnd;
        }

        @Override
        public int read() throws IOException {
            throw new UnsupportedOperationException();
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (pos >= data.length) {
                if (failAtEnd) {
                    throw new IOException("connection reset");
                }
                return -1;
            }
            int n = Math.min(Math.min(len, chunk), data.length - pos);
            System.arraycopy(data, pos, b, off, n);
            pos += n;
            return n;
        }
    }

    private static Decoder decoder() throws SpillgateException {
        return Decoder.builder(Limits.maxOutput(1L << 26).withoutRatioLimit())
                .codec("zstd", ZstdCodec.create(io.github.felixmiddelhoff.spillgate.zstd.ZstdOptions.defaults()))
                .codec("br", BrotliCodec.create(io.github.felixmiddelhoff.spillgate.brotli.BrotliOptions.defaults()))
                .build();
    }

    private record Result(byte[] bytes, Kind kind) {}

    private static Result run(Decoder d, byte[] data, int chunk, boolean failAtEnd, int read, String... header)
            throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        Random rnd = new Random(3);
        byte[] buf = new byte[1 << 17];
        Kind kind = null;
        try (SpillgateInputStream s = d.reader(new Source(data, chunk, failAtEnd), header)) {
            while (true) {
                int n = s.read(buf, 0, read == 0 ? 1 + rnd.nextInt(3000) : read);
                if (n < 0) {
                    break;
                }
                bo.write(buf, 0, n);
            }
        } catch (SpillgateException e) {
            kind = e.kind();
        }
        return new Result(bo.toByteArray(), kind);
    }

    private static Result allAgree(Decoder d, byte[] data, boolean failAtEnd, String... header) throws IOException {
        Result expected = null;
        for (int chunk : CHUNKS) {
            for (int read : READS) {
                Result r = run(d, data, chunk, failAtEnd, read, header);
                if (expected == null) {
                    expected = r;
                }
                String what = Arrays.toString(header) + " chunk " + chunk + " read " + read;
                assertEquals(expected.kind(), r.kind(), what);
                assertEquals(expected.bytes().length, r.bytes().length, what);
                assertArrayEquals(expected.bytes(), r.bytes(), what);
            }
        }
        return expected;
    }

    // Covers: SG-R-RDR-01 SG-I-09
    @Test
    void corrupt_chains_deliver_the_same_bytes_for_every_read_size_and_chunking() throws Exception {
        Decoder d = decoder();
        Result r1 = allAgree(d, resource("chain_1.bin"), false, "gzip", "deflate");
        assertEquals(Kind.CORRUPT, r1.kind());
        Result r2 = allAgree(d, resource("chain_zstd_deflate_gzip.bin"), false, "deflate", "gzip", "zstd");
        assertEquals(Kind.CORRUPT, r2.kind());
    }

    // Covers: SG-R-RDR-01 SG-R-RDR-05
    @Test
    void a_source_failure_inside_a_chain_delivers_the_same_bytes() throws Exception {
        Decoder d = decoder();
        byte[] full = resource("chain_zstd_deflate_gzip.bin");
        for (int cut = 40; cut < full.length; cut += 97) {
            Result r = allAgree(d, Arrays.copyOf(full, cut), true, "deflate", "gzip", "zstd");
            assertEquals(Kind.SOURCE_ERROR, r.kind(), "cut " + cut);
        }
    }
}
