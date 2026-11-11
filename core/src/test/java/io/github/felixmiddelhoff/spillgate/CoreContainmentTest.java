package io.github.felixmiddelhoff.spillgate;

import static io.github.felixmiddelhoff.spillgate.CoreSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * Misbehaving codecs and failure containment (SG-I-01, SG-I-02, SG-I-05, SG-R-CDC-08, SG-R-RDR-01).
 */
class CoreContainmentTest {
    private static Decoder with(Limits limits, Object... namesAndCodecs) {
        try {
            DecoderBuilder b = Decoder.builder(limits).codec("pass", new CoreCodecs.Pass());
            for (int i = 0; i < namesAndCodecs.length; i += 2) {
                b.codec((String) namesAndCodecs[i], (Codec) namesAndCodecs[i + 1]);
            }
            return b.build();
        } catch (SpillgateException e) {
            throw new AssertionError(e);
        }
    }

    private static Outcome kinds(Decoder d, byte[] data, String header, int size) {
        Outcome o = run(d, data, header, size);
        assertNotNull(o.kind, header + ": expected an error, got the end");
        return o;
    }

    /** A throwable whose every method is hostile. */
    private static final class HostileException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        HostileException() {
            super("secret\n\u001b[31mFORGED LOG LINE " + "A".repeat(5000));
        }

        @Override
        public String getMessage() {
            throw new IllegalStateException("getMessage is hostile");
        }

        @Override
        public String toString() {
            throw new IllegalStateException("toString is hostile");
        }

        @Override
        public String getLocalizedMessage() {
            throw new IllegalStateException("getLocalizedMessage is hostile");
        }

        @Override
        public synchronized Throwable getCause() {
            return null;
        }
    }

    // Covers: SG-R-CDC-08 SG-I-05 SG-I-03
    @Test
    void sg_r_cdc_08_a_throwing_codec_is_internal_and_sticky() throws Exception {
        for (int after : new int[] {0, 1, 100, 70_000}) {
            for (String header : new String[] {"panic", "pass, panic"}) {
                Decoder d = with(loose(), "panic", new CoreCodecs.Panicker(after));
                SpillgateInputStream r = open(d, new byte[] {'x'}, header);
                ByteArrayOutputStream got = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                SpillgateException e;
                while (true) {
                    try {
                        int n = r.read(buf);
                        assertNotEquals(-1, n, "expected an error, got the end");
                        got.write(buf, 0, n);
                    } catch (SpillgateException ex) {
                        e = ex;
                        break;
                    }
                }
                assertEquals(Kind.INTERNAL, e.kind(), header + " after " + after);
                assertEquals(after, got.size());
                for (int i = 0; i < 5; i++) {
                    assertEquals(Kind.INTERNAL, assertThrows(SpillgateException.class, () -> r.read(new byte[8])).kind());
                }
            }
        }
    }

    private static SpillgateInputStream open(Decoder d, byte[] data, String... header) {
        try {
            return d.reader(in(data), header);
        } catch (SpillgateException e) {
            throw new AssertionError(e);
        }
    }

    // Covers: SG-R-CDC-08 SG-R-API-05
    @Test
    void sg_r_cdc_08_a_failure_in_the_second_layer_names_layer_two() {
        Decoder d = with(loose(), "panic", new CoreCodecs.Panicker(5));
        SpillgateException e = assertThrows(SpillgateException.class, () -> open(d, "hello".getBytes(), "panic, pass").readAllBytes());
        assertEquals(Kind.INTERNAL, e.kind());
        assertEquals(2, e.layer());
        assertTrue(e.getMessage().length() < 150, e.getMessage());
    }

    // Covers: SG-R-CDC-08 SG-I-05
    @Test
    void sg_r_cdc_08_every_kind_of_throwable_is_contained() {
        Object[][] throwables = {
            {"RuntimeException", (Supplier<Throwable>) () -> new RuntimeException("boom")},
            {"IllegalStateException", (Supplier<Throwable>) () -> new IllegalStateException("boom")},
            {"ArrayIndexOutOfBounds", (Supplier<Throwable>) () -> new ArrayIndexOutOfBoundsException(7)},
            {"NullPointerException", (Supplier<Throwable>) () -> new NullPointerException()},
            {"ArithmeticException", (Supplier<Throwable>) () -> new ArithmeticException("/ by zero")},
            {"ClassCastException", (Supplier<Throwable>) () -> new ClassCastException("x")},
            {"UnsupportedOperationException", (Supplier<Throwable>) () -> new UnsupportedOperationException()},
            {"StackOverflowError", (Supplier<Throwable>) () -> new StackOverflowError()},
            {"AssertionError", (Supplier<Throwable>) () -> new AssertionError("assert")},
            {"LinkageError", (Supplier<Throwable>) () -> new LinkageError("link")},
            {"NoClassDefFoundError", (Supplier<Throwable>) () -> new NoClassDefFoundError("x")},
            {"ExceptionInInitializerError", (Supplier<Throwable>) () -> new ExceptionInInitializerError("x")},
            {"IOException (plain)", (Supplier<Throwable>) () -> new IOException("plain")},
            {"InterruptedIOException", (Supplier<Throwable>) () -> new java.io.InterruptedIOException("irq")},
            {"EOFException", (Supplier<Throwable>) () -> new java.io.EOFException()},
            {"Hostile", (Supplier<Throwable>) HostileException::new},
            {"Hostile cause", (Supplier<Throwable>) () -> new RuntimeException("x", new HostileException())},
        };
        for (Object[] row : throwables) {
            @SuppressWarnings("unchecked")
            Supplier<Throwable> make = (Supplier<Throwable>) row[1];
            Decoder d = with(loose(), "t", new CoreCodecs.Throws(make));
            for (String header : new String[] {"t", "pass, t", "t, pass"}) {
                SpillgateInputStream r = open(d, new byte[] {1}, header);
                SpillgateException e = assertThrows(SpillgateException.class, () -> r.read(new byte[8]), row[0] + " " + header);
                assertEquals(Kind.INTERNAL, e.kind(), row[0] + " " + header);
                // the message and toString never touch the hostile exception and carry none of its text
                String shown = e.toString();
                assertFalse(shown.contains("secret"), shown);
                assertFalse(shown.contains("FORGED"), shown);
                assertTrue(shown.length() < 200, shown);
                assertTrue(shown.chars().noneMatch(Character::isISOControl), shown);
                // sticky, and the stack of the codec is not pinned in repeats
                SpillgateException again = assertThrows(SpillgateException.class, () -> r.read(new byte[8]));
                assertEquals(Kind.INTERNAL, again.kind());
                assertEquals(e.getMessage(), again.getMessage());
            }
            // decodeAll reports it too
            SpillgateException e = assertThrows(SpillgateException.class, () -> d.decodeAll(in(new byte[] {1}), "t"));
            assertEquals(Kind.INTERNAL, e.kind());
        }
    }

    // Covers: SG-R-CDC-08 SG-I-05
    @Test
    void sg_r_cdc_08_the_layer_of_a_failure_is_the_layer_that_failed() {
        Decoder d = with(loose(), "t", new CoreCodecs.Throws(() -> new RuntimeException("x")));
        // "pass, t": t is listed first, so it is the last layer applied (layer 2)
        assertEquals(2, assertThrows(SpillgateException.class, () -> open(d, new byte[] {1}, "t, pass").read(new byte[4])).layer());
        assertEquals(1, assertThrows(SpillgateException.class, () -> open(d, new byte[] {1}, "pass, t").read(new byte[4])).layer());
        assertEquals(1, assertThrows(SpillgateException.class, () -> open(d, new byte[] {1}, "t").read(new byte[4])).layer());
    }

    // Covers: SG-R-CDC-08 SG-I-05
    @Test
    void sg_r_cdc_08_a_codec_that_throws_while_it_is_constructed_is_contained() throws Exception {
        for (Supplier<Throwable> make : Arrays.<Supplier<Throwable>>asList(
                () -> new IllegalStateException("no"), StackOverflowError::new, () -> new LinkageError("x"),
                HostileException::new, () -> new AssertionError("a"))) {
            Decoder d = with(loose(), "boom", new CoreCodecs.ThrowsOnNew(make));
            SpillgateException e = assertThrows(SpillgateException.class, () -> d.reader(in(new byte[0]), "boom"));
            assertEquals(Kind.INTERNAL, e.kind());
            assertEquals(1, e.layer());
            assertTrue(e.toString().length() < 200);
            e = assertThrows(SpillgateException.class, () -> d.reader(in(new byte[0]), "boom, pass"));
            assertEquals(2, e.layer());
            e = assertThrows(SpillgateException.class, () -> d.reader(in(new byte[0]), "pass, boom"));
            assertEquals(1, e.layer());
            // the decoder is fine afterwards
            assertArrayEquals("ok".getBytes(), run(d, "ok".getBytes(), "pass", 8).bytes);
            assertEquals(Kind.INTERNAL, assertThrows(SpillgateException.class, () -> d.decodeAll(in(new byte[0]), "boom")).kind());
        }
        // a codec that returns no reader
        Decoder d = with(loose(), "null", new CoreCodecs.NullReader());
        SpillgateException e = assertThrows(SpillgateException.class, () -> d.reader(in(new byte[0]), "null"));
        assertEquals(Kind.INTERNAL, e.kind());
    }

    // Covers: SG-R-CDC-08 SG-R-LAY-02 SG-I-01
    @Test
    void sg_r_cdc_08_a_codec_returning_more_than_requested_is_internal_and_leaks_nothing() throws Exception {
        for (int extra : new int[] {1, 5, 1 << 20, Integer.MAX_VALUE - 100}) {
            Decoder d = with(out(1000), "over", new CoreCodecs.OverReport(extra));
            for (int size : new int[] {1, 16, 4096, 1 << 20}) {
                SpillgateInputStream r = open(d, new byte[0], "over");
                byte[] buf = new byte[size];
                Arrays.fill(buf, (byte) 0x55);
                SpillgateException e = assertThrows(SpillgateException.class, () -> r.read(buf));
                assertEquals(Kind.INTERNAL, e.kind(), "extra " + extra + " size " + size);
                for (byte x : buf) {
                    assertEquals((byte) 0x55, x, "nothing was copied into the callers buffer");
                }
                assertEquals(Kind.INTERNAL, assertThrows(SpillgateException.class, () -> r.read(buf)).kind());
            }
            // in the probe step as well: after exactly the budget a codec that over-reports is internal
            Decoder tight = with(out(1), "over", new CoreCodecs.OverReport(extra));
            assertEquals(Kind.INTERNAL, run(tight, new byte[0], "over", 1).kind);
        }
    }

    // Covers: SG-R-CDC-08
    @Test
    void sg_r_cdc_08_a_codec_returning_a_negative_count_other_than_minus_one_is_internal() {
        for (int value : new int[] {-2, -3, -1000, Integer.MIN_VALUE}) {
            Decoder d = with(loose(), "neg", new CoreCodecs.Returns(value));
            Outcome o = kinds(d, new byte[0], "neg", 100);
            assertEquals(Kind.INTERNAL, o.kind, "" + value);
            assertEquals(0, o.bytes.length);
        }
        // in the probe step: after the budget, a negative is internal, not the end
        Decoder d = with(out(1), "neg", new CoreCodecs.Zeros(Long.MAX_VALUE), "neg2", new CoreCodecs.Returns(-7));
        assertEquals(Kind.INTERNAL, run(d, new byte[0], "neg2", 1).kind);
    }

    // Covers: SG-R-CDC-08
    @Test
    void sg_r_cdc_08_a_codec_that_never_makes_progress_is_internal_not_a_hang() {
        Decoder d = with(loose(), "zero", new CoreCodecs.Returns(0));
        long start = System.nanoTime();
        Outcome o = kinds(d, new byte[0], "zero", 100);
        assertEquals(Kind.INTERNAL, o.kind);
        assertEquals(0, o.bytes.length);
        assertTrue(System.nanoTime() - start < 5_000_000_000L);
    }

    // Covers: SG-R-CDC-08 SG-I-01
    @Test
    void sg_r_cdc_08_a_codec_that_writes_outside_its_range_is_internal() throws Exception {
        for (int amount : new int[] {1, 2, 15, 16, 17, 100, 5000}) {
            Decoder d = with(loose(), "wild", new CoreCodecs.WritesOutside(amount));
            for (int size : new int[] {1, 7, 100, 4096, 70_000}) {
                SpillgateInputStream r = open(d, new byte[0], "wild");
                byte[] buf = new byte[size + 20];
                Arrays.fill(buf, (byte) 0x55);
                SpillgateException e = assertThrows(SpillgateException.class, () -> r.read(buf, 5, size),
                        "amount " + amount + " size " + size);
                assertEquals(Kind.INTERNAL, e.kind());
                // the callers array was never handed to the codec: not a byte of it changed
                for (byte x : buf) {
                    assertEquals((byte) 0x55, x);
                }
            }
        }
        // a codec that ignores len and fills the whole array it was given
        Decoder d = with(loose(), "wild", new CoreCodecs.IgnoresLen());
        for (int size : new int[] {1, 10, 1000, 65536, 1 << 20}) {
            SpillgateInputStream r = open(d, new byte[0], "wild");
            byte[] buf = new byte[size + 100];
            Arrays.fill(buf, (byte) 0x55);
            SpillgateException e = assertThrows(SpillgateException.class, () -> r.read(buf, 50, size), "size " + size);
            assertEquals(Kind.INTERNAL, e.kind());
            for (byte x : buf) {
                assertEquals((byte) 0x55, x);
            }
        }
    }

    // Covers: SG-R-CDC-08 SG-R-RDR-06
    @Test
    void sg_r_cdc_08_a_throwing_close_is_contained_and_every_codec_is_still_closed() throws Exception {
        CoreCodecs.Counting hostile = new CoreCodecs.Counting(true);
        Decoder d = with(loose(), "h", hostile);
        // clean end releases (and the exception of close() does not surface)
        SpillgateInputStream r = open(d, "abc".getBytes(), "h, h, h");
        assertArrayEquals("abc".getBytes(), r.readAllBytes());
        assertEquals(3, hostile.closed.get());
        r.close();
        r.close();
        assertEquals(3, hostile.closed.get());
        // close() under a running stream
        SpillgateInputStream r2 = open(d, "abcdef".getBytes(), "h, h");
        r2.read(new byte[1]);
        r2.close();
        assertEquals(5, hostile.closed.get());
        // an error path
        Decoder tight = with(out(1), "h", hostile);
        SpillgateInputStream r3 = open(tight, "abcdef".getBytes(), "h");
        assertEquals(Kind.OUTPUT_LIMIT, assertThrows(SpillgateException.class, r3::readAllBytes).kind());
        assertEquals(6, hostile.closed.get());
    }

    // Covers: SG-R-CDC-08 SG-R-API-05
    @Test
    void sg_r_cdc_08_codec_errors_keep_their_kind_and_get_the_layer() {
        for (Kind kind : new Kind[] {Kind.CORRUPT, Kind.MEMORY_LIMIT, Kind.INTERNAL, Kind.UNSUPPORTED_ENCODING}) {
            Decoder d = with(loose(), "f", new CoreCodecs.Fails(kind));
            SpillgateException e = assertThrows(SpillgateException.class, () -> d.decodeAll(in(new byte[] {1}), "f"));
            assertEquals(kind, e.kind());
            assertEquals(1, e.layer());
            e = assertThrows(SpillgateException.class, () -> d.decodeAll(in(new byte[] {1}), "f, pass"));
            assertEquals(kind, e.kind());
            assertEquals(2, e.layer());
        }
    }

    // Covers: SG-R-LAY-05 SG-I-03 SG-R-LAY-09
    @Test
    void a_codec_is_not_called_again_after_its_end_or_failure() throws Exception {
        CoreCodecs.Ends ends = new CoreCodecs.Ends(10);
        Decoder d = with(loose(), "ends", ends);
        SpillgateInputStream r = open(d, new byte[0], "ends");
        assertArrayEquals(filled(10, 4), r.readAllBytes());
        for (int i = 0; i < 10; i++) {
            assertEquals(-1, r.read(new byte[4]));
        }
        assertEquals(0, ends.callsAfterEnd.get());
        // at the output limit the codec is probed once and then never again
        CoreCodecs.Ends probed = new CoreCodecs.Ends(10);
        Decoder tight = with(out(10), "ends", probed);
        SpillgateInputStream r2 = open(tight, new byte[0], "ends");
        assertArrayEquals(filled(10, 4), r2.readAllBytes());
        for (int i = 0; i < 10; i++) {
            assertEquals(-1, r2.read(new byte[4]));
        }
        assertEquals(0, probed.callsAfterEnd.get());
    }

    // Covers: SG-R-CDC-06
    @Test
    void a_codec_that_ends_early_is_a_clean_end() {
        // The limiter cannot know what the format wanted: a codec that returns -1 ends the stream.
        // (Codecs themselves must report truncation as corrupt; see the stored codec tests.)
        Decoder d = with(loose(), "ends", new CoreCodecs.Ends(0));
        Outcome o = run(d, new byte[0], "ends", 8);
        assertNull(o.kind);
        assertEquals(0, o.bytes.length);
    }

    // Covers: SG-R-CDC-01 SG-R-CDC-09 SG-I-01
    @Test
    void sg_r_cdc_01_a_codec_gets_no_private_path_around_the_limits() {
        // output budget, ratio, input cap and layer count all apply to an extension codec
        Decoder d = with(out(100), "z", new CoreCodecs.Zeros(Long.MAX_VALUE));
        assertEquals(Kind.OUTPUT_LIMIT, run(d, new byte[0], "z", 33).kind);
        d = with(Limits.maxOutput(1L << 30), "z", new CoreCodecs.Zeros(Long.MAX_VALUE));
        assertEquals(Kind.RATIO_LIMIT, run(d, new byte[0], "z", 33).kind);
        d = with(out(1L << 30).withMaxInput(3), "p", new CoreCodecs.Pass());
        assertEquals(Kind.INPUT_LIMIT, run(d, "abcd".getBytes(), "p", 33).kind);
        d = with(Limits.maxOutput(1L << 30).withMaxLayers(1), "p", new CoreCodecs.Pass());
        assertEquals(Kind.LAYER_LIMIT, run(d, new byte[0], "p, p", 33).kind);
    }

    // Covers: SG-R-CDC-08 SG-I-05
    @Test
    void a_hostile_exception_in_the_source_is_a_source_error_and_never_escapes() throws Exception {
        Decoder d = decoder(loose());
        InputStream hostile = new InputStream() {
            @Override
            public int read() {
                throw new HostileException();
            }

            @Override
            public int read(byte[] b, int off, int len) {
                throw new HostileException();
            }
        };
        SpillgateException e = assertThrows(SpillgateException.class, () -> d.reader(hostile, "pass").readAllBytes());
        assertEquals(Kind.SOURCE_ERROR, e.kind());
        assertFalse(e.toString().contains("secret"));
        assertTrue(e.toString().length() < 200);
    }

    // Covers: SG-I-05 SG-R-CDC-08
    @Test
    void a_fatal_jvm_error_from_a_codec_releases_the_layer_and_propagates() throws Exception {
        CoreCodecs.Counting counting = new CoreCodecs.Counting(false);
        Decoder d = with(loose(), "oom", new CoreCodecs.Throws(() -> new OutOfMemoryError("test")), "c", counting);
        SpillgateInputStream r = open(d, new byte[] {1, 2, 3}, "oom, c");
        assertThrows(OutOfMemoryError.class, () -> r.read(new byte[8]));
        assertEquals(1, counting.closed.get(), "the state of the layers was released");
        // afterwards the stream is dead but harmless
        SpillgateException e = assertThrows(SpillgateException.class, () -> r.read(new byte[8]));
        assertEquals(Kind.INTERNAL, e.kind());
        r.close();
        // an InternalError / UnknownError / other VirtualMachineError behaves the same way
        for (Supplier<Throwable> make : Arrays.<Supplier<Throwable>>asList(InternalError::new, UnknownError::new)) {
            Decoder d2 = with(loose(), "vm", new CoreCodecs.Throws(make));
            SpillgateInputStream r2 = open(d2, new byte[] {1}, "vm");
            assertThrows(VirtualMachineError.class, () -> r2.read(new byte[8]));
            assertEquals(Kind.INTERNAL, assertThrows(SpillgateException.class, () -> r2.read(new byte[8])).kind());
        }
        // construction
        Decoder d3 = with(loose(), "oom", new CoreCodecs.ThrowsOnNew(() -> new OutOfMemoryError("ctor")));
        assertThrows(OutOfMemoryError.class, () -> d3.reader(in(new byte[0]), "oom"));
    }

    // Covers: SG-R-CDC-08 SG-I-05
    @Test
    void a_codec_that_swallows_a_failure_of_its_input_and_ends_cleanly_is_not_a_clean_end() {
        Decoder d = with(loose(), "swallow", new CoreCodecs.SwallowsAndEnds(), "stored", new CoreCodecs.Stored());
        // the input limit
        Decoder capped = with(out(1L << 30).withMaxInput(5), "swallow", new CoreCodecs.SwallowsAndEnds());
        Outcome o = run(capped, filled(100, 1), "swallow", 64);
        assertEquals(Kind.INPUT_LIMIT, o.kind);
        assertEquals(5, o.bytes.length);
        // an error of the layer below
        o = run(d, b(9, 0), "swallow, stored", 64);
        assertEquals(Kind.CORRUPT, o.kind);
        assertEquals(1, o.layer);
    }

    // Covers: SG-I-01 SG-I-02 SG-I-03 SG-I-05 SG-R-CDC-08 SG-R-RDR-01 SG-I-09
    @Test
    void random_bytes_never_break_an_invariant() {
        Rng rng = new Rng(0x9e3779b97f4a7c15L);
        String[][] headers = {
            h("stored"), h("stored, pass"), h("bytes, stored"), h("expand10"), h("pass, up, dbl"), h(),
        };
        for (int round = 0; round < 400; round++) {
            int len = (int) rng.below(600);
            byte[] input = rng.bytes(len);
            if (round % 3 == 0) {
                // make it look like a stored stream so deeper states are reached
                byte[] framed = stored(rng.bytes((int) rng.below(300)), 1 + (int) rng.below(80));
                if (round % 6 == 0 && framed.length > 0) {
                    framed[(int) rng.below(framed.length)] ^= 1 << rng.below(8);
                }
                input = framed;
            }
            String[] header = headers[(int) rng.below(headers.length)];
            long maxOutput = 1 + rng.below(500);
            Decoder d = decoder(Limits.maxOutput(maxOutput).withRatioGrace(rng.below(300)).withMaxRatio(1 + rng.below(30)));
            Outcome reference = run(d, input, header, 4096);
            assertTrue(reference.bytes.length <= maxOutput, "round " + round);
            for (int size : new int[] {1, 3, 17, 1 << 20}) {
                Outcome o = run(d, input, header, size);
                assertTrue(o.bytes.length <= maxOutput);
                assertEquals(reference.kind, o.kind, "round " + round + " header " + Arrays.toString(header) + " size " + size);
                if (o.kind != Kind.RATIO_LIMIT) {
                    assertArrayEquals(reference.bytes, o.bytes, "round " + round + " header " + Arrays.toString(header) + " size " + size);
                } else {
                    int n = Math.min(o.bytes.length, reference.bytes.length);
                    assertArrayEquals(Arrays.copyOf(reference.bytes, n), Arrays.copyOf(o.bytes, n));
                }
            }
        }
    }

    // Covers: SG-I-01 SG-R-LAY-01 SG-R-LAY-02 SG-I-08 SG-R-RDR-01
    @Test
    void random_chains_never_deliver_more_than_the_budget() {
        String[] names = {"pass", "up", "dbl", "expand10", "shrink10", "bytes"};
        Rng rng = new Rng(0xdeadbeef12345678L);
        for (int round = 0; round < 300; round++) {
            int depth = 1 + (int) rng.below(6);
            String[] parts = new String[depth];
            for (int i = 0; i < depth; i++) {
                parts[i] = names[(int) rng.below(names.length)];
            }
            String header = String.join(", ", parts);
            byte[] input = rng.bytes((int) rng.below(400));
            long maxOutput = 1 + rng.below(3000);
            Decoder d = decoder(out(maxOutput).withMaxLayers(8));
            Outcome reference = run(d, input, header, 4096);
            assertTrue(reference.bytes.length <= maxOutput, "round " + round + " " + header);
            for (int size : new int[] {1, 5, 1 << 20}) {
                assertEquals(reference, run(d, input, header, size), "round " + round + " " + header + " size " + size);
            }
            // chunked source
            int step = 1 + (int) rng.below(50);
            assertEquals(reference, runStream(d, new Chunked(input, step), h(header), 7), "round " + round + " " + header + " chunked");
        }
    }

    // Covers: SG-I-05 SG-R-CDC-06 SG-R-LAY-07
    @Test
    void damaged_stored_streams_are_ok_or_corrupt_never_something_else() {
        byte[] payload = new byte[400];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (i * 7);
        }
        byte[] good = stored(payload, 64);
        Decoder d = decoder(out(1 << 20));
        for (int at = 0; at < good.length; at++) {
            for (int bit = 0; bit < 8; bit++) {
                byte[] bad = good.clone();
                bad[at] ^= 1 << bit;
                Outcome o = run(d, bad, "stored", 64);
                assertTrue(o.kind == null || o.kind == Kind.CORRUPT, "flip " + at + ":" + bit + " gave " + o.kind);
            }
        }
    }
}
