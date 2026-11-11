package io.github.felixmiddelhoff.spillgate.brotli;

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

/**
 * SG-R-INP-01 for brotli: the decoder buffers up to 256 bytes ahead, so the byte after MaxInput is
 * often fetched without being used. Looking at it must not be taking it. Every case runs against
 * every source shape and read pattern (port of the Go input_probe tests).
 */
class BrotliInputProbeTest {
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
                    .codec("br", BrotliCodec.create(BrotliOptions.defaults()))
                    .build();
            try (SpillgateInputStream in = d.reader(source, "br")) {
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

    static byte[] concat(byte[] a, byte[] b) {
        byte[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
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
        byte[] random = new byte[700];
        new java.util.Random(6).nextBytes(random);
        byte[] text = "probe the byte after the limit. ".repeat(300).getBytes();
        List<Stream> out = new ArrayList<>();
        out.add(new Stream("stored", random, Support.stored(random, 16)));
        out.add(new Stream("stored text", text, Support.stored(text, 22)));
        for (String name : new String[] {"text.q5.w16", "prose.q11.w24", "noise.q0.w10"}) {
            out.add(new Stream("golden " + name, Support.resource(name.substring(0, name.indexOf('.')) + ".raw"),
                    Support.resource(name + ".br")));
        }
        return out;
    }

    // Covers: SG-R-INP-01 SG-R-INP-02 SG-R-CDC-04
    @Test
    void data_after_a_complete_brotli_stream_is_corrupt_not_input_limit() {
        for (Stream s : streams()) {
            long len = s.bytes.length;
            byte[][] tails = {{0}, new byte[1000], "trailing garbage that is longer than the probe".getBytes()};
            for (byte[] tail : tails) {
                byte[] input = concat(s.bytes, tail);
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
    void a_brotli_stream_of_exactly_max_input_bytes_is_fine() {
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
    void a_brotli_stream_cut_at_the_limit_is_input_limit_if_the_source_goes_on_and_corrupt_if_it_ends() {
        for (Stream s : streams()) {
            int len = s.bytes.length;
            int[] cuts = {1, 2, 5, 9, 10, 11, 37, len / 3, len / 2, len - 9, len - 5, len - 2, len - 1};
            byte[] more = concat(s.bytes, new byte[50]);
            for (int k : cuts) {
                if (k <= 0 || k >= len) {
                    continue;
                }
                byte[] ends = Arrays.copyOf(s.bytes, k);
                byte[] garbage = concat(Arrays.copyOf(s.bytes, k), new byte[50]);
                Arrays.fill(garbage, k, garbage.length, (byte) 0xA5);
                forEachRun((shape, pattern, where) -> {
                    String msg = s.name + " cut " + k + " of " + len + " " + where;
                    assertEquals("corrupt", decode(ends, k, shape, pattern).kind, msg + ": the source ends at the limit");
                    Result r = decode(more, k, shape, pattern);
                    assertEquals("input_limit", r.kind, msg + ": the source goes on");
                    assertTrue(r.handed <= k + 1, msg + ": handed " + r.handed);
                    // garbage in place of the rest: the byte after the limit is taken if the stream needs it
                    assertEquals("input_limit", decode(garbage, k, shape, pattern).kind, msg + ": garbage behind the limit");
                });
            }
        }
    }

    // Covers: SG-R-INP-01
    @Test
    void the_output_delivered_before_trailing_data_is_a_prefix_of_the_payload() {
        Stream s = streams().get(1);
        byte[] input = concat(s.bytes, new byte[10]);
        forEachRun((shape, pattern, where) -> {
            Result r = decode(input, s.bytes.length, shape, pattern);
            assertEquals("corrupt", r.kind, where);
            assertTrue(r.bytes.length <= s.plain.length, where);
            assertArrayEquals(Arrays.copyOf(s.plain, r.bytes.length), r.bytes, where);
        });
    }
}
