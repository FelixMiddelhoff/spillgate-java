package io.github.felixmiddelhoff.spillgate;

import static io.github.felixmiddelhoff.spillgate.CoreSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Cooperative cancellation (SG-R-API-04, trap 3: a canceled stream must not look retryable). */
class CoreCancelTest {
    private static Decoder zeroDecoder() {
        try {
            return Decoder.builder(Limits.maxOutput(1L << 40).withoutRatioLimit())
                    .codec("zeros", new CoreCodecs.Zeros(1L << 40))
                    .codec("shrink", new CoreCodecs.Shrink(1L << 50))
                    .codec("pass", new CoreCodecs.Pass())
                    .build();
        } catch (SpillgateException e) {
            throw new AssertionError(e);
        }
    }

    // Covers: SG-R-API-04 SG-I-03
    @Test
    void sg_r_api_04_cancel_before_the_first_read_is_canceled_and_sticky() throws Exception {
        CancelToken token = new CancelToken();
        SpillgateInputStream stream = zeroDecoder().reader(in(new byte[] {'x'}), token, "zeros");
        token.cancel();
        for (int i = 0; i < 3; i++) {
            SpillgateException e = assertThrows(SpillgateException.class, () -> stream.read(new byte[16]));
            assertEquals(Kind.CANCELED, e.kind());
            assertEquals(503, e.kind().httpStatus());
        }
        assertEquals(Kind.CANCELED, assertThrows(SpillgateException.class, stream::read).kind());
        assertEquals(Kind.CANCELED, assertThrows(SpillgateException.class, stream::readAllBytes).kind());
        stream.close();
    }

    // Covers: SG-R-API-04
    @Test
    void sg_r_api_04_cancel_between_reads_stops_the_stream() throws Exception {
        CancelToken token = new CancelToken();
        SpillgateInputStream stream = zeroDecoder().reader(in(new byte[] {'x'}), token, "zeros");
        byte[] buf = new byte[100_000];
        assertTrue(stream.read(buf) > 0);
        assertFalse(token.isCanceled());
        token.cancel();
        token.cancel();
        assertTrue(token.isCanceled());
        assertEquals(Kind.CANCELED, assertThrows(SpillgateException.class, () -> stream.read(buf)).kind());
    }

    // Covers: SG-R-API-04
    @Test
    void sg_r_api_04_cancel_from_another_thread_ends_an_endless_stream() throws Exception {
        CancelToken token = new CancelToken();
        Thread canceler = new Thread(() -> {
            try {
                Thread.sleep(50);
            } catch (InterruptedException ignored) {
                // fall through
            }
            token.cancel();
        });
        SpillgateInputStream stream = zeroDecoder().reader(in(new byte[] {'x'}), token, "zeros");
        canceler.start();
        byte[] buf = new byte[65_536];
        SpillgateException error = null;
        long deadline = System.nanoTime() + 20_000_000_000L;
        while (error == null) {
            assertTrue(System.nanoTime() < deadline, "cancellation was not observed");
            try {
                stream.read(buf);
            } catch (SpillgateException e) {
                error = e;
            }
        }
        assertEquals(Kind.CANCELED, error.kind());
        canceler.join();
    }

    // Covers: SG-R-API-04
    @Test
    void sg_r_api_04_cancel_is_seen_while_a_layer_only_consumes_input() throws Exception {
        // The check sits in the input meter, so a codec that reads a lot of input without producing
        // output is stopped too, also from another thread.
        CancelToken token = new CancelToken();
        Endless source = new Endless(0);
        SpillgateInputStream stream = zeroDecoder().reader(source, token, "shrink");
        CountDownLatch started = new CountDownLatch(1);
        AtomicReference<Throwable> result = new AtomicReference<>();
        Thread reader = new Thread(() -> {
            started.countDown();
            try {
                stream.read(new byte[16]);
                result.set(new AssertionError("expected an error"));
            } catch (Throwable t) {
                result.set(t);
            }
        });
        reader.start();
        started.await();
        Thread.sleep(30);
        long consumedBefore = source.total.get();
        token.cancel();
        reader.join(20_000);
        assertFalse(reader.isAlive(), "the reader thread is stuck in the codec");
        assertTrue(result.get() instanceof SpillgateException, String.valueOf(result.get()));
        assertEquals(Kind.CANCELED, ((SpillgateException) result.get()).kind());
        assertTrue(source.total.get() >= consumedBefore);
        // already canceled before the first read: not a single byte is read from the source
        Endless source2 = new Endless(0);
        CancelToken canceled = new CancelToken();
        canceled.cancel();
        SpillgateInputStream stream2 = zeroDecoder().reader(source2, canceled, "shrink");
        assertEquals(Kind.CANCELED, assertThrows(SpillgateException.class, () -> stream2.read(new byte[16])).kind());
        assertEquals(0, source2.total.get());
    }

    // Covers: SG-R-API-04
    @Test
    void a_stream_without_a_token_is_unaffected_and_a_null_token_is_none() throws Exception {
        SpillgateInputStream stream = zeroDecoder().reader(in(new byte[] {'x'}), "zeros");
        assertTrue(stream.read(new byte[16]) > 0);
        SpillgateInputStream nulled = zeroDecoder().reader(in(new byte[] {'x'}), (CancelToken) null, "zeros");
        assertTrue(nulled.read(new byte[16]) > 0);
    }

    // Covers: SG-R-API-04
    @Test
    void sg_r_api_04_a_canceled_stream_never_throws_an_interrupted_io_exception() throws Exception {
        // Callers and helpers retry InterruptedIOException; a canceled stream must not look retryable,
        // and Thread.interrupt() is not cancellation.
        CancelToken token = new CancelToken();
        token.cancel();
        for (String header : new String[] {"zeros", "pass", "pass, pass"}) {
            SpillgateInputStream stream = zeroDecoder().reader(in(new byte[] {'x'}), token, header);
            IOException e = assertThrows(IOException.class, () -> stream.readAllBytes());
            assertFalse(e instanceof InterruptedIOException, header);
            assertTrue(e instanceof SpillgateException, header);
            assertEquals(Kind.CANCELED, ((SpillgateException) e).kind());
            assertNull(e.getCause());
        }
        // a thread interrupt is not a cancellation
        Thread.currentThread().interrupt();
        try {
            SpillgateInputStream stream = zeroDecoder().reader(in(new byte[] {'x'}), "zeros");
            assertTrue(stream.read(new byte[16]) > 0);
        } finally {
            assertTrue(Thread.interrupted(), "the interrupt flag was left alone");
        }
    }

    // Covers: SG-R-API-04 SG-R-LAY-05
    @Test
    void a_clean_end_stays_a_clean_end_after_a_later_cancel() throws Exception {
        CancelToken token = new CancelToken();
        SpillgateInputStream stream = decoder(loose()).reader(in("abc".getBytes()), token, "pass");
        assertArrayEquals("abc".getBytes(), stream.readAllBytes());
        token.cancel();
        assertEquals(-1, stream.read(new byte[4]));
        assertEquals(0, stream.read(new byte[0]));
    }

    // Covers: SG-R-API-04 SG-R-LAY-05
    @Test
    void an_error_that_came_first_stays_the_error_after_a_cancel() throws Exception {
        CancelToken token = new CancelToken();
        SpillgateInputStream stream = decoder(out(2)).reader(in("abcdef".getBytes()), token, "pass");
        assertEquals(Kind.OUTPUT_LIMIT, assertThrows(SpillgateException.class, stream::readAllBytes).kind());
        token.cancel();
        assertEquals(Kind.OUTPUT_LIMIT, assertThrows(SpillgateException.class, () -> stream.read(new byte[4])).kind());
    }

    // Covers: SG-R-API-04
    @Test
    void cancellation_is_seen_at_least_once_per_4_kib_fetched_from_the_source() throws Exception {
        CancelToken token = new CancelToken();
        Endless source = new Endless(1);
        Decoder d = decoder(out(1L << 40));
        SpillgateInputStream stream = d.reader(new InputWrapper(source, token, 3), token, "pass");
        byte[] buf = new byte[1 << 16];
        SpillgateException e = null;
        int reads = 0;
        while (e == null && reads < 100) {
            try {
                stream.read(buf);
                reads++;
            } catch (SpillgateException ex) {
                e = ex;
            }
        }
        assertNotNull(e, "cancel was not observed");
        assertEquals(Kind.CANCELED, e.kind());
        assertTrue(source.total.get() <= 4 * 4096 + 1, "fetched " + source.total.get() + " bytes after the cancel");
    }

    /** Cancels the token when the n-th fetch happens. */
    private static final class InputWrapper extends java.io.InputStream {
        private final java.io.InputStream inner;
        private final CancelToken token;
        private final int cancelAt;
        private int calls;

        InputWrapper(java.io.InputStream inner, CancelToken token, int cancelAt) {
            this.inner = inner;
            this.token = token;
            this.cancelAt = cancelAt;
        }

        @Override
        public int read() throws IOException {
            return inner.read();
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (++calls == cancelAt) {
                token.cancel();
            }
            return inner.read(b, off, len);
        }
    }

    // Covers: SG-R-API-04
    @Test
    void many_streams_can_share_one_token() throws Exception {
        CancelToken token = new CancelToken();
        Decoder d = zeroDecoder();
        SpillgateInputStream[] streams = new SpillgateInputStream[8];
        for (int i = 0; i < streams.length; i++) {
            streams[i] = d.reader(in(new byte[] {'x'}), token, "zeros");
            assertTrue(streams[i].read(new byte[10]) > 0);
        }
        token.cancel();
        for (SpillgateInputStream s : streams) {
            assertEquals(Kind.CANCELED, assertThrows(SpillgateException.class, () -> s.read(new byte[10])).kind());
        }
        // a new stream with the canceled token is canceled from the start; one without is not
        assertEquals(Kind.CANCELED, assertThrows(SpillgateException.class,
                () -> d.reader(in(new byte[] {'x'}), token, "zeros").read(new byte[1])).kind());
        assertTrue(d.reader(in(new byte[] {'x'}), "zeros").read(new byte[1]) > 0);
        assertTrue(token.isCanceled());
    }
}
