package io.github.felixmiddelhoff.spillgate;

import static io.github.felixmiddelhoff.spillgate.CoreSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Limits (SPEC section 3), decoder construction, codec registration and the exception type. */
class CoreLimitsTest {
    private static void bad(Limits limits) {
        SpillgateException e = assertThrows(SpillgateException.class, () -> EffectiveLimits.from(limits));
        assertEquals(Kind.INVALID_LIMITS, e.kind(), e.getMessage());
        assertEquals(Kind.INVALID_LIMITS, assertThrows(SpillgateException.class, () -> Decoder.of(limits)).kind());
        assertEquals(Kind.INVALID_LIMITS,
                assertThrows(SpillgateException.class, () -> Decoder.builder(limits).build()).kind());
    }

    private static EffectiveLimits good(Limits limits) throws SpillgateException {
        Decoder.of(limits);
        return EffectiveLimits.from(limits);
    }

    // Covers: SG-R-LIM-01 SG-R-API-01
    @Test
    void sg_r_lim_01_max_output_is_required_and_positive() {
        bad(Limits.maxOutput(0));
        bad(Limits.maxOutput(-1));
        bad(Limits.maxOutput(Long.MIN_VALUE));
        bad(Limits.maxOutput(Long.MIN_VALUE + 1));
        bad(Limits.maxOutput(Integer.MIN_VALUE));
        bad(null);
        // invalid limits are a SpillgateException even for the convenience entry points
        assertEquals(Kind.INVALID_LIMITS, assertThrows(SpillgateException.class,
                () -> Decoder.decodeAll(in(new byte[0]), Limits.maxOutput(0))).kind());
        assertEquals(Kind.INVALID_LIMITS, assertThrows(SpillgateException.class,
                () -> Decoder.decodeAll(in(new byte[0]), (Limits) null)).kind());
    }

    // Covers: SG-R-LIM-01 SG-R-LIM-02 SG-R-LIM-07
    @Test
    void sg_r_lim_02_max_output_up_to_2_pow_63_minus_1() throws Exception {
        long[] values = {1, 2, 1 << 20, 0xffffffffL, 1L << 31, 1L << 53, 1L << 62, INT64 - 1, INT64};
        for (long v : values) {
            assertEquals(v, good(Limits.maxOutput(v)).maxOutput);
        }
    }

    // Covers: SG-R-LIM-03 SG-R-LIM-07
    @Test
    void sg_r_lim_03_max_layers_range() throws Exception {
        for (int v : new int[] {0, -1, 17, 100, Integer.MIN_VALUE, Integer.MAX_VALUE}) {
            bad(Limits.maxOutput(1).withMaxLayers(v));
        }
        for (int v : new int[] {1, 2, 5, 15, 16}) {
            assertEquals(v, good(Limits.maxOutput(1).withMaxLayers(v)).maxLayers);
        }
        assertEquals(5, good(Limits.maxOutput(1)).maxLayers);
    }

    // Covers: SG-R-LIM-04 SG-R-LIM-07
    @Test
    void sg_r_lim_04_no_numeric_max_ratio_disables_the_check() throws Exception {
        for (long v : new long[] {0, -1, 1L << 31, (1L << 31) + 1, INT64, Long.MIN_VALUE, Integer.MIN_VALUE}) {
            bad(Limits.maxOutput(1).withMaxRatio(v));
        }
        assertEquals(1, good(Limits.maxOutput(1).withMaxRatio(1)).maxRatio);
        assertEquals((1L << 31) - 1, good(Limits.maxOutput(1).withMaxRatio((1L << 31) - 1)).maxRatio);
        assertEquals(1000, good(Limits.maxOutput(1)).maxRatio);
    }

    // Covers: SG-R-LIM-04 SG-R-RAT-04
    @Test
    void sg_r_lim_04_the_opt_out_is_distinct_and_cannot_be_combined() throws Exception {
        assertEquals(0, good(Limits.maxOutput(1).withoutRatioLimit()).maxRatio);
        bad(Limits.maxOutput(1).withMaxRatio(10).withoutRatioLimit());
        bad(Limits.maxOutput(1).withoutRatioLimit().withMaxRatio(10));
        // a contradicting value is invalid even if the value itself is invalid
        bad(Limits.maxOutput(1).withMaxRatio(0).withoutRatioLimit());
        bad(Limits.maxOutput(1).withoutRatioLimit().withMaxRatio(-5));
    }

    // Covers: SG-R-LIM-05
    @Test
    void sg_r_lim_05_ratio_grace_may_be_zero_never_negative() throws Exception {
        assertEquals(0, good(Limits.maxOutput(1).withRatioGrace(0)).ratioGrace);
        assertEquals(65536, good(Limits.maxOutput(1)).ratioGrace);
        assertEquals(INT64, good(Limits.maxOutput(1).withRatioGrace(INT64)).ratioGrace);
        bad(Limits.maxOutput(1).withRatioGrace(-1));
        bad(Limits.maxOutput(1).withRatioGrace(Long.MIN_VALUE));
        EffectiveLimits e = good(Limits.maxOutput(10).withMaxRatio(2).withRatioGrace(0));
        assertTrue(e.ratioExceeded(3, 1));
        assertFalse(e.ratioExceeded(2, 1));
    }

    // Covers: SG-R-LIM-06
    @Test
    void sg_r_lim_06_max_input_zero_does_not_mean_unlimited() throws Exception {
        bad(Limits.maxOutput(1).withMaxInput(0));
        bad(Limits.maxOutput(1).withMaxInput(-1));
        bad(Limits.maxOutput(1).withMaxInput(Long.MIN_VALUE));
        assertEquals(0, good(Limits.maxOutput(1)).maxInput);
        assertEquals(7, good(Limits.maxOutput(1).withMaxInput(7)).maxInput);
        assertEquals(INT64, good(Limits.maxOutput(1).withMaxInput(INT64)).maxInput);
        // maxInput = Long.MAX_VALUE must not overflow the probe arithmetic
        byte[] data = payload(10_000);
        Decoder d = Decoder.of(Limits.maxOutput(1 << 20).withMaxInput(INT64).withoutRatioLimit());
        try (SpillgateInputStream s = d.reader(in(data))) {
            Outcome o = drain(s, 4096);
            assertNull(o.kind);
            assertArrayEquals(data, o.bytes);
        }
    }

    // Covers: SG-R-LIM-08 SG-R-API-03
    @Test
    void sg_r_lim_08_validated_at_construction_before_any_read() {
        FailAfter source = new FailAfter(new byte[0], () -> new IOException("must not be read"));
        SpillgateException e = assertThrows(SpillgateException.class,
                () -> Decoder.decodeAll(source, Limits.maxOutput(0)));
        assertEquals(Kind.INVALID_LIMITS, e.kind());
        assertEquals(0, source.calls.get());
        Endless endless = new Endless(0);
        e = assertThrows(SpillgateException.class,
                () -> Decoder.decodeAll(endless, Limits.maxOutput(5).withMaxLayers(0)));
        assertEquals(Kind.INVALID_LIMITS, e.kind());
        assertEquals(0, endless.total.get());
        // an invalid decoder never exists, so no codec state was allocated either
        CoreCodecs.Counting counting = new CoreCodecs.Counting(false);
        assertThrows(SpillgateException.class,
                () -> Decoder.builder(Limits.maxOutput(0)).codec("c", counting).build());
        assertEquals(0, counting.created.get());
    }

    // Covers: SG-R-LIM-09
    @Test
    void sg_r_lim_09_limits_are_captured_by_value() throws Exception {
        Limits limits = Limits.maxOutput(100).withoutRatioLimit();
        Decoder decoder = Decoder.of(limits);
        // Limits is immutable: the with... methods return new objects and change nothing
        Limits other = limits.withMaxLayers(0);
        assertNotSame(limits, other);
        assertEquals(100, decoder.decodeAll(in(filled(100, 7))).length);
        SpillgateException e = assertThrows(SpillgateException.class, () -> decoder.decodeAll(in(filled(101, 7))));
        assertEquals(Kind.OUTPUT_LIMIT, e.kind());
        // building from the same Limits object twice gives independent decoders
        Limits shared = Limits.maxOutput(10).withoutRatioLimit();
        Decoder a = Decoder.of(shared);
        Decoder b = Decoder.of(shared.withMaxLayers(1));
        assertEquals(10, a.decodeAll(in(filled(10, 1))).length);
        assertEquals(10, b.decodeAll(in(filled(10, 1))).length);
        assertThrows(SpillgateException.class, () -> Decoder.of(shared.withMaxLayers(99)));
        assertNotNull(Decoder.of(shared));
    }

    // Covers: SG-R-RAT-01
    @Test
    void sg_r_rat_01_the_ratio_boundary_is_exact_and_cannot_overflow() throws Exception {
        EffectiveLimits e = good(Limits.maxOutput(INT64).withMaxRatio(10).withRatioGrace(100));
        assertFalse(e.ratioExceeded(100, 1)); // inside the grace
        assertFalse(e.ratioExceeded(10 * 50, 50)); // exactly at the ratio
        assertTrue(e.ratioExceeded(10 * 50 + 1, 50)); // one byte over
        assertTrue(e.ratioExceeded(101, 0)); // consumed counts as at least 1
        assertFalse(e.ratioExceeded(101, 11)); // 101 <= 110
        assertFalse(e.ratioExceeded(INT64, INT64)); // the product does not wrap
        assertFalse(e.ratioExceeded(INT64, INT64 / 10 + 1)); // 10 * (MAX/10 + 1) overflows
        assertTrue(e.ratioExceeded(INT64, INT64 / 10)); // 10 * (MAX/10) = MAX - 7 < MAX
        assertTrue(e.ratioExceeded(INT64, 1));
        assertTrue(e.ratioExceeded(INT64, (INT64 - 1) / 10 - 1));
        EffectiveLimits max = good(Limits.maxOutput(INT64).withMaxRatio((1L << 31) - 1).withRatioGrace(0));
        assertFalse(max.ratioExceeded(INT64, INT64));
        assertFalse(max.ratioExceeded(INT64, 1L << 33)); // product would be > 2^63
        assertTrue(max.ratioExceeded(INT64, 1L << 31));
        assertFalse(max.ratioExceeded(0, 0));
        assertTrue(max.ratioExceeded(1L << 32, 1));
        EffectiveLimits off = good(Limits.maxOutput(INT64).withoutRatioLimit());
        assertFalse(off.ratioExceeded(INT64, 0));
        // monotone: more consumed never turns a pass into a failure
        EffectiveLimits m = good(Limits.maxOutput(INT64).withMaxRatio(7).withRatioGrace(3));
        Rng rng = new Rng(7);
        for (int i = 0; i < 20_000; i++) {
            long produced = rng.below(1000);
            long consumed = rng.below(200);
            boolean expected = produced > 3 && produced > 7 * Math.max(consumed, 1);
            assertEquals(expected, m.ratioExceeded(produced, consumed), produced + "/" + consumed);
        }
    }

    // -- decoder construction and codec registration ---------------------------------------------------

    // Covers: SG-R-CDC-09
    @Test
    void sg_r_cdc_09_codec_names_are_checked() throws Exception {
        for (String name : new String[] {"identity", "gzip", "deflate", "x-gzip"}) {
            SpillgateException e = assertThrows(SpillgateException.class, () -> build(name), name);
            assertEquals(Kind.INVALID_OPTION, e.kind(), name);
            assertEquals("invalid_option", e.kind().specName());
            assertEquals(500, e.kind().httpStatus());
        }
        // not lower case, not a token
        String[] names = {
            "Gzip", "BR", "", " br", "br;q=1", "a b", "-x", ".x", "é", rep("a", 33), "x\n", "x,y", "bR", "Br", "IDENTITY",
        };
        for (String name : names) {
            assertEquals(Kind.INVALID_OPTION, assertThrows(SpillgateException.class, () -> build(name), name).kind(),
                    "{" + name + "}");
        }
        assertEquals(Kind.INVALID_OPTION, assertThrows(SpillgateException.class, () -> build(null)).kind());
        for (String name : new String[] {"br", "zstd", "x", "a.b_c-d", "9", rep("a", 32)}) {
            build(name);
        }
        // twice
        SpillgateException e = assertThrows(SpillgateException.class, () -> Decoder.builder(Limits.maxOutput(10))
                .codec("br", new CoreCodecs.Pass()).codec("br", new CoreCodecs.Pass()).build());
        assertEquals(Kind.INVALID_OPTION, e.kind());
        // a null codec
        assertEquals(Kind.INVALID_OPTION, assertThrows(SpillgateException.class,
                () -> Decoder.builder(Limits.maxOutput(10)).codec("br", null).build()).kind());
        // errors do not hide invalid limits
        assertEquals(Kind.INVALID_LIMITS, assertThrows(SpillgateException.class, () -> Decoder.builder(Limits.maxOutput(0))
                .codec("gzip", new CoreCodecs.Pass()).build()).kind());
        // the message never carries more than 32 bytes of the name
        e = assertThrows(SpillgateException.class, () -> build(rep("A", 500) + "\n"));
        assertTrue(count(e.toString(), 'A') <= 32);
        assertFalse(e.toString().contains("\n"));
        e = assertThrows(SpillgateException.class, () -> build(rep("Z", 500)));
        assertTrue(count(e.toString(), 'Z') <= 32);
        // the builder can be built more than once and keeps its registrations
        DecoderBuilder b = Decoder.builder(Limits.maxOutput(10)).codec("p", new CoreCodecs.Pass());
        assertNotSame(b.build(), b.build());
        assertSame(b, b.strictZlib(true));
        b.build();
    }

    private static int count(String s, char c) {
        int n = 0;
        for (char x : s.toCharArray()) {
            if (x == c) {
                n++;
            }
        }
        return n;
    }

    private static String rep(String s, int n) {
        return String.join("", java.util.Collections.nCopies(n, s));
    }

    private static Decoder build(String name) throws SpillgateException {
        return Decoder.builder(Limits.maxOutput(10)).codec(name, new CoreCodecs.Pass()).build();
    }

    // Covers: SG-R-CDC-09
    @Test
    void sg_r_cdc_09_registered_codecs_are_per_decoder_not_global() throws Exception {
        Decoder with = decoder(loose());
        Decoder without = Decoder.of(loose());
        assertArrayEquals("abc".getBytes(), run(with, "abc".getBytes(), h("pass"), 16).bytes);
        assertEquals(Kind.UNSUPPORTED_ENCODING, run(without, "abc".getBytes(), h("pass"), 16).kind);
        assertEquals(Kind.UNSUPPORTED_ENCODING, run(without, "abc".getBytes(), h("br"), 16).kind);
    }

    // Covers: SG-R-CDC-09 SG-R-HDR-08
    @Test
    void sg_r_cdc_09_builtin_and_optional_names() throws Exception {
        Decoder plain = Decoder.of(loose());
        for (String header : new String[] {"gzip", "deflate", "x-gzip", "identity", "GZIP", "X-Gzip", "Deflate"}) {
            plain.reader(in(new byte[0]), header).close();
        }
        // the optional codecs are only available when registered
        for (String header : new String[] {"br", "zstd", "compress", "dcb", "dcz", "aes128gcm", "snappy", "deflate64"}) {
            assertEquals(Kind.UNSUPPORTED_ENCODING,
                    assertThrows(SpillgateException.class, () -> plain.reader(in(new byte[0]), header)).kind(), header);
        }
        // registered extension names resolve case-insensitively in the header
        Decoder withBr = Decoder.builder(loose()).codec("br", new CoreCodecs.Pass()).build();
        assertArrayEquals(b(1, 2), run(withBr, b(1, 2), h("BR"), 8).bytes);
        assertArrayEquals(b(1, 2), run(withBr, b(1, 2), h("Br, identity"), 8).bytes);
    }

    // Covers: SG-R-API-01
    @Test
    void sg_r_api_01_there_is_no_decoder_without_limits() throws Exception {
        assertThrows(SpillgateException.class, () -> Decoder.of(Limits.maxOutput(0)));
        assertThrows(SpillgateException.class, () -> Decoder.decodeAll(in(new byte[0]), Limits.maxOutput(0)));
        assertThrows(SpillgateException.class, () -> Decoder.of(null));
        assertArrayEquals("abc".getBytes(), Decoder.decodeAll(in("abc".getBytes()), Limits.maxOutput(3)));
        // no public static or instance entry point takes anything but Limits to decode
        for (java.lang.reflect.Method m : Decoder.class.getDeclaredMethods()) {
            if (java.lang.reflect.Modifier.isPublic(m.getModifiers()) && m.getName().equals("decodeAll")
                    && java.lang.reflect.Modifier.isStatic(m.getModifiers())) {
                assertEquals(Limits.class, m.getParameterTypes()[1]);
            }
        }
        assertEquals(0, Decoder.class.getConstructors().length, "a Decoder is created by of() and builder() only");
    }

    // Covers: SG-R-API-05
    @Test
    void error_kinds_have_names_and_statuses() {
        Object[][] all = {
            {Kind.INVALID_LIMITS, "invalid_limits", 500},
            {Kind.INVALID_OPTION, "invalid_option", 500},
            {Kind.OUTPUT_LIMIT, "output_limit", 413},
            {Kind.RATIO_LIMIT, "ratio_limit", 413},
            {Kind.LAYER_LIMIT, "layer_limit", 413},
            {Kind.INPUT_LIMIT, "input_limit", 413},
            {Kind.MEMORY_LIMIT, "memory_limit", 503},
            {Kind.UNSUPPORTED_ENCODING, "unsupported_encoding", 415},
            {Kind.MALFORMED_ENCODING, "malformed_encoding", 400},
            {Kind.CORRUPT, "corrupt", 400},
            {Kind.INTERNAL, "internal", 500},
            {Kind.CANCELED, "canceled", 503},
            {Kind.SOURCE_ERROR, "source_error", 400},
        };
        Set<Kind> seen = new HashSet<>();
        for (Object[] row : all) {
            Kind kind = (Kind) row[0];
            assertEquals(row[1], kind.specName());
            assertEquals(row[2], kind.httpStatus(), kind.specName());
            seen.add(kind);
        }
        assertEquals(new HashSet<>(Arrays.asList(Kind.values())), seen, "every kind is covered by the table");
        SpillgateException e = new SpillgateException(Kind.OUTPUT_LIMIT, "m", 2, 5, null);
        assertEquals(Kind.OUTPUT_LIMIT, e.kind());
        assertEquals(2, e.layer());
        assertEquals(5, e.limit());
        assertTrue(e.toString().contains("layer 2") && e.toString().contains("limit 5"), e.toString());
        SpillgateException lowest = e.withLayer(3);
        assertEquals(2, lowest.layer(), "the lowest layer wins");
        assertEquals(3, new SpillgateException(Kind.CORRUPT, "m").withLayer(3).layer());
        SpillgateException again = e.repeat();
        assertEquals(e.kind(), again.kind());
        assertEquals(e.layer(), again.layer());
        assertEquals(e.limit(), again.limit());
        assertEquals(e.getMessage(), again.getMessage());
        assertEquals(0, again.getStackTrace().length, "a repeated error pins no stack");
        assertTrue(e instanceof IOException);
    }

    // Covers: SG-R-HDR-13
    @Test
    void quote_escapes_and_bounds() {
        assertEquals("\"abc\"", SpillgateException.quote("abc".getBytes()));
        assertEquals("\"\"", SpillgateException.quote(new byte[0]));
        assertEquals("\"\\x00\\x1b\\x7f\\xff\"", SpillgateException.quote(b(0, 0x1b, 0x7f, 0xff)));
        assertEquals("\"\\\"\\\\\"", SpillgateException.quote("\"\\".getBytes()));
        String q = SpillgateException.quote(filled(33, 'a'));
        assertEquals("\"" + rep("a", 32) + "...\"", q);
        assertEquals("\"" + rep("a", 32) + "\"", SpillgateException.quote(filled(32, 'a')));
        assertTrue(SpillgateException.quote(filled(1_000_000, '\n')).length() < 32 * 4 + 10);
    }

    // Covers: SG-R-RDR-07 SG-R-CDC-09
    @Test
    void decoders_with_different_limits_do_not_share_state() {
        Decoder small = decoder(out(10));
        Decoder big = decoder(out(100));
        assertEquals(Kind.OUTPUT_LIMIT, run(small, new byte[50], h("pass"), 8).kind);
        assertNull(run(big, new byte[50], h("pass"), 8).kind);
        assertEquals(Kind.OUTPUT_LIMIT, run(small, new byte[50], h("pass"), 8).kind);
    }
}
