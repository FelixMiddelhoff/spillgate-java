package io.github.felixmiddelhoff.spillgate.zstd;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.felixmiddelhoff.spillgate.Decoder;
import io.github.felixmiddelhoff.spillgate.Limits;
import io.github.felixmiddelhoff.spillgate.SpillgateException;
import io.github.felixmiddelhoff.spillgate.SpillgateInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** SG-R-INP-01 for zstd: data after a complete frame is corrupt and its first byte is only looked at. */
class ZstdInputProbeTest {
    static final class Shaped extends InputStream {
        private final byte[] data;
        private final int[] sizes;
        private int pos;
        private int call;
        long handed;

        Shaped(byte[] data, int... sizes) {
            this.data = data;
            this.sizes = sizes;
        }

        @Override
        public int read() {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n < 0 ? -1 : one[0] & 0xff;
        }

        @Override
        public int read(byte[] b, int off, int len) {
            if (len == 0) {
                return 0;
            }
            if (pos >= data.length) {
                return -1;
            }
            int cap = sizes.length == 0 ? Integer.MAX_VALUE : sizes[call++ % sizes.length];
            int n = Math.min(Math.min(len, cap), data.length - pos);
            System.arraycopy(data, pos, b, off, n);
            pos += n;
            handed += n;
            return n;
        }
    }

    static final int SHAPES = 5;
    static final int[][] PATTERNS = {{1}, {3}, {4096}, {1 << 20}, {1, 7, 4096, 1}};

    static Shaped shape(int shape, byte[] data) {
        switch (shape) {
            case 0:
                return new Shaped(data);
            case 1:
                return new Shaped(data, 1);
            case 2:
                return new Shaped(data, Math.max(1, data.length / 2), Math.max(1, data.length / 3), 1 << 20);
            case 3:
                return new Shaped(data, 7);
            default:
                return new Shaped(data, 1, 2, 5, 13, 64, 3);
        }
    }

    static final class Result {
        String kind = "ok";
        byte[] bytes;
        long handed;
    }

    static Result decode(byte[] stream, long maxInput, int shape, int[] pattern) {
        Result r = new Result();
        Shaped source = shape(shape, stream);
        ByteArrayOutputStream got = new ByteArrayOutputStream();
        try {
            Decoder d = Decoder.builder(Limits.maxOutput(1L << 30).withoutRatioLimit().withMaxInput(maxInput))
                    .codec("zstd", Z.codec(Z.DEFAULT_MAX_WINDOW))
                    .build();
            try (SpillgateInputStream in = d.reader(source, "zstd")) {
                for (int i = 0; ; i++) {
                    byte[] buf = new byte[pattern[i % pattern.length]];
                    int n;
                    try {
                        n = in.read(buf);
                    } catch (SpillgateException e) {
                        r.kind = e.kind().name().toLowerCase();
                        break;
                    }
                    if (n < 0) {
                        break;
                    }
                    got.write(buf, 0, n);
                }
            }
        } catch (IOException e) {
            throw new AssertionError(e);
        }
        r.bytes = got.toByteArray();
        r.handed = source.handed;
        return r;
    }

    interface Check {
        void run(int shape, int[] pattern, String where);
    }

    static void forEachRun(Check check) {
        for (int shape = 0; shape < SHAPES; shape++) {
            for (int[] pattern : PATTERNS) {
                check.run(shape, pattern, "shape " + shape + Arrays.toString(pattern));
            }
        }
    }

    static final class Stream {
        final String name;
        final byte[] plain;
        final byte[] bytes;

        Stream(String name, byte[] plain, byte[] bytes) {
            this.name = name;
            this.plain = plain;
            this.bytes = bytes;
        }
    }

    static List<Stream> streams() {
        byte[] random = Z.data(6, 700);
        byte[] text = Z.ascii("probe the byte after the limit. ".repeat(300));
        List<Stream> out = new ArrayList<>();
        out.add(new Stream("compressed", text, Z.compress(text)));
        out.add(new Stream("checksummed", text, Z.checksummed(text, 3)));
        out.add(new Stream("raw blocks", random, Z.concat(Z.windowHeader(17), Z.rawBlocks(random, 1000, true))));
        out.add(new Stream("two frames", Z.concat(text, random), Z.concat(Z.compress(text), Z.compress(random))));
        out.add(new Stream("skippable and frame", text, Z.concat(Z.skippable(9), Z.compress(text))));
        return out;
    }

    // Covers: SG-R-INP-01 SG-R-INP-02 SG-R-CDC-05
    @Test
    void data_after_a_complete_zstd_frame_is_corrupt_not_input_limit() {
        for (Stream s : streams()) {
            long len = s.bytes.length;
            byte[][] tails = {{0}, new byte[1000], Z.ascii("trailing garbage that is longer than the probe")};
            for (byte[] tail : tails) {
                byte[] input = Z.concat(s.bytes, tail);
                forEachRun((shape, pattern, where) -> {
                    for (long max : new long[] {len, len + 1, len + 5}) {
                        Result r = decode(input, max, shape, pattern);
                        String msg = s.name + " tail " + tail.length + " max " + max + " " + where;
                        assertEquals("corrupt", r.kind, msg);
                        assertTrue(r.handed <= max + 1, msg + ": handed " + r.handed);
                    }
                });
            }
        }
    }

    // Covers: SG-R-INP-01
    @Test
    void a_zstd_stream_of_exactly_max_input_bytes_is_fine() {
        for (Stream s : streams()) {
            long len = s.bytes.length;
            forEachRun((shape, pattern, where) -> {
                for (long max : new long[] {len, len + 1}) {
                    Result r = decode(s.bytes, max, shape, pattern);
                    assertEquals("ok", r.kind, s.name + " max " + max + " " + where);
                    assertArrayEquals(s.plain, r.bytes, s.name + " " + where);
                }
            });
        }
    }

    // Covers: SG-R-INP-01 SG-R-INP-02 SG-R-CDC-06
    @Test
    void a_zstd_stream_cut_at_the_limit_is_input_limit_if_the_source_goes_on_and_corrupt_if_it_ends() {
        for (Stream s : streams()) {
            int len = s.bytes.length;
            int[] cuts = {1, 2, 5, 9, 10, 11, len / 3, len / 2, len - 9, len - 5, len - 1};
            byte[] more = Z.concat(s.bytes, new byte[50]);
            for (int k : cuts) {
                if (k <= 0 || k >= len) {
                    continue;
                }
                byte[] ends = Arrays.copyOf(s.bytes, k);
                forEachRun((shape, pattern, where) -> {
                    String msg = s.name + " cut " + k + " of " + len + " " + where;
                    assertEquals("corrupt", decode(ends, k, shape, pattern).kind, msg + ": the source ends at the limit");
                    Result r = decode(more, k, shape, pattern);
                    assertEquals("input_limit", r.kind, msg + ": the source goes on");
                    assertTrue(r.handed <= k + 1, msg + ": handed " + r.handed);
                });
            }
        }
    }

    // A first byte after a frame that could begin another frame (0x28, 0x50..0x5f) is taken: the codec
    // cannot know the source ends without asking for byte MaxInput+2. input_limit for MaxInput = L,
    // corrupt (a cut magic number) for L+1.
    //
    // Covers: SG-R-INP-01
    @Test
    void a_byte_after_a_zstd_frame_that_could_begin_another_frame_is_taken() {
        Stream s = streams().get(0);
        long len = s.bytes.length;
        for (int first : new int[] {0x28, 0x50, 0x55, 0x5f}) {
            byte[] input = Z.concat(s.bytes, new byte[] {(byte) first});
            forEachRun((shape, pattern, where) -> {
                assertEquals("input_limit", decode(input, len, shape, pattern).kind, "first " + first + " " + where);
                assertEquals("corrupt", decode(input, len + 1, shape, pattern).kind, "first " + first + " " + where);
            });
        }
    }
}
