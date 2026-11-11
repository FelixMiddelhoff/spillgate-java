package io.github.felixmiddelhoff.spillgate;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.Deflater;
import org.junit.jupiter.api.Test;

/**
 * SG-R-INP-01 as written: a codec TAKING the MaxInput+1-th byte is input_limit, looking at it is not.
 * Every case runs against every source shape and every read pattern. Port of the Go tests
 * input_probe_test.go and meter_probe_test.go.
 */
class CoreInputProbeTest {
    // -- sources, patterns, driver -----------------------------------------------------------------------------------

    /** Hands out at most {@code limit.applyAsInt(call)} bytes per read and counts what it handed out. */
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

    static final String[] SHAPE_NAMES = {"whole", "one-byte", "half", "chunks-of-7", "irregular"};

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

    static final int[][] PATTERNS = {{1}, {3}, {4096}, {1 << 20}, {1, 7, 4096, 1}};

    static final class Result {
        Kind kind;
        byte[] bytes;
        long handed;

        String name() {
            return kind == null ? "ok" : kind.name().toLowerCase();
        }
    }

    static Decoder decoder(long maxInput) {
        try {
            return Decoder.of(Limits.maxOutput(1L << 30).withoutRatioLimit().withMaxInput(maxInput));
        } catch (SpillgateException e) {
            throw new AssertionError(e);
        }
    }

    static Result decode(Decoder d, byte[] stream, int shape, int[] pattern, String... header) {
        Shaped source = shape(shape, stream);
        Result r = new Result();
        ByteArrayOutputStream got = new ByteArrayOutputStream();
        try (SpillgateInputStream in = d.reader(source, header)) {
            for (int i = 0; ; i++) {
                byte[] buf = new byte[pattern[i % pattern.length]];
                int n;
                try {
                    n = in.read(buf);
                } catch (SpillgateException e) {
                    r.kind = e.kind();
                    // the terminal condition is sticky
                    try {
                        in.read(buf);
                        throw new AssertionError("read after a terminal error succeeded");
                    } catch (SpillgateException again) {
                        assertEquals(e.kind(), again.kind(), "sticky");
                    }
                    break;
                }
                if (n < 0) {
                    break;
                }
                got.write(buf, 0, n);
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
        for (int shape = 0; shape < SHAPE_NAMES.length; shape++) {
            for (int[] pattern : PATTERNS) {
                check.run(shape, pattern, SHAPE_NAMES[shape] + Arrays.toString(pattern));
            }
        }
    }

    // -- streams -----------------------------------------------------------------------------------------------------

    static final class Stream {
        final String name;
        final String encoding;
        final byte[] plain;
        final byte[] bytes;

        Stream(String name, String encoding, byte[] plain, byte[] bytes) {
            this.name = name;
            this.encoding = encoding;
            this.plain = plain;
            this.bytes = bytes;
        }
    }

    static List<Stream> streams() {
        byte[] text = DeflateTestKit.text(9000, 5);
        byte[] random = DeflateTestKit.random(700, 6);
        List<Stream> out = new ArrayList<>();
        out.add(new Stream("gzip", "gzip", text, DeflateTestKit.gzip(text)));
        out.add(new Stream("gzip stored", "gzip", random, gzipStored(random)));
        byte[] both = DeflateTestKit.concat(text, random);
        out.add(new Stream("gzip two members", "gzip", both,
                DeflateTestKit.concat(DeflateTestKit.gzip(text), DeflateTestKit.gzip(random))));
        out.add(new Stream("zlib", "deflate", text, DeflateTestKit.zlib(text)));
        out.add(new Stream("zlib stored", "deflate", random,
                DeflateTestKit.deflate(random, Deflater.NO_COMPRESSION, Deflater.DEFAULT_STRATEGY, false)));
        out.add(new Stream("raw deflate", "deflate", text, DeflateTestKit.raw(text)));
        out.add(new Stream("raw deflate stored", "deflate", random,
                DeflateTestKit.deflate(random, Deflater.NO_COMPRESSION, Deflater.DEFAULT_STRATEGY, true)));
        return out;
    }

    static byte[] gzipStored(byte[] data) {
        byte[] body = DeflateTestKit.deflate(data, Deflater.NO_COMPRESSION, Deflater.DEFAULT_STRATEGY, true);
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(data);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(new byte[] {0x1f, (byte) 0x8b, 8, 0, 0, 0, 0, 0, 0, (byte) 0xff}, 0, 10);
        out.write(body, 0, body.length);
        for (int i = 0; i < 4; i++) {
            out.write((int) (crc.getValue() >> (8 * i)) & 0xff);
        }
        for (int i = 0; i < 4; i++) {
            out.write((data.length >> (8 * i)) & 0xff);
        }
        return out.toByteArray();
    }

    // -- tests -------------------------------------------------------------------------------------------------------

    // Covers: SG-R-INP-01 SG-R-INP-02 SG-R-CDC-02 SG-R-CDC-03
    @Test
    void data_after_a_complete_stream_is_corrupt_not_input_limit_whatever_the_probe_is() {
        for (Stream s : streams()) {
            long len = s.bytes.length;
            byte[][] tails = {{0}, new byte[1000], "trailing garbage that is longer than the probe".getBytes()};
            for (byte[] tail : tails) {
                byte[] input = DeflateTestKit.concat(s.bytes, tail);
                forEachRun((shape, pattern, where) -> {
                    for (long max : new long[] {len, len + 1, len + 5}) {
                        Result r = decode(decoder(max), input, shape, pattern, s.encoding);
                        String msg = s.name + " tail " + tail.length + " max " + max + " " + where;
                        assertEquals("corrupt", r.name(), msg);
                        assertTrue(r.handed <= max + 1, msg + ": the source handed out " + r.handed);
                    }
                });
            }
        }
    }

    // Covers: SG-R-INP-01
    @Test
    void a_stream_of_exactly_max_input_bytes_with_nothing_after_it_is_fine() {
        for (Stream s : streams()) {
            long len = s.bytes.length;
            forEachRun((shape, pattern, where) -> {
                for (long max : new long[] {len, len + 1}) {
                    Result r = decode(decoder(max), s.bytes, shape, pattern, s.encoding);
                    assertEquals("ok", r.name(), s.name + " max " + max + " " + where);
                    assertArrayEquals(s.plain, r.bytes, s.name + " " + where);
                }
            });
        }
    }

    // Covers: SG-R-INP-01 SG-R-INP-02 SG-R-CDC-06
    @Test
    void a_stream_cut_at_the_limit_is_input_limit_if_the_source_goes_on_and_corrupt_if_it_ends() {
        for (Stream s : streams()) {
            int len = s.bytes.length;
            int[] cuts = {1, 5, 9, 10, 11, len / 3, len / 2, len - 9, len - 5, len - 1};
            byte[] more = DeflateTestKit.concat(s.bytes, new byte[50]);
            for (int k : cuts) {
                if (k <= 0 || k >= len) {
                    continue;
                }
                byte[] garbage = DeflateTestKit.concat(Arrays.copyOf(s.bytes, k), filled(50, 0xA5));
                byte[] ends = Arrays.copyOf(s.bytes, k);
                forEachRun((shape, pattern, where) -> {
                    String msg = s.name + " cut " + k + " of " + len + " " + where;
                    assertEquals("corrupt", decode(decoder(k), ends, shape, pattern, s.encoding).name(),
                            msg + ": the source ends at the limit");
                    Result r = decode(decoder(k), more, shape, pattern, s.encoding);
                    assertEquals("input_limit", r.name(), msg + ": the source goes on");
                    assertTrue(r.handed <= k + 1, msg + ": handed " + r.handed);
                    assertEquals("input_limit", decode(decoder(k), garbage, shape, pattern, s.encoding).name(),
                            msg + ": garbage behind the limit is taken");
                });
            }
        }
    }

    // Covers: SG-R-INP-01
    @Test
    void a_byte_after_a_gzip_member_that_could_begin_another_member_is_taken() {
        Stream s = streams().get(0);
        long len = s.bytes.length;
        byte[] input = DeflateTestKit.concat(s.bytes, new byte[] {0x1f});
        forEachRun((shape, pattern, where) -> {
            assertEquals("input_limit", decode(decoder(len), input, shape, pattern, "gzip").name(), where);
            assertEquals("corrupt", decode(decoder(len + 1), input, shape, pattern, "gzip").name(), where);
        });
    }

    // Covers: SG-R-INP-01 SG-R-CDC-10
    @Test
    void identity_takes_everything_it_passes_and_stops_after_exactly_max_input_bytes() {
        byte[] body = DeflateTestKit.random(300, 9);
        for (String[] header : new String[][] {{}, {"identity"}}) {
            forEachRun((shape, pattern, where) -> {
                long len = body.length - 1;
                Result r = decode(decoder(len), body, shape, pattern, header);
                assertEquals("input_limit", r.name(), where);
                assertArrayEquals(Arrays.copyOf(body, (int) len), r.bytes, where);
                assertTrue(r.handed <= len + 1, where);
                byte[] longer = DeflateTestKit.concat(body, new byte[1000]);
                r = decode(decoder(body.length), longer, shape, pattern, header);
                assertEquals("input_limit", r.name(), where);
                assertEquals(body.length, r.bytes.length, where);
                r = decode(decoder(body.length), body, shape, pattern, header);
                assertEquals("ok", r.name(), where);
                assertArrayEquals(body, r.bytes, where);
            });
        }
    }

    // Covers: SG-R-INP-01 SG-R-INP-02
    @Test
    void in_a_chain_only_layer_one_carries_max_input() {
        byte[] zeros = new byte[100_000];
        byte[] inner = gzipStored(zeros);
        byte[] outer = DeflateTestKit.gzip(inner);
        assertTrue(outer.length * 10 < inner.length, "the outer layer expands: " + outer.length + " to " + inner.length);
        long len = outer.length;
        forEachRun((shape, pattern, where) -> {
            Result r = decode(decoder(len), outer, shape, pattern, "gzip", "gzip");
            assertEquals("ok", r.name(), where);
            assertEquals(zeros.length, r.bytes.length, where);
            for (byte[] tail : new byte[][] {{0}, new byte[500]}) {
                for (long max : new long[] {len, len + 1}) {
                    r = decode(decoder(max), DeflateTestKit.concat(outer, tail), shape, pattern, "gzip", "gzip");
                    assertEquals("corrupt", r.name(), where + " tail " + tail.length + " max " + max);
                    assertTrue(r.handed <= max + 1, where);
                }
            }
            assertEquals("input_limit", decode(decoder(len - 1), outer, shape, pattern, "gzip", "gzip").name(), where);
        });
        // identity in front of a codec
        assertEquals("corrupt",
                decode(decoder(len), DeflateTestKit.concat(outer, new byte[] {0}), 0, new int[] {4096}, "identity", "gzip", "gzip")
                        .name());
    }

    // Covers: SG-R-INP-01
    @Test
    void a_source_that_ends_exactly_at_the_limit_is_the_end_of_the_input() {
        Stream s = streams().get(0);
        int len = s.bytes.length;
        byte[] shortSource = Arrays.copyOf(s.bytes, len - 1);
        forEachRun((shape, pattern, where) ->
                assertEquals("corrupt", decode(decoder(len - 1), shortSource, shape, pattern, "gzip").name(), where));
    }

    // -- the end-of-step check with a custom codec -------------------------------------------------------------------

    static final int MAX = 5;

    /** A codec whose reader runs {@code step} on every call and returns what it returns. */
    interface Step {
        int run(CodecInput in, byte[] buf, int off, int len) throws IOException;
    }

    static void take(CodecInput in, int n) throws IOException {
        while (n > 0) {
            int avail = in.fill();
            if (avail == 0) {
                throw new SpillgateException(Kind.CORRUPT, "ended while taking");
            }
            int k = Math.min(avail, n);
            in.consume(k);
            n -= k;
        }
    }

    static Result runStep(Step step, int shape) {
        try {
            Decoder d = Decoder.builder(Limits.maxOutput(1 << 20).withoutRatioLimit().withMaxInput(MAX))
                    .codec("x", in -> (buf, off, len) -> step.run(in, buf, off, len))
                    .build();
            return decode(d, DeflateTestKit.random(100, 3), shape, new int[] {64}, "x");
        } catch (SpillgateException e) {
            throw new AssertionError(e);
        }
    }

    // Covers: SG-R-INP-01 SG-R-RAT-02
    @Test
    void taking_the_probe_is_checked_at_the_end_of_every_codec_step_whatever_the_step_returned() {
        Object[][] cases = {
            {"takes max+1 bytes and returns data", (Step) (in, b, o, l) -> { take(in, MAX + 1); b[o] = 1; return 1; }, "input_limit"},
            {"takes max+1 bytes and ends cleanly", (Step) (in, b, o, l) -> { take(in, MAX + 1); return -1; }, "input_limit"},
            {"takes max+1 bytes and reports corrupt", (Step) (in, b, o, l) -> {
                take(in, MAX + 1);
                throw new SpillgateException(Kind.CORRUPT, "made up");
            }, "input_limit"},
            {"takes max+1 bytes and asks for more", (Step) (in, b, o, l) -> {
                take(in, MAX + 1);
                in.fill();
                return -1;
            }, "input_limit"},
            {"takes max bytes and ends cleanly", (Step) (in, b, o, l) -> { take(in, MAX); return -1; }, "ok"},
            {"is shown the probe alone and only looks at it", (Step) (in, b, o, l) -> {
                assertTrue(in.fill() <= MAX, "the probe is not shown together with the bytes before it");
                take(in, MAX);
                assertEquals(1, in.fill(), "the probe comes alone");
                return -1;
            }, "ok"},
            {"finds trailing data behind the probe and reports it", (Step) (in, b, o, l) -> {
                take(in, MAX);
                assertEquals(1, in.fill());
                throw new SpillgateException(Kind.CORRUPT, "trailing data");
            }, "corrupt"},
        };
        for (Object[] c : cases) {
            for (int shape = 0; shape < SHAPE_NAMES.length; shape++) {
                Result r = runStep((Step) c[1], shape);
                String msg = c[0] + " / " + SHAPE_NAMES[shape];
                assertEquals(c[2], r.name(), msg);
                assertTrue(r.handed <= MAX + 1, msg + ": handed " + r.handed);
                if ("input_limit".equals(c[2])) {
                    assertEquals(0, r.bytes.length, msg + ": the violation came before this step's output");
                }
            }
        }
    }

    // Covers: SG-R-INP-01
    @Test
    void a_codec_that_needs_the_probe_to_go_on_ends_in_input_limit_not_corrupt() {
        // needs 6 bytes, the source holds 100: it must take byte 6
        Result r = runStep((in, b, o, l) -> {
            take(in, MAX + 1);
            return -1;
        }, 1);
        assertEquals("input_limit", r.name());
        // the source really ends at MAX: truncated
        try {
            Decoder d = Decoder.builder(Limits.maxOutput(1 << 20).withoutRatioLimit().withMaxInput(MAX))
                    .codec("x", in -> (buf, off, len) -> {
                        take(in, MAX + 1);
                        return -1;
                    })
                    .build();
            assertEquals("corrupt", decode(d, DeflateTestKit.random(MAX, 3), 1, new int[] {64}, "x").name());
        } catch (SpillgateException e) {
            throw new AssertionError(e);
        }
    }

    private static byte[] filled(int n, int v) {
        byte[] b = new byte[n];
        Arrays.fill(b, (byte) v);
        return b;
    }
}
