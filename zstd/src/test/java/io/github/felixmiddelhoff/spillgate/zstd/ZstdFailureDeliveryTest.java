package io.github.felixmiddelhoff.spillgate.zstd;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

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
import org.junit.jupiter.api.Test;

/** SG-R-RDR-01 for zstd: a failing source must not cost the bytes the decoder already holds. */
class ZstdFailureDeliveryTest {
    private static final class Source extends InputStream {
        private final byte[] head;
        private final int chunk;
        private int pos;

        Source(byte[] head, int chunk) {
            this.head = head;
            this.chunk = chunk;
        }

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
    }

    private static byte[] run(Decoder d, byte[] head, int chunk, int read) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        Random rnd = new Random(7);
        byte[] buf = new byte[1 << 20];
        try (SpillgateInputStream s = d.reader(new Source(head, chunk), "zstd")) {
            while (true) {
                int n = s.read(buf, 0, read == 0 ? 1 + rnd.nextInt(3000) : read);
                if (n < 0) {
                    throw new AssertionError("a clean end although the source failed");
                }
                bo.write(buf, 0, n);
            }
        } catch (SpillgateException e) {
            assertEquals(Kind.SOURCE_ERROR, e.kind());
        }
        return bo.toByteArray();
    }

    // Covers: SG-R-RDR-01 SG-R-RDR-05
    @Test
    void source_error_before_the_checksum_delivers_everything_for_every_read_size() throws Exception {
        byte[] data = new byte[100000];
        Random r = new Random(1);
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) ('a' + r.nextInt(4));
        }
        byte[] frame = Z.checksummed(data, 3);
        byte[] head = Arrays.copyOf(frame, frame.length - 4);
        Decoder d = Decoder.builder(Limits.maxOutput(1L << 26).withoutRatioLimit())
                .codec("zstd", ZstdCodec.create(ZstdOptions.defaults())).build();
        for (int chunk : new int[] {1, 5, 4096, Integer.MAX_VALUE}) {
            for (int read : new int[] {65536, 0, 3, 1}) {
                byte[] got = run(d, head, chunk, read);
                assertEquals(data.length, got.length, "chunk " + chunk + " read " + read);
                assertArrayEquals(data, got);
            }
        }
    }

    // Covers: SG-R-RDR-01 SG-R-RDR-05
    @Test
    void source_error_in_the_middle_delivers_the_same_bytes_for_every_read_size() throws Exception {
        byte[] data = Z.mixed();
        byte[] frame = Z.checksummed(data, 3);
        Decoder d = Decoder.builder(Limits.maxOutput(1L << 26).withoutRatioLimit())
                .codec("zstd", ZstdCodec.create(ZstdOptions.defaults())).build();
        for (int cut : new int[] {frame.length / 4, frame.length / 2, frame.length - 1}) {
            byte[] head = Arrays.copyOf(frame, cut);
            byte[] expected = run(d, head, Integer.MAX_VALUE, 65536);
            for (int chunk : new int[] {1, 5, 4096}) {
                for (int read : new int[] {0, 3, 1}) {
                    assertArrayEquals(expected, run(d, head, chunk, read), "cut " + cut + " chunk " + chunk + " read " + read);
                }
            }
        }
    }
}
