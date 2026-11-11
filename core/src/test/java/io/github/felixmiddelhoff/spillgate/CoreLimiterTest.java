package io.github.felixmiddelhoff.spillgate;

import static io.github.felixmiddelhoff.spillgate.CoreSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** The limiter: output boundary, ratio, input cap (SPEC sections 5 to 7). */
class CoreLimiterTest {
    /** Everything a test needs to know about the end of a read loop. */
    private static Outcome typed(InputStream in, int size) {
        return drain(in, size);
    }

    private static Decoder withCodec(Limits limits, String name, Codec codec) {
        try {
            return Decoder.builder(limits).codec(name, codec).codec("pass", new CoreCodecs.Pass()).build();
        } catch (SpillgateException e) {
            throw new AssertionError(e);
        }
    }

    // -- output boundary ------------------------------------------------------------------------------------

    // Covers: SG-R-LAY-02 SG-R-LAY-03 SG-I-01 SG-R-RDR-01
    @Test
    void sg_r_lay_03_exact_boundary_with_a_clean_end() {
        int[] ns = {0, 1, 2, 3, 99, 100, 4095, 4096, 4097, 65535, 65536, 65537, 70000};
        String[] headers = {"stored", "pass, stored", "pass, pass, stored"};
        for (int n : ns) {
            byte[] payload = data(n);
            byte[] framed = stored(payload, 1000);
            for (String header : headers) {
                for (int size : READ_SIZES) {
                    String where = "n=" + n + " " + header + " size " + size;
                    // maxOutput = n: exactly at the limit is success
                    if (n >= 1) {
                        Outcome o = run(decoder(out(n)), framed, header, size);
                        assertNull(o.kind, where);
                        assertArrayEquals(payload, o.bytes, where);
                    }
                    // maxOutput = n + 1: success
                    Outcome o = run(decoder(out(n + 1L)), framed, header, size);
                    assertNull(o.kind, where + " +1");
                    assertArrayEquals(payload, o.bytes);
                    // maxOutput = n - 1: output_limit after exactly n - 1 bytes
                    if (n >= 2) {
                        o = run(decoder(out(n - 1L)), framed, header, size);
                        assertEquals(Kind.OUTPUT_LIMIT, o.kind, where + " -1");
                        assertArrayEquals(Arrays.copyOf(payload, n - 1), o.bytes, where + " -1");
                    }
                }
            }
        }
    }

    // Covers: SG-R-CDC-10 SG-R-LAY-01 SG-R-LAY-03
    @Test
    void sg_r_lay_03_identity_is_bounded_at_the_same_boundary() {
        for (int n : new int[] {1, 2, 10, 4096, 5000}) {
            byte[] payload = data(n);
            for (String[] header : new String[][] {h(), h("identity"), h("identity, identity")}) {
                for (int size : READ_SIZES) {
                    Outcome o = run(decoder(out(n)), payload, header, size);
                    assertNull(o.kind, "n=" + n + " size " + size);
                    assertEquals(n, o.bytes.length);
                    if (n >= 2) {
                        o = run(decoder(out(n - 1L)), payload, header, size);
                        assertEquals(Kind.OUTPUT_LIMIT, o.kind);
                        assertArrayEquals(Arrays.copyOf(payload, n - 1), o.bytes);
                    }
                }
            }
        }
    }

    // Covers: SG-R-LAY-07 SG-R-LAY-03 SG-R-CDC-06
    @Test
    void sg_r_lay_07_conditions_are_judged_in_stream_order() {
        int n = 300;
        byte[] payload = data(n);
        byte[] good = stored(payload, 100);
        byte[] badTrailer = good.clone();
        badTrailer[badTrailer.length - 1] = '?';
        byte[] truncated = Arrays.copyOf(good, good.length - 2);
        byte[] trailing = Arrays.copyOf(good, good.length + 1);
        trailing[trailing.length - 1] = 1;
        for (int size : READ_SIZES) {
            // a bad trailer after exactly maxOutput bytes is corrupt
            Outcome o = run(decoder(out(n)), badTrailer, "stored", size);
            assertEquals(Kind.CORRUPT, o.kind, "size " + size);
            assertEquals(n, o.bytes.length);
            // a truncated stream after exactly maxOutput bytes is corrupt
            o = run(decoder(out(n)), truncated, "stored", size);
            assertEquals(Kind.CORRUPT, o.kind);
            assertEquals(n, o.bytes.length);
            // trailing bytes after the trailer: corrupt at the boundary
            o = run(decoder(out(n)), trailing, "stored", size);
            assertEquals(Kind.CORRUPT, o.kind);
            // a stream longer than maxOutput is output_limit even if its trailer is bad
            o = run(decoder(out(n - 1L)), badTrailer, "stored", size);
            assertEquals(Kind.OUTPUT_LIMIT, o.kind);
            assertEquals(n - 1, o.bytes.length);
            o = run(decoder(out(n - 1L)), truncated, "stored", size);
            assertEquals(Kind.OUTPUT_LIMIT, o.kind);
            assertEquals(n - 1, o.bytes.length);
            // success must not be reported before the trailer was validated
            o = run(decoder(out(n + 1L)), badTrailer, "stored", size);
            assertEquals(Kind.CORRUPT, o.kind);
            assertEquals(n, o.bytes.length);
            // corruption before the (max+1)th byte is corrupt
            byte[] early = good.clone();
            early[0] = (byte) 0xff; // a chunk of 65280 + 100 bytes declared, then the stream ends: corrupt
            o = run(decoder(out(1 << 20)), early, "stored", size);
            assertEquals(Kind.CORRUPT, o.kind);
            // corrupt right at the end of a chain too
            o = run(decoder(out(n)), badTrailer, "pass, pass, stored", size);
            assertEquals(Kind.CORRUPT, o.kind);
            assertEquals(n, o.bytes.length);
        }
    }

    // Covers: SG-R-LAY-04 SG-I-02
    @Test
    void sg_r_lay_04_a_limit_hit_is_never_a_clean_end() throws Exception {
        Decoder d = decoder(out(1000));
        for (int size : READ_SIZES) {
            Outcome o = typed(d.reader(new Endless(7)), size);
            assertEquals(1000, o.bytes.length);
            assertEquals(Kind.OUTPUT_LIMIT, o.kind);
        }
    }

    // Covers: SG-I-01 SG-R-LAY-02 SG-R-CDC-01 SG-R-MEM-02
    @Test
    void sg_i_01_a_bomb_never_delivers_more_than_the_budget() throws Exception {
        for (long max : new long[] {1, 2, 65535, 65536, 65537, 200_000}) {
            Decoder d = withCodec(out(max), "zeros", new CoreCodecs.Zeros(Long.MAX_VALUE));
            for (int size : READ_SIZES) {
                Outcome o = typed(d.reader(in(new byte[0]), "zeros"), size);
                assertEquals(max, o.bytes.length, "max " + max + " size " + size);
                assertEquals(Kind.OUTPUT_LIMIT, o.kind);
                assertEquals(1, o.layer);
                assertEquals(max, o.limit);
            }
        }
        // a bomb of exactly max bytes is fine
        Decoder d = withCodec(out(70_000), "zeros", new CoreCodecs.Zeros(70_000));
        Outcome o = run(d, new byte[0], "zeros", 9999);
        assertNull(o.kind);
        assertEquals(70_000, o.bytes.length);
        // one more is not
        d = withCodec(out(70_000), "zeros", new CoreCodecs.Zeros(70_001));
        o = run(d, new byte[0], "zeros", 9999);
        assertEquals(Kind.OUTPUT_LIMIT, o.kind);
        assertEquals(70_000, o.bytes.length);
    }

    // Covers: SG-I-01 SG-R-LAY-01 SG-R-LAY-02
    @Test
    void the_budget_is_not_exceeded_for_every_read_pattern_around_the_step_size() {
        for (long max : new long[] {65535, 65536, 65537, 131071, 131072, 131073}) {
            Decoder d = withCodec(out(max), "zeros", new CoreCodecs.Zeros(Long.MAX_VALUE));
            for (int size : new int[] {1, 65535, 65536, 65537, 1 << 17, 1 << 22}) {
                Outcome o = run(d, new byte[0], "zeros", size);
                assertEquals(max, o.bytes.length, "max " + max + " size " + size);
                assertEquals(Kind.OUTPUT_LIMIT, o.kind);
            }
        }
    }

    // Covers: SG-R-MEM-02 SG-R-RAT-02
    @Test
    void max_step_bounds_every_codec_call() throws Exception {
        CoreCodecs.Spy spy = new CoreCodecs.Spy();
        Decoder d = withCodec(out(10L << 20), "spy", spy);
        SpillgateInputStream r = d.reader(in(new byte[0]), "spy");
        byte[] buf = new byte[8 << 20];
        long total = 0;
        while (total < (4 << 20)) {
            total += r.read(buf);
        }
        assertEquals(64 * 1024, spy.largest.get());
        // small reads ask for small steps
        CoreCodecs.Spy small = new CoreCodecs.Spy();
        Decoder d2 = withCodec(out(1000), "spy", small);
        SpillgateInputStream r2 = d2.reader(in(new byte[0]), "spy");
        assertEquals(7, r2.read(new byte[7]));
        assertEquals(7, small.largest.get());
        // the budget limits the step: never a request beyond what is left
        CoreCodecs.Spy last = new CoreCodecs.Spy();
        Decoder d3 = withCodec(out(100), "spy", last);
        drain(d3.reader(in(new byte[0]), "spy"), 1 << 20);
        assertEquals(100, last.largest.get());
    }

    // -- ratio -------------------------------------------------------------------------------------------------------

    private static Decoder ratioDecoder(long ratio, long grace) {
        return decoder(Limits.maxOutput(1L << 30).withMaxRatio(ratio).withRatioGrace(grace));
    }

    // Covers: SG-R-RAT-01 SG-I-02
    @Test
    void sg_r_rat_01_the_ratio_fires_at_the_exact_byte_with_single_byte_reads() throws Exception {
        // expand10: every input byte is 10 output bytes. Ratio 9, grace 0: P=10 > 9*1.
        Outcome o = run(ratioDecoder(9, 0), filled(50, 1), "expand10", 1);
        assertEquals(Kind.RATIO_LIMIT, o.kind);
        assertEquals(10, o.bytes.length);
        // Ratio 10 is exactly the ratio of the codec: P = 10*C at every full input byte, never above
        o = run(ratioDecoder(10, 0), filled(5000, 1), "expand10", 1);
        assertNull(o.kind);
        assertEquals(50_000, o.bytes.length);
        // expand11 against ratio 10 fires at the first byte past 10 * C
        Decoder d = withCodec(Limits.maxOutput(1L << 30).withMaxRatio(10).withRatioGrace(0), "e11", new CoreCodecs.Expand(11));
        o = run(d, filled(50, 1), "e11", 1);
        assertEquals(Kind.RATIO_LIMIT, o.kind);
        assertEquals(11, o.bytes.length);
    }

    // Covers: SG-R-RAT-01 SG-R-LIM-05
    @Test
    void sg_r_rat_01_the_grace_boundary() throws Exception {
        for (long grace : new long[] {0, 50, 100, 101, 150}) {
            Decoder d = withCodec(Limits.maxOutput(1L << 30).withMaxRatio(1).withRatioGrace(grace), "e100", new CoreCodecs.Expand(100));
            Outcome o = run(d, filled(20, 1), "e100", 1);
            assertEquals(Kind.RATIO_LIMIT, o.kind, "grace " + grace);
            // inside the grace nothing is checked; past it P > 1 * max(C, 1) holds from P = 2 on
            assertEquals(Math.max(grace + 1, 2), o.bytes.length, "grace " + grace);
        }
        // the default grace is 64 KiB
        Decoder d = decoder(Limits.maxOutput(1L << 30).withMaxRatio(1));
        Outcome o = run(d, filled(100_000, 1), "expand10", 1);
        assertEquals(Kind.RATIO_LIMIT, o.kind);
        assertEquals(65_537, o.bytes.length);
        // output exactly at the grace is never a ratio error, whatever the ratio
        Decoder g = withCodec(Limits.maxOutput(1L << 30).withMaxRatio(1).withRatioGrace(1000), "z", new CoreCodecs.Zeros(1000));
        o = run(g, new byte[0], "z", 1 << 16);
        assertNull(o.kind);
        assertEquals(1000, o.bytes.length);
    }

    // Covers: SG-R-RAT-01
    @Test
    void sg_r_rat_01_consumed_counts_as_at_least_one() {
        // A bomb that consumes nothing is judged against max(C, 1).
        Decoder d = withCodec(Limits.maxOutput(1L << 30), "zeros", new CoreCodecs.Zeros(Long.MAX_VALUE));
        Outcome o = typed(streamOf(d, new byte[0], "zeros"), 1);
        assertEquals(Kind.RATIO_LIMIT, o.kind);
        assertEquals(1, o.layer);
        assertEquals(1000, o.limit);
        // default grace 65536, ratio 1000 * 1: fires on the first byte past the grace
        assertEquals(65_537, o.bytes.length);
    }

    private static SpillgateInputStream streamOf(Decoder d, byte[] data, String... header) {
        try {
            return d.reader(in(data), header);
        } catch (SpillgateException e) {
            throw new AssertionError(e);
        }
    }

    // Covers: SG-R-RAT-02 SG-R-INP-02
    @Test
    void sg_r_rat_02_read_ahead_gives_no_credit() throws Exception {
        // The whole 4 KiB source is fetched at once, but the codec consumes one byte per 1001 outputs.
        Decoder d = withCodec(Limits.maxOutput(1L << 30).withMaxRatio(1000).withRatioGrace(0), "e1001", new CoreCodecs.Expand(1001));
        Outcome o = typed(d.reader(new Chunked(filled(4096, 1), 4096), "e1001"), 1);
        assertEquals(Kind.RATIO_LIMIT, o.kind);
        assertEquals(1001, o.bytes.length);
        // the same at exactly the ratio never fires
        Decoder d2 = withCodec(Limits.maxOutput(1L << 30).withMaxRatio(1000).withRatioGrace(0), "e1000", new CoreCodecs.Expand(1000));
        o = typed(d2.reader(new Chunked(filled(4096, 1), 4096), "e1000"), 1 << 16);
        assertNull(o.kind);
        assertEquals(4096 * 1000, o.bytes.length);
    }

    // Covers: SG-R-RAT-01 SG-I-02 SG-R-API-05
    @Test
    void sg_r_rat_01_the_ratio_names_layer_and_limit() {
        Decoder d = decoder(Limits.maxOutput(1L << 30).withRatioGrace(0));
        Outcome o = typed(streamOf(d, filled(10, 1), "expand2000"), 1 << 16);
        assertEquals(Kind.RATIO_LIMIT, o.kind);
        assertEquals(1, o.layer);
        assertEquals(1000, o.limit);
        // bytes that broke the ratio are delivered first, the error follows (SG-R-LAY-07)
        assertTrue(o.bytes.length > 0 && o.bytes.length <= 65536, "" + o.bytes.length);
    }

    // Covers: SG-R-RAT-03 SG-R-LAY-01
    @Test
    void sg_r_rat_03_end_to_end_ratio_without_a_failing_layer() {
        // Two layers of 10x each are fine alone (limit 50) but 100x end to end.
        Decoder d = decoder(Limits.maxOutput(1L << 30).withMaxRatio(50).withRatioGrace(0));
        Outcome o = typed(streamOf(d, filled(5000, 1), "expand10, expand10"), 1);
        assertEquals(Kind.RATIO_LIMIT, o.kind);
        assertEquals(0, o.layer, "an end-to-end error has no layer");
        assertEquals(50, o.limit);
        assertTrue(o.bytes.length < 5000 * 100);
        // with a ratio of 100 it never fires
        d = decoder(Limits.maxOutput(1L << 30).withMaxRatio(100).withRatioGrace(0));
        o = typed(streamOf(d, filled(500, 1), "expand10, expand10"), 1 << 16);
        assertNull(o.kind);
        assertEquals(50_000, o.bytes.length);
    }

    // Covers: SG-R-RAT-01 SG-R-LAY-01
    @Test
    void sg_r_rat_01_an_intermediate_layer_names_itself() {
        Decoder d = decoder(Limits.maxOutput(1L << 30).withRatioGrace(0));
        // layer 1 = expand2000 (listed last) fails; layer 2 shrinks, so only layer 1 can be blamed
        Outcome o = typed(streamOf(d, filled(10, 1), "shrink10, expand2000"), 4096);
        assertEquals(Kind.RATIO_LIMIT, o.kind);
        assertEquals(1, o.layer);
        // with a pass-through on top the same ratio is reported, by the layer or by the whole chain
        o = typed(streamOf(d, filled(10, 1), "pass, expand2000"), 4096);
        assertEquals(Kind.RATIO_LIMIT, o.kind);
        assertTrue(o.layer <= 1);
    }

    // Covers: SG-R-RAT-04 SG-R-LIM-07
    @Test
    void sg_r_rat_04_the_opt_out_leaves_the_other_limits_in_force() throws Exception {
        Decoder d = withCodec(out(200_000), "zeros", new CoreCodecs.Zeros(Long.MAX_VALUE));
        Outcome o = typed(streamOf(d, new byte[0], "zeros"), 1 << 16);
        assertEquals(200_000, o.bytes.length);
        assertEquals(Kind.OUTPUT_LIMIT, o.kind);
        d = withCodec(out(1L << 30).withMaxInput(10), "e", new CoreCodecs.Expand(10_000));
        o = run(d, filled(11, 1), "e", 1 << 16);
        assertEquals(Kind.INPUT_LIMIT, o.kind);
        d = withCodec(out(1L << 30).withMaxLayers(1), "e", new CoreCodecs.Expand(10_000));
        Decoder fd = d;
        assertEquals(Kind.LAYER_LIMIT, assertThrows(SpillgateException.class, () -> fd.reader(in(new byte[0]), "e, e")).kind());
        // without the opt-out, the same bomb is a ratio error
        Decoder on = withCodec(Limits.maxOutput(1L << 30), "e", new CoreCodecs.Expand(10_000));
        assertEquals(Kind.RATIO_LIMIT, run(on, filled(100, 1), "e", 1 << 16).kind);
        assertNull(run(d, filled(100, 1), "e", 1 << 16).kind);
    }

    // Covers: SG-R-RAT-03 SG-R-RAT-01
    @Test
    void a_single_layer_ratio_equals_the_end_to_end_ratio() {
        // For a one layer chain the layer fires first and names itself, never the chain.
        Decoder d = decoder(Limits.maxOutput(1L << 30).withMaxRatio(5).withRatioGrace(0));
        Outcome o = typed(streamOf(d, filled(10, 1), "expand10"), 1);
        assertEquals(1, o.layer);
        // identity: P = C, a ratio of 1 never fires even for maxRatio 1
        d = decoder(Limits.maxOutput(1L << 30).withMaxRatio(1).withRatioGrace(0));
        o = run(d, data(100_000), h(), 4096);
        assertNull(o.kind);
        assertEquals(100_000, o.bytes.length);
        o = run(d, data(100_000), h("identity, identity"), 4096);
        assertNull(o.kind);
    }

    // Covers: SG-R-RAT-01 SG-R-RDR-01
    @Test
    void the_ratio_outcome_class_does_not_depend_on_read_sizes_and_the_prefix_is_the_same_stream() {
        Decoder d = decoder(Limits.maxOutput(1L << 30).withMaxRatio(50).withRatioGrace(100));
        byte[] input = filled(2000, 7);
        Outcome reference = run(d, input, "expand1000", 4096);
        assertEquals(Kind.RATIO_LIMIT, reference.kind);
        for (int size : new int[] {1, 3, 1000, 65536, 1 << 20}) {
            Outcome o = run(d, input, "expand1000", size);
            assertEquals(Kind.RATIO_LIMIT, o.kind, "size " + size);
            int n = Math.min(o.bytes.length, reference.bytes.length);
            assertArrayEquals(Arrays.copyOf(reference.bytes, n), Arrays.copyOf(o.bytes, n));
            assertTrue(o.bytes.length <= 1L << 30);
        }
    }

    // -- input cap ------------------------------------------------------------------------------------------------------

    // Covers: SG-R-INP-01 SG-R-INP-02
    @Test
    void sg_r_inp_01_the_input_limit_is_exact_and_asks_for_one_probe_byte_at_most() throws Exception {
        for (long max : new long[] {1, 2, 100, 4095, 4096, 4097, 10_000}) {
            for (int step : new int[] {1, 1000, 4096, 1 << 20}) {
                Decoder d = decoder(out(1L << 30).withMaxInput(max));
                // plenty of bytes in the source
                Chunked source = new Chunked(filled(100_000, 3), step);
                Outcome o = typed(d.reader(source, "pass"), 1 << 16);
                String where = "max " + max + " step " + step;
                assertEquals(Kind.INPUT_LIMIT, o.kind, where);
                assertEquals(1, o.layer);
                assertEquals(max, o.limit);
                assertEquals(max, o.bytes.length, "the codec got exactly maxInput bytes");
                assertTrue(source.largestRequest.get() <= max + 1, "asked for " + source.largestRequest.get() + " for " + where);
                assertTrue(source.bytes.get() <= max + 1, "got " + source.bytes.get() + " for " + where);
                // exactly max bytes in the source is fine
                Chunked exact = new Chunked(filled((int) max, 3), step);
                o = typed(d.reader(exact, "pass"), 1 << 16);
                assertNull(o.kind, where);
                assertEquals(max, o.bytes.length);
                assertEquals(max, exact.bytes.get());
                assertTrue(exact.largestRequest.get() <= max + 1, "no request beyond the probe");
                // one more byte than max is the violation
                Chunked over = new Chunked(filled((int) max + 1, 3), step);
                o = typed(d.reader(over, "pass"), 1 << 16);
                assertEquals(Kind.INPUT_LIMIT, o.kind, where);
                assertEquals(max, o.bytes.length);
                assertTrue(over.bytes.get() <= max + 1);
            }
        }
    }

    // Covers: SG-R-INP-02
    @Test
    void sg_r_inp_02_the_total_of_all_requests_never_exceeds_max_input_plus_one() throws Exception {
        for (long max : new long[] {1, 3, 4095, 4096, 4097, 8191, 8192, 8193, 12345}) {
            for (String header : new String[] {"pass", "pass, pass", "bytes", "shrink10", "expand10"}) {
                long[] total = {0};
                InputStream endless = new InputStream() {
                    @Override
                    public int read() {
                        total[0]++;
                        return 1;
                    }

                    @Override
                    public int read(byte[] b, int off, int len) {
                        total[0] += len; // what was *requested*
                        Arrays.fill(b, off, off + len, (byte) 1);
                        return len;
                    }
                };
                Decoder d = decoder(out(1L << 30).withMaxInput(max));
                Outcome o = typed(d.reader(endless, header), 1 << 16);
                assertEquals(Kind.INPUT_LIMIT, o.kind, header + " " + max);
                assertTrue(total[0] <= max + 1, header + " max " + max + ": requested " + total[0]);
            }
        }
    }

    // Covers: SG-R-INP-01
    @Test
    void sg_r_inp_01_the_cap_applies_to_identity_chains() throws Exception {
        Decoder d = decoder(out(1L << 30).withMaxInput(10));
        for (String[] header : new String[][] {h(), h("identity")}) {
            Outcome o = run(d, filled(10, 1), header, 3);
            assertNull(o.kind);
            assertEquals(10, o.bytes.length);
            o = run(d, filled(11, 1), header, 3);
            assertEquals(Kind.INPUT_LIMIT, o.kind);
            assertEquals(10, o.bytes.length);
        }
    }

    // Covers: SG-R-INP-01
    @Test
    void sg_r_inp_01_a_stream_that_fits_is_unaffected_by_what_follows_it() throws Exception {
        Decoder d = withCodec(out(1L << 30).withMaxInput(10), "take10", new CoreCodecs.TakeN(10));
        Chunked source = new Chunked(filled(500, 8), 100);
        Outcome o = typed(d.reader(source, "take10"), 100);
        assertNull(o.kind);
        assertArrayEquals(filled(10, 8), o.bytes);
        assertTrue(source.bytes.get() <= 11 && source.largestRequest.get() <= 11);
        // a codec stream of 10 bytes: take 11 needs the 11th byte -> input_limit
        Decoder d2 = withCodec(out(1L << 30).withMaxInput(10), "take11", new CoreCodecs.TakeN(11));
        Outcome o2 = run(d2, filled(500, 8), "take11", 100);
        assertEquals(Kind.INPUT_LIMIT, o2.kind);
        assertEquals(10, o2.bytes.length);
    }

    // Covers: SG-R-INP-01 SG-R-LAY-01
    @Test
    void sg_r_inp_01_the_cap_applies_to_the_source_only_not_to_lower_layers() {
        Decoder d = decoder(out(1L << 30).withMaxInput(10));
        // layer 1 = expand10 reads 10 source bytes and produces 100, layer 2 consumes those 100
        Outcome o = run(d, filled(10, 2), "pass, expand10", 7);
        assertNull(o.kind);
        assertEquals(100, o.bytes.length);
        o = run(d, filled(11, 2), "pass, expand10", 7);
        assertEquals(Kind.INPUT_LIMIT, o.kind);
        assertEquals(100, o.bytes.length);
        assertEquals(1, o.layer);
    }

    // Covers: SG-R-INP-02 SG-R-LAY-09
    @Test
    void sg_r_inp_02_an_endless_source_is_never_over_read() throws Exception {
        for (long max : new long[] {1, 100, 5000}) {
            Endless endless = new Endless(1);
            Decoder d = decoder(out(1L << 30).withMaxInput(max));
            Outcome o = typed(d.reader(endless, "pass, pass"), 1 << 16);
            assertEquals(Kind.INPUT_LIMIT, o.kind);
            assertTrue(endless.total.get() <= max + 1, endless.total.get() + " for max " + max);
        }
    }

    // Covers: SG-R-INP-01 SG-R-RAT-02
    @Test
    void sg_r_inp_01_input_accounting_is_by_consumption_not_by_fetch() {
        // expand10 consumes 1 byte per 10 outputs; maxInput 5 lets it read exactly 5 bytes = 50 outputs
        Decoder d = decoder(out(1L << 30).withMaxInput(5));
        Outcome o = run(d, filled(4096, 9), "expand10", 1);
        assertEquals(Kind.INPUT_LIMIT, o.kind);
        assertEquals(50, o.bytes.length);
        o = run(d, filled(5, 9), "expand10", 1);
        assertNull(o.kind);
        assertEquals(50, o.bytes.length);
    }

    // Covers: SG-R-INP-01 SG-R-CDC-01
    @Test
    void sg_r_inp_01_a_codec_cannot_consume_more_than_it_was_shown() throws Exception {
        Decoder d = withCodec(out(1L << 30).withMaxInput(10), "greedy", new CoreCodecs.Greedy());
        Chunked source = new Chunked(filled(1000, 1), 1000);
        Outcome o = typed(d.reader(source, "greedy"), 100);
        assertEquals(Kind.INPUT_LIMIT, o.kind);
        assertTrue(source.bytes.get() <= 11);
        // without a cap a greedy codec simply uses up its input, and the ratio sees the true count
        Decoder open = withCodec(out(1L << 30), "greedy", new CoreCodecs.Greedy());
        Outcome ok = run(open, filled(1000, 1), "greedy", 100);
        assertNull(ok.kind);
        assertEquals(100, ok.bytes.length, "one read of 100 bytes, which used up the whole window");
    }

    // Covers: SG-R-INP-01
    @Test
    void a_codec_consuming_negative_or_zero_changes_nothing() {
        Codec odd = in -> (buf, off, len) -> {
            int a = in.fill();
            if (a == 0) {
                return -1;
            }
            in.consume(-5);
            in.consume(0);
            int n = Math.min(a, len);
            System.arraycopy(in.buffer(), in.position(), buf, off, n);
            in.consume(n);
            return n;
        };
        Decoder d = withCodec(out(1000), "odd", odd);
        // consume(1000) after consume(n) must not skip input: it is clamped to what was shown (0 left)
        Outcome o = run(d, data(100), "odd", 7);
        assertNull(o.kind);
        assertArrayEquals(data(100), o.bytes);
    }

    // Covers: SG-R-INP-01 SG-R-INP-02
    @Test
    void the_input_probe_is_a_single_byte_at_the_boundary() throws Exception {
        // the source holds exactly maxInput bytes in one chunk: the next fetch asks for exactly 1 byte
        Decoder d = decoder(out(1L << 30).withMaxInput(4096));
        Chunked source = new Chunked(filled(4096, 1), 4096);
        Outcome o = typed(d.reader(source, "pass"), 1 << 16);
        assertNull(o.kind);
        assertEquals(4096, source.bytes.get());
        assertEquals(4096 + 1, source.requested.get(), "one 4096-byte request and then one probe byte");
        assertEquals(2, source.calls.get());
    }

    // Covers: SG-R-INP-01
    @Test
    void sg_r_inp_01_the_input_cap_with_a_huge_value_never_overflows() throws Exception {
        for (long max : new long[] {Long.MAX_VALUE, Long.MAX_VALUE - 1, 1L << 62, 1L << 40}) {
            Decoder d = decoder(out(1L << 30).withMaxInput(max));
            Chunked source = new Chunked(data(10_000), 3000);
            Outcome o = typed(d.reader(source, "pass"), 1 << 12);
            assertNull(o.kind, "max " + max);
            assertArrayEquals(data(10_000), o.bytes);
            assertTrue(source.largestRequest.get() <= 4096);
        }
    }

    // Covers: SG-R-LAY-02 SG-I-01
    @Test
    void whatever_the_source_chunking_the_output_never_exceeds_the_budget() throws IOException {
        Decoder d = decoder(out(1000));
        for (int step : new int[] {1, 2, 3, 999, 1000, 1001, 4096}) {
            ByteArrayOutputStream collected = new ByteArrayOutputStream();
            Outcome o = typed(d.reader(new Chunked(data(5000), step), "pass, pass"), 77);
            collected.write(o.bytes);
            assertEquals(Kind.OUTPUT_LIMIT, o.kind);
            assertEquals(1000, collected.size());
        }
    }
}
