package io.github.felixmiddelhoff.spillgate;

import static io.github.felixmiddelhoff.spillgate.CoreSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/**
 * The reader contract (SPEC section 9): read sizes, sticky terminal conditions, zero-length reads,
 * close, misbehaving sources.
 */
class CoreReaderContractTest {
    private static final int[] SIZES = {1, 2, 3, 4096, 65536, 1 << 20, 1 << 24};

    private static SpillgateException drainError(InputStream in) {
        return assertThrows(SpillgateException.class, () -> {
            byte[] buf = new byte[4096];
            while (in.read(buf) >= 0) {
                // keep reading
            }
        });
    }

    // -- SG-R-RDR-01: read sizes and chunking do not matter ------------------------------------------------

    // Covers: SG-R-RDR-01 SG-I-09 SG-I-01
    @Test
    void sg_r_rdr_01_the_result_does_not_depend_on_read_sizes() throws Exception {
        byte[] data = payload(20_000);
        byte[] good = stored(data, 777);
        byte[] truncated = Arrays.copyOf(good, good.length - 6);
        byte[] badTrailer = good.clone();
        badTrailer[badTrailer.length - 1] = '?';
        byte[] trailing = Arrays.copyOf(good, good.length + 1);
        Object[][] cases = {
            {"ok", good, out(20_000)},
            {"ok with room", good, out(1 << 20)},
            {"output_limit", good, out(19_999)},
            {"output_limit tiny", good, out(1)},
            {"truncated", truncated, out(1 << 20)},
            {"bad trailer", badTrailer, out(1 << 20)},
            {"trailing byte", trailing, out(1 << 20)},
            {"input_limit", good, out(1 << 20).withMaxInput(5_000)},
        };
        for (Object[] c : cases) {
            String name = (String) c[0];
            byte[] input = (byte[]) c[1];
            Decoder d = decoder((Limits) c[2]);
            Outcome reference = run(d, input, "stored", SIZES[0]);
            for (int size : SIZES) {
                assertEquals(reference, run(d, input, "stored", size), name + " with read size " + size);
            }
            // mixed sizes
            Rng rng = new Rng(0x123456789abcdef1L);
            SpillgateInputStream stream = d.reader(in(input), "stored");
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            Kind kind = null;
            while (true) {
                byte[] buf = new byte[(int) rng.below(9000) + 1];
                try {
                    int n = stream.read(buf);
                    if (n < 0) {
                        break;
                    }
                    bytes.write(buf, 0, n);
                } catch (SpillgateException e) {
                    kind = e.kind();
                    break;
                }
            }
            assertEquals(reference, new Outcome(bytes.toByteArray(), kind, 0, 0), name + " with mixed sizes");
            // single byte reads through read()
            stream = d.reader(in(input), "stored");
            bytes.reset();
            kind = null;
            while (true) {
                try {
                    int b = stream.read();
                    if (b < 0) {
                        break;
                    }
                    bytes.write(b);
                } catch (SpillgateException e) {
                    kind = e.kind();
                    break;
                }
            }
            assertEquals(reference, new Outcome(bytes.toByteArray(), kind, 0, 0), name + " with read()");
        }
    }

    // Covers: SG-R-RDR-01 SG-R-LAY-06
    @Test
    void sg_r_rdr_01_the_expected_outcomes_of_the_table() {
        byte[] data = payload(20_000);
        byte[] good = stored(data, 777);
        Decoder d = decoder(out(1 << 20));
        Outcome o = run(d, good, "stored", 4096);
        assertNull(o.kind);
        assertArrayEquals(data, o.bytes);
        o = run(d, Arrays.copyOf(good, good.length - 6), "stored", 4096);
        assertEquals(Kind.CORRUPT, o.kind);
        // the bytes delivered before the error are a prefix of the stream
        assertArrayEquals(Arrays.copyOf(data, o.bytes.length), o.bytes);
        d = decoder(out(1000));
        o = run(d, good, "stored", 4096);
        assertEquals(Kind.OUTPUT_LIMIT, o.kind);
        assertArrayEquals(Arrays.copyOf(data, 1000), o.bytes);
    }

    // Covers: SG-R-RDR-01 SG-I-09
    @Test
    void sg_r_rdr_01_source_chunking_does_not_matter() {
        byte[] data = payload(30_000);
        byte[] good = stored(data, 5000);
        Decoder d = decoder(out(1 << 20));
        Object[][] cases = {
            {h(), data},
            {h("identity"), data},
            {h("pass, pass, bytes"), data},
            {h("stored"), good},
            {h("pass, stored"), good},
            {h("bytes, stored"), good},
        };
        for (Object[] c : cases) {
            String[] header = (String[]) c[0];
            for (int step : new int[] {1, 2, 7, 4095, 4096, 4097, 1 << 20}) {
                for (int size : new int[] {1, 100, 1 << 20}) {
                    Outcome o = runStream(d, new Chunked((byte[]) c[1], step), header, size);
                    String where = Arrays.toString(header) + " step " + step + " size " + size;
                    assertNull(o.kind, where);
                    assertArrayEquals(data, o.bytes, where);
                }
            }
        }
    }

    // Covers: SG-R-RDR-01 SG-R-RAT-02 SG-R-CDC-01
    @Test
    void sg_r_rdr_01_a_codec_that_reads_byte_by_byte_changes_nothing() {
        byte[] data = payload(10_000);
        Decoder d = decoder(loose());
        assertArrayEquals(data, run(d, data, "bytes", 3).bytes);
        Outcome o = run(d, stored(data, 100), "bytes, stored", 1 << 16);
        assertNull(o.kind);
        assertArrayEquals(data, o.bytes);
    }

    // Covers: SG-R-MEM-02 SG-I-08
    @Test
    void a_single_read_returns_at_most_one_step_of_64_kib() throws Exception {
        Decoder d = decoder(loose());
        byte[] data = filled(1 << 20, 5);
        byte[] buf = new byte[1 << 20];
        try (SpillgateInputStream r = d.reader(in(data))) {
            int n = r.read(buf);
            assertTrue(n > 0 && n <= 64 * 1024, "" + n);
        }
        try (SpillgateInputStream r = d.reader(new Endless(0), "pass")) {
            assertTrue(r.read(buf) <= 64 * 1024);
        }
        try (SpillgateInputStream r = d.reader(in(data), "pass, pass")) {
            assertTrue(r.read(buf, 0, buf.length) <= 64 * 1024);
        }
        // the standard helpers work through read() and stay bounded by the limiter
        assertArrayEquals(data, d.reader(in(data)).readAllBytes());
        assertArrayEquals(Arrays.copyOf(data, 100_000), d.reader(in(data)).readNBytes(100_000));
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        assertEquals(data.length, d.reader(in(data), "pass").transferTo(sink));
        assertEquals(data.length, sink.size());
        assertEquals(1000, d.reader(in(data), "pass").skip(1000));
        Decoder tight = decoder(out(10));
        assertEquals(Kind.OUTPUT_LIMIT, assertThrows(SpillgateException.class, () -> tight.reader(in(data)).readAllBytes()).kind());
        assertEquals(Kind.OUTPUT_LIMIT, assertThrows(SpillgateException.class, () -> tight.reader(in(data)).transferTo(new ByteArrayOutputStream())).kind());
        assertEquals(Kind.OUTPUT_LIMIT, assertThrows(SpillgateException.class, () -> tight.reader(in(data)).readNBytes(5000)).kind());
        SpillgateInputStream skipper = tight.reader(in(data));
        assertEquals(10, skipper.skip(10));
        assertEquals(Kind.OUTPUT_LIMIT, assertThrows(SpillgateException.class, () -> skipper.skip(1)).kind());
    }

    // Covers: SG-R-API-01
    @Test
    void arguments_are_checked_like_any_input_stream() throws Exception {
        Decoder d = decoder(loose());
        SpillgateInputStream r = d.reader(in(new byte[] {1, 2, 3}), "pass");
        assertThrows(NullPointerException.class, () -> r.read(null, 0, 1));
        assertThrows(IndexOutOfBoundsException.class, () -> r.read(new byte[4], -1, 1));
        assertThrows(IndexOutOfBoundsException.class, () -> r.read(new byte[4], 0, -1));
        assertThrows(IndexOutOfBoundsException.class, () -> r.read(new byte[4], 2, 3));
        assertThrows(IndexOutOfBoundsException.class, () -> r.read(new byte[4], 5, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> r.read(new byte[4], Integer.MAX_VALUE, 2));
        assertThrows(NullPointerException.class, () -> d.reader(null));
        // none of it changed anything
        assertArrayEquals(new byte[] {1, 2, 3}, r.readAllBytes());
    }

    // -- SG-R-RDR-02: zero-length reads ---------------------------------------------------------------------

    // Covers: SG-R-RDR-02
    @Test
    void sg_r_rdr_02_a_zero_length_read_changes_nothing() throws Exception {
        Decoder d = decoder(loose());
        Chunked source = new Chunked("hello world".getBytes(), 4);
        SpillgateInputStream r = d.reader(source, "pass");
        for (int i = 0; i < 5; i++) {
            assertEquals(0, r.read(new byte[0]));
            assertEquals(0, r.read(new byte[8], 3, 0));
        }
        assertEquals(0, source.calls.get(), "an empty read must not touch the source");
        assertArrayEquals("hello world".getBytes(), r.readAllBytes());
        // after the end an empty read is still fine
        assertEquals(0, r.read(new byte[0]));
        assertEquals(-1, r.read(new byte[1]));
        // and after an error it may return the sticky error, but never data
        Decoder tight = decoder(out(3));
        SpillgateInputStream failed = tight.reader(in("abcdef".getBytes()), "pass");
        assertEquals(Kind.OUTPUT_LIMIT, drainError(failed).kind());
        try {
            assertEquals(0, failed.read(new byte[0]));
        } catch (SpillgateException e) {
            assertEquals(Kind.OUTPUT_LIMIT, e.kind());
        }
    }

    // -- sticky -----------------------------------------------------------------------------------------------

    // Covers: SG-R-LAY-05 SG-I-03
    @Test
    void sg_r_lay_05_errors_and_a_clean_end_are_sticky() throws Exception {
        Decoder d = decoder(out(10));
        SpillgateInputStream r = d.reader(filledStream(50, 1));
        byte[] buf = new byte[100];
        int total = 0;
        SpillgateException first = null;
        while (first == null) {
            try {
                int n = r.read(buf);
                assertNotEquals(-1, n, "a limit hit must not look like the end");
                total += n;
            } catch (SpillgateException e) {
                first = e;
            }
        }
        assertEquals(10, total);
        assertEquals(Kind.OUTPUT_LIMIT, first.kind());
        for (int size : new int[] {1, 100, 7}) {
            for (int i = 0; i < 20; i++) {
                SpillgateException e = readError(r, new byte[size]);
                assertNotNull(e);
                assertEquals(Kind.OUTPUT_LIMIT, e.kind());
                assertEquals(first.layer(), e.layer());
                assertEquals(first.limit(), e.limit());
                assertEquals(first.getMessage(), e.getMessage());
            }
        }
        for (int i = 0; i < 5; i++) {
            assertEquals(Kind.OUTPUT_LIMIT, assertThrows(SpillgateException.class, r::read).kind());
        }
        // a clean end is sticky as well
        Decoder loose = decoder(loose());
        SpillgateInputStream clean = loose.reader(in("abc".getBytes()), "pass");
        assertArrayEquals("abc".getBytes(), clean.readAllBytes());
        for (int i = 0; i < 20; i++) {
            assertEquals(-1, clean.read(buf));
            assertEquals(-1, clean.read());
        }
    }

    private static InputStream filledStream(int n, int value) {
        return in(filled(n, value));
    }

    // Covers: SG-R-LAY-05 SG-I-03 SG-R-LAY-04 SG-I-02
    @Test
    void sg_r_lay_05_every_error_kind_is_sticky_with_the_same_kind() throws Exception {
        Decoder d = Decoder.builder(out(1000))
                .codec("corrupt", new CoreCodecs.Fails(Kind.CORRUPT))
                .codec("mem", new CoreCodecs.Fails(Kind.MEMORY_LIMIT))
                .codec("internal", new CoreCodecs.Fails(Kind.INTERNAL))
                .codec("panic", new CoreCodecs.Panicker(10))
                .codec("overreport", new CoreCodecs.OverReport(1))
                .codec("io", new CoreCodecs.FailsWithIo())
                .codec("stored", new CoreCodecs.Stored())
                .build();
        Object[][] cases = {
            {"corrupt", b(1), Kind.CORRUPT},
            {"mem", b(1), Kind.MEMORY_LIMIT},
            {"internal", b(1), Kind.INTERNAL},
            {"panic", b(1), Kind.INTERNAL},
            {"overreport", b(1), Kind.INTERNAL},
            {"io", b(1), Kind.INTERNAL},
            {"stored", b(1, 0), Kind.CORRUPT},
        };
        for (Object[] c : cases) {
            String header = (String) c[0];
            SpillgateInputStream r = d.reader(in((byte[]) c[1]), header);
            byte[] buf = new byte[64];
            Kind seen = null;
            String message = null;
            for (int i = 0; i < 30; i++) {
                try {
                    int n = r.read(buf);
                    assertNotEquals(-1, n, header + ": an error must not look like the end");
                } catch (SpillgateException e) {
                    if (seen != null) {
                        assertEquals(seen, e.kind(), header);
                        assertEquals(message, e.getMessage(), header);
                    }
                    seen = e.kind();
                    message = e.getMessage();
                }
            }
            assertEquals(c[2], seen, header);
        }
    }

    // -- close ------------------------------------------------------------------------------------------------

    // Covers: SG-R-RDR-06 SG-I-10
    @Test
    void sg_r_rdr_06_close_is_idempotent_and_reads_after_close_fail_with_a_plain_io_exception() throws Exception {
        Decoder d = decoder(loose());
        SpillgateInputStream r = d.reader(in("abcdef".getBytes()), "pass");
        byte[] buf = new byte[2];
        assertEquals(2, r.read(buf));
        r.close();
        r.close();
        r.close();
        for (int i = 0; i < 3; i++) {
            IOException e = assertThrows(IOException.class, () -> r.read(buf));
            assertFalse(e instanceof SpillgateException, "a closed error is not an outcome class");
            assertTrue(e.getMessage().contains("closed"), e.getMessage());
            e = assertThrows(IOException.class, r::read);
            assertFalse(e instanceof SpillgateException);
            assertThrows(IOException.class, () -> r.readAllBytes());
        }
        assertEquals(0, r.available());
        // a closed stream after its clean end, and after an error
        SpillgateInputStream ended = d.reader(in("ab".getBytes()));
        ended.readAllBytes();
        ended.close();
        IOException e = assertThrows(IOException.class, () -> ended.read(buf));
        assertFalse(e instanceof SpillgateException);
        Decoder tight = decoder(out(1));
        SpillgateInputStream failed = tight.reader(in("ab".getBytes()));
        drainError(failed);
        failed.close();
        failed.close();
        e = assertThrows(IOException.class, () -> failed.read(buf));
        assertFalse(e instanceof SpillgateException);
        // closing a fresh stream that was never read is fine, and so is a zero-length read after close
        SpillgateInputStream fresh = decoder(loose()).reader(in("x".getBytes()), "pass");
        fresh.close();
        fresh.close();
        assertThrows(IOException.class, () -> fresh.read(new byte[0]));
    }

    // Covers: SG-R-API-02
    @Test
    void available_is_always_zero() throws Exception {
        Decoder d = decoder(loose());
        SpillgateInputStream r = d.reader(in(payload(100000)), "pass");
        assertEquals(0, r.available());
        r.read(new byte[10]);
        assertEquals(0, r.available());
        r.readAllBytes();
        assertEquals(0, r.available());
    }

    private static final class CloseSpy extends Chunked {
        final AtomicBoolean closed = new AtomicBoolean();

        CloseSpy(byte[] data, int step) {
            super(data, step);
        }

        @Override
        public void close() {
            closed.set(true);
        }
    }

    // Covers: SG-R-RDR-06 SG-I-10
    @Test
    void sg_r_rdr_06_close_does_not_close_or_read_the_callers_source() throws Exception {
        Decoder d = decoder(loose());
        CloseSpy source = new CloseSpy(filled(100_000, 1), 100_000);
        SpillgateInputStream r = d.reader(source, "pass");
        assertEquals(10, r.read(new byte[10]));
        long before = source.bytes.get();
        int callsBefore = source.calls.get();
        r.close();
        r.close();
        assertEquals(before, source.bytes.get(), "close must not read");
        assertEquals(callsBefore, source.calls.get());
        assertFalse(source.closed.get(), "close must not close the callers source");
        // the caller can keep using the source; the stream took at most one buffer ahead
        assertTrue(source.remaining() >= 100_000 - 4096, "" + source.remaining());
        // nor does a terminal error or a clean end
        CloseSpy source2 = new CloseSpy(filled(10, 1), 100);
        SpillgateInputStream r2 = d.reader(source2);
        r2.readAllBytes();
        r2.close();
        assertFalse(source2.closed.get());
    }

    // Covers: SG-I-10 SG-R-RDR-06
    @Test
    void sg_i_10_nothing_outlives_close_all_codecs_are_closed_exactly_once() throws Exception {
        CoreCodecs.Counting probe = new CoreCodecs.Counting(false);
        Decoder d = Decoder.builder(loose()).codec("probe", probe).build();
        SpillgateInputStream r = d.reader(in("hello".getBytes()), "probe, probe");
        assertEquals(2, probe.created.get());
        assertEquals(0, probe.closed.get());
        assertTrue(r.read(new byte[2]) > 0);
        assertEquals(0, probe.closed.get());
        r.close();
        assertEquals(2, probe.closed.get(), "codec readers are released by close");
        r.close();
        r.close();
        assertEquals(2, probe.closed.get(), "close is idempotent");
        // a clean end releases the codecs without close()
        SpillgateInputStream ended = d.reader(in("hi".getBytes()), "probe");
        ended.readAllBytes();
        assertEquals(3, probe.closed.get());
        ended.close();
        assertEquals(3, probe.closed.get());
        // so does a terminal error
        Decoder tight = Decoder.builder(out(1)).codec("probe", probe).build();
        SpillgateInputStream failed = tight.reader(in("hello".getBytes()), "probe");
        drainError(failed);
        assertEquals(4, probe.closed.get());
        failed.close();
        assertEquals(4, probe.closed.get());
        // a failed construction releases everything built so far
        int created = probe.created.get();
        int closed = probe.closed.get();
        assertThrows(SpillgateException.class, () -> d.reader(in("hello".getBytes()), "probe, nope"));
        assertEquals(created, probe.created.get(), "the header is checked before any codec exists");
        // an exception while constructing layer 2 closes layer 1
        Decoder boom = Decoder.builder(loose()).codec("probe", probe)
                .codec("boom", new CoreCodecs.ThrowsOnNew(() -> new IllegalStateException("no"))).build();
        SpillgateException e = assertThrows(SpillgateException.class, () -> boom.reader(in("hello".getBytes()), "boom, probe"));
        assertEquals(Kind.INTERNAL, e.kind());
        assertEquals(2, e.layer());
        assertEquals(created + 1, probe.created.get());
        assertEquals(closed + 1, probe.closed.get(), "layer 1 was released");
        // a throwing close() is contained and still releases the others
        CoreCodecs.Counting hostile = new CoreCodecs.Counting(true);
        Decoder h = Decoder.builder(loose()).codec("h", hostile).codec("probe", probe).build();
        int pc = probe.closed.get();
        SpillgateInputStream mixed = h.reader(in("hello".getBytes()), "probe, h, h");
        mixed.read(new byte[1]);
        mixed.close();
        assertEquals(2, hostile.closed.get(), "both hostile readers were closed");
        assertEquals(pc + 1, probe.closed.get());
        // dropping the references is enough for the collector: no Cleaner, no finalizer
        assertThrows(NoSuchMethodException.class, () -> SpillgateInputStream.class.getDeclaredMethod("finalize"));
        assertThrows(NoSuchMethodException.class, () -> Decoder.class.getDeclaredMethod("finalize"));
    }

    // -- distinct streams --------------------------------------------------------------------------------------

    // Covers: SG-R-RDR-07
    @Test
    void sg_r_rdr_07_distinct_streams_are_independent() throws Exception {
        Decoder d = decoder(out(10));
        SpillgateInputStream a = d.reader(filledStream(50, 1), "pass");
        SpillgateInputStream b = d.reader(filledStream(5, 2), "pass");
        drainError(a);
        assertArrayEquals(filled(5, 2), b.readAllBytes()); // a's failure does not affect b
        SpillgateInputStream c = d.reader(filledStream(5, 3), "pass");
        a.close();
        assertArrayEquals(filled(5, 3), c.readAllBytes());
    }

    // -- sources ------------------------------------------------------------------------------------------------

    // Covers: SG-R-RDR-05 SG-R-LAY-05
    @Test
    void sg_r_rdr_05_a_source_error_is_source_error_with_the_cause() throws Exception {
        Decoder d = decoder(loose());
        String[][] headers = {h(), h("pass"), h("stored"), h("bytes"), h("pass, stored"), h("stored, pass, pass")};
        for (String[] header : headers) {
            IOException original = new IOException("the socket exploded");
            FailAfter source = new FailAfter(Arrays.copyOf(stored(payload(100), 10), 30), () -> original);
            SpillgateInputStream r = d.reader(source, header);
            SpillgateException e = drainError(r);
            assertEquals(Kind.SOURCE_ERROR, e.kind(), Arrays.toString(header));
            assertEquals(1, e.layer(), Arrays.toString(header));
            assertSame(original, e.getCause(), "the cause is reachable");
            assertFalse(e.getMessage().contains("exploded"), "the message does not carry source text");
            // sticky, and the cause is still there
            for (int i = 0; i < 3; i++) {
                SpillgateException again = assertThrows(SpillgateException.class, () -> r.read(new byte[8]));
                assertEquals(Kind.SOURCE_ERROR, again.kind());
                assertSame(original, again.getCause());
                assertEquals(1, again.layer());
            }
            assertEquals(Kind.SOURCE_ERROR, assertThrows(SpillgateException.class, r::read).kind());
        }
    }

    // Covers: SG-R-RDR-05 SG-R-CDC-08
    @Test
    void sg_r_rdr_05_a_source_error_is_reported_even_if_the_codec_swallows_it() throws Exception {
        Decoder d = Decoder.builder(loose()).codec("bytes", new CoreCodecs.ByteByByte())
                .codec("swallow", new CoreCodecs.SwallowsAndEnds()).build();
        // the "bytes" codec turns any input failure into corrupt; the source failure must win
        for (String header : new String[] {"bytes", "swallow", "swallow, bytes", "bytes, swallow"}) {
            FailAfter source = new FailAfter("abc".getBytes(), () -> new IOException("nope"));
            SpillgateInputStream r = d.reader(source, header);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16];
            SpillgateException e = null;
            while (e == null) {
                try {
                    int n = r.read(buf);
                    assertNotEquals(-1, n, header + ": a swallowed source failure must not be a clean end");
                    out.write(buf, 0, n);
                } catch (SpillgateException ex) {
                    e = ex;
                }
            }
            assertEquals(Kind.SOURCE_ERROR, e.kind(), header);
            assertArrayEquals("abc".getBytes(), out.toByteArray(), header);
        }
    }

    // Covers: SG-R-RDR-05 SG-R-API-02
    @Test
    void sg_r_rdr_05_decode_all_keeps_the_original_cause() {
        Decoder d = decoder(loose());
        IOException slow = new IOException("slow");
        FailAfter source = new FailAfter("abc".getBytes(), () -> slow);
        SpillgateException e = assertThrows(SpillgateException.class, () -> d.decodeAll(source, "pass"));
        assertEquals(Kind.SOURCE_ERROR, e.kind());
        assertSame(slow, e.getCause());
        assertEquals(400, e.kind().httpStatus());
    }

    // Covers: SG-R-RDR-05
    @Test
    void every_kind_of_source_exception_is_a_source_error_and_never_retried() throws Exception {
        Decoder d = decoder(loose());
        List<java.util.function.Supplier<Throwable>> failures = new ArrayList<>();
        failures.add(() -> new InterruptedIOException("interrupted"));
        failures.add(() -> new java.io.EOFException("eof"));
        failures.add(() -> new java.io.UncheckedIOException(new IOException("wrapped")));
        failures.add(() -> new IllegalStateException("runtime"));
        failures.add(() -> new java.net.SocketTimeoutException("timeout"));
        failures.add(() -> new IOException("hostile\n\u001b[31m" + "A".repeat(10_000)));
        for (java.util.function.Supplier<Throwable> failure : failures) {
            for (String[] header : new String[][] {h(), h("pass"), h("pass, pass")}) {
                int[] calls = {0};
                InputStream source = new InputStream() {
                    @Override
                    public int read() {
                        throw new AssertionError();
                    }

                    @Override
                    public int read(byte[] b, int off, int len) throws IOException {
                        calls[0]++;
                        CoreCodecs.sneaky(failure.get());
                        return 0;
                    }
                };
                SpillgateInputStream r = d.reader(source, header);
                SpillgateException e = drainError(r);
                assertEquals(Kind.SOURCE_ERROR, e.kind());
                assertNotNull(e.getCause());
                assertEquals(1, e.layer());
                assertEquals(1, calls[0], "the source is not retried and not read again");
                assertTrue(e.toString().length() < 200, "the message carries no source text");
                assertThrows(SpillgateException.class, r::read);
                assertEquals(1, calls[0]);
            }
        }
    }

    // Covers: SG-R-RDR-05 SG-R-LAY-05
    @Test
    void a_spillgate_exception_from_the_source_passes_through_unchanged() throws Exception {
        Decoder d = decoder(loose());
        // a custom source that throws a typed error
        InputStream typed = new InputStream() {
            @Override
            public int read() {
                return -1;
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                throw new SpillgateException(Kind.MEMORY_LIMIT, "from below", 7, 123, null);
            }
        };
        SpillgateException e = drainError(d.reader(typed, "pass"));
        assertEquals(Kind.MEMORY_LIMIT, e.kind());
        assertEquals(7, e.layer(), "the lower layer's number is kept");
        assertEquals(123, e.limit());
        // an untyped layer is named after the layer that saw it
        InputStream unnamed = new InputStream() {
            @Override
            public int read() {
                return -1;
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                throw new SpillgateException(Kind.CORRUPT, "no layer");
            }
        };
        e = drainError(d.reader(unnamed, "pass, pass"));
        assertEquals(Kind.CORRUPT, e.kind());
        assertEquals(1, e.layer());
        // a stream of another decoder as the source
        SpillgateInputStream inner = d.reader(in(b(9, 0)), "stored");
        SpillgateInputStream outer = d.reader(inner, "up");
        e = drainError(outer);
        assertEquals(Kind.CORRUPT, e.kind());
    }

    // Covers: SG-R-CDC-06 SG-R-RDR-05
    @Test
    void sg_r_cdc_06_a_source_that_ends_early_is_corrupt_not_source_error() {
        Decoder d = decoder(loose());
        byte[] full = stored(payload(500), 100);
        for (int cut = 0; cut < full.length; cut++) {
            Outcome o = run(d, Arrays.copyOf(full, cut), "stored", 64);
            assertEquals(Kind.CORRUPT, o.kind, "cut at " + cut);
            assertArrayEquals(Arrays.copyOf(payload(500), o.bytes.length), o.bytes);
        }
        assertNull(run(d, full, "stored", 64).kind);
        // an empty source is a clean end for identity and truncation for a codec
        Outcome empty = run(d, new byte[0], h(), 8);
        assertNull(empty.kind);
        assertEquals(0, empty.bytes.length);
        assertEquals(Kind.CORRUPT, run(d, new byte[0], "stored", 8).kind);
    }

    // Covers: SG-R-RDR-03
    @Test
    void sg_r_rdr_03_a_source_that_returns_zero_bytes_without_progress_is_internal() throws Exception {
        Decoder d = decoder(loose());
        for (String[] header : new String[][] {h(), h("pass"), h("pass, pass")}) {
            FixedCountSource source = new FixedCountSource(0);
            SpillgateInputStream r = d.reader(source, header);
            byte[] buf = new byte[16];
            SpillgateException e = assertThrows(SpillgateException.class, () -> r.read(buf));
            assertEquals(Kind.INTERNAL, e.kind(), Arrays.toString(header));
            assertEquals(1, e.layer());
            assertEquals(100, source.calls.get(), Arrays.toString(header) + ": exactly 100 fruitless calls");
            int after = source.calls.get();
            for (int i = 0; i < 5; i++) {
                assertEquals(Kind.INTERNAL, assertThrows(SpillgateException.class, () -> r.read(buf)).kind());
            }
            assertEquals(after, source.calls.get(), "no further reads after the terminal error (SG-R-LAY-09)");
        }
    }

    // Covers: SG-R-RDR-03
    @Test
    void sg_r_rdr_03_the_zero_return_boundary() throws Exception {
        Decoder d = decoder(loose());
        // 99 empty reads in a row before every real read are survived, 100 are not
        byte[] data = payload(10_000);
        Outcome ok = runStream(d, new Stuttering(new Chunked(data, 1000), 99), h("pass"), 4096);
        assertNull(ok.kind);
        assertArrayEquals(data, ok.bytes);
        Outcome bad = runStream(d, new Stuttering(new Chunked(data, 1000), 100), h("pass"), 4096);
        assertEquals(Kind.INTERNAL, bad.kind);
        assertEquals(0, bad.bytes.length);
    }

    // Covers: SG-R-CDC-06 SG-R-RDR-03
    @Test
    void the_end_of_the_source_is_minus_one_and_the_source_is_not_read_again() throws Exception {
        Decoder d = decoder(loose());
        int[] calls = {0};
        InputStream ended = new InputStream() {
            @Override
            public int read() {
                return -1;
            }

            @Override
            public int read(byte[] b, int off, int len) {
                calls[0]++;
                return -1;
            }
        };
        SpillgateInputStream r = d.reader(ended, "pass");
        byte[] buf = new byte[8];
        for (int i = 0; i < 10; i++) {
            assertEquals(-1, r.read(buf));
        }
        assertEquals(1, calls[0], "the source is not read again after its end");
        assertEquals(Kind.CORRUPT, run(d, new byte[0], "stored", 8).kind);
    }

    // Covers: SG-R-RDR-04
    @Test
    void sg_r_rdr_04_a_source_that_reports_more_than_requested_is_internal() throws Exception {
        Decoder d = decoder(loose());
        for (int extra : new int[] {1, 2, 4096, 1 << 20, Integer.MAX_VALUE / 2}) {
            for (String[] header : new String[][] {h(), h("pass"), h("stored")}) {
                SpillgateInputStream r = d.reader(new OverReportingSource(extra), header);
                byte[] buf = new byte[100];
                SpillgateException e = assertThrows(SpillgateException.class, () -> r.read(buf));
                assertEquals(Kind.INTERNAL, e.kind(), Arrays.toString(header) + " " + extra);
                assertEquals(Kind.INTERNAL, assertThrows(SpillgateException.class, () -> r.read(buf)).kind());
            }
        }
        // a count that is only one more than the request, for the meter's own request size
        SpillgateInputStream r = d.reader(new OverReportingSource(1), "pass");
        assertEquals(Kind.INTERNAL, assertThrows(SpillgateException.class, () -> r.read(new byte[1])).kind());
    }

    // Covers: SG-R-RDR-04
    @Test
    void sg_r_rdr_04_a_negative_count_other_than_minus_one_is_internal() throws Exception {
        Decoder d = decoder(loose());
        for (int value : new int[] {-2, -3, -100, Integer.MIN_VALUE, Integer.MIN_VALUE + 1}) {
            for (String[] header : new String[][] {h(), h("pass"), h("pass, stored")}) {
                FixedCountSource source = new FixedCountSource(value);
                SpillgateInputStream r = d.reader(source, header);
                SpillgateException e = assertThrows(SpillgateException.class, () -> r.read(new byte[16]));
                assertEquals(Kind.INTERNAL, e.kind(), value + " " + Arrays.toString(header));
                int calls = source.calls.get();
                assertEquals(Kind.INTERNAL, assertThrows(SpillgateException.class, r::read).kind());
                assertEquals(calls, source.calls.get());
            }
        }
    }

    // Covers: SG-R-RDR-01 SG-R-RDR-05
    @Test
    void short_reads_of_the_source_are_fine() throws Exception {
        Decoder d = decoder(loose());
        byte[] data = payload(9000);
        Outcome o = runStream(d, new Stuttering(new Chunked(stored(data, 300), 700), 3), h("stored"), 4096);
        assertNull(o.kind);
        assertArrayEquals(data, o.bytes);
    }

    // Covers: SG-R-LAY-09
    @Test
    void sg_r_lay_09_the_source_is_not_read_after_a_terminal_condition() throws Exception {
        Decoder d = decoder(out(100));
        // output limit
        Chunked source = new Chunked(new byte[1 << 20], 4096);
        SpillgateInputStream r = d.reader(source, "pass");
        drainError(r);
        int after = source.calls.get();
        for (int i = 0; i < 10; i++) {
            readError(r, new byte[64]);
        }
        assertEquals(after, source.calls.get());
        assertTrue(source.bytes.get() <= 4096 * 2, "" + source.bytes.get());
        // clean end
        Chunked source2 = new Chunked("abc".getBytes(), 4096);
        SpillgateInputStream r2 = d.reader(source2, "pass");
        r2.readAllBytes();
        after = source2.calls.get();
        for (int i = 0; i < 10; i++) {
            assertEquals(-1, r2.read(new byte[64]));
        }
        assertEquals(after, source2.calls.get());
        // corrupt
        Chunked source3 = new Chunked(filled(100, 9), 10);
        SpillgateInputStream r3 = d.reader(source3, "stored");
        drainError(r3);
        after = source3.calls.get();
        for (int i = 0; i < 10; i++) {
            assertNotNull(readError(r3, new byte[64]));
        }
        assertEquals(after, source3.calls.get());
        // close
        Chunked source4 = new Chunked(filled(100_000, 9), 100);
        SpillgateInputStream r4 = d.reader(source4, "pass");
        r4.read(new byte[1]);
        r4.close();
        after = source4.calls.get();
        assertThrows(IOException.class, () -> r4.read(new byte[1]));
        assertEquals(after, source4.calls.get());
    }

    // Covers: SG-R-LAY-09 SG-R-RAT-02
    @Test
    void sg_r_lay_09_sg_r_rat_02_look_ahead_is_bounded() throws Exception {
        Decoder d = decoder(loose());
        Chunked source = new Chunked(new byte[10 << 20], 1 << 20);
        SpillgateInputStream r = d.reader(source, "pass");
        byte[] buf = new byte[1];
        assertTrue(r.read(buf) > 0);
        assertTrue(source.bytes.get() <= 64 * 1024, "fetched " + source.bytes.get() + " bytes for one");
        assertTrue(source.largestRequest.get() <= 4096, "asked for " + source.largestRequest.get());
        // with a chain, still bounded
        Chunked source2 = new Chunked(new byte[10 << 20], 1 << 20);
        SpillgateInputStream r2 = d.reader(source2, "pass, pass, pass");
        assertTrue(r2.read(buf) > 0);
        assertTrue(source2.bytes.get() <= 64 * 1024, "fetched " + source2.bytes.get() + " bytes for one");
        assertEquals(0, source2.singleByteCalls.get(), "the source is read in blocks, never byte by byte");
    }

    // Covers: SG-R-API-05
    @Test
    void sg_r_api_05_failures_carry_kind_layer_and_limit() throws Exception {
        Decoder d = decoder(out(50));
        SpillgateException e = drainError(d.reader(in(new byte[100]), "pass"));
        assertEquals(Kind.OUTPUT_LIMIT, e.kind());
        assertEquals(1, e.layer());
        assertEquals(50, e.limit());
        assertTrue(e.toString().contains("output_limit"));
        assertTrue(e instanceof IOException);
        Decoder l = decoder(loose());
        SpillgateException c = assertThrows(SpillgateException.class, () -> l.decodeAll(in(b(1, 0)), "stored"));
        assertEquals(Kind.CORRUPT, c.kind());
        assertEquals(1, c.layer());
    }

    // Covers: SG-R-LAY-06 SG-R-API-02
    @Test
    void sg_r_lay_06_decode_all_returns_no_data_on_error_but_streams_deliver_a_prefix() throws Exception {
        byte[] data = payload(5000);
        byte[] bad = stored(data, 1000);
        bad[bad.length - 1] = 'X';
        Decoder d = decoder(loose());
        assertEquals(Kind.CORRUPT, assertThrows(SpillgateException.class, () -> d.decodeAll(in(bad), "stored")).kind());
        SpillgateInputStream r = d.reader(in(bad), "stored");
        ByteArrayOutputStream got = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        SpillgateException e = null;
        while (e == null) {
            try {
                int n = r.read(buf);
                assertNotEquals(-1, n);
                got.write(buf, 0, n);
            } catch (SpillgateException ex) {
                e = ex;
            }
        }
        assertEquals(Kind.CORRUPT, e.kind());
        assertArrayEquals(data, got.toByteArray(), "all bytes were produced before the trailer was judged");
        // the static convenience
        assertArrayEquals(data, Decoder.decodeAll(in(data), Limits.maxOutput(5000).withoutRatioLimit()));
        assertArrayEquals(data, d.decodeAll(in(stored(data, 1000)), "stored"));
        assertEquals(0, d.decodeAll(in(new byte[0])).length);
        assertEquals(0, d.decodeAll(in(new byte[0]), "pass, up").length);
        // decodeAll with exactly maxOutput and one more
        assertEquals(Kind.OUTPUT_LIMIT, assertThrows(SpillgateException.class,
                () -> Decoder.decodeAll(in(data), Limits.maxOutput(4999).withoutRatioLimit())).kind());
    }
}
