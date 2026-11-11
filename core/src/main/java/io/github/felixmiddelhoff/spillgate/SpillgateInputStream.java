package io.github.felixmiddelhoff.spillgate;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

/**
 * The decoded bytes of a source, read through the usual {@link InputStream} API, under the limits of
 * the {@link Decoder} that created it.
 *
 * <ul>
 *   <li>Reading can deliver bytes and then fail: treat any error as failure of the whole body
 *       (SG-R-LAY-06). A failure is a {@link SpillgateException} (an {@link IOException}); its
 *       {@link SpillgateException#kind()} is the outcome class.
 *   <li>Errors are sticky: every later read fails with an error of the same kind (SG-R-LAY-05), and
 *       a clean end ({@code -1}) is sticky too.
 *   <li>A single {@code read(byte[], int, int)} returns at most 64 KiB, whatever the size of the
 *       buffer; {@code skip}, {@code readAllBytes}, {@code readNBytes} and {@code transferTo} work
 *       through {@code read} and are therefore bounded by the limiter.
 *   <li>{@link #available()} always returns 0 (the JDK's {@code available()} of decompressing streams
 *       lies, so this one makes no promise).
 *   <li>{@link #close()} is idempotent and releases every codec (native memory included) and buffer.
 *       It does <em>not</em> close the caller's source (SG-R-RDR-06). A read after {@code close()}
 *       throws a plain {@link IOException} ("stream closed"), not a {@link SpillgateException}.
 *       Nothing relies on {@code finalize} or a {@code Cleaner}: close the stream.
 *   <li>A stream is not safe for concurrent use by several threads (SG-R-RDR-07); different streams
 *       are independent. A {@link CancelToken} is the way to stop one from another thread.
 * </ul>
 */
public final class SpillgateInputStream extends InputStream {
    private static final int RUNNING = 0;
    private static final int END = 1;
    private static final int FAILED = 2;

    private final LayerGuard top;
    private final InputMeter sourceMeter;
    private final LayerGuard[] guards;
    private final EffectiveLimits limits;
    private final CancelToken cancel;
    private final byte[] one = new byte[1];
    private long produced;
    private volatile int state = RUNNING;
    private volatile SpillgateException error;
    private volatile boolean closed;

    SpillgateInputStream(LayerGuard[] guards, InputMeter sourceMeter, EffectiveLimits limits, CancelToken cancel) {
        this.guards = guards;
        this.top = guards[guards.length - 1];
        this.sourceMeter = sourceMeter;
        this.limits = limits;
        this.cancel = cancel;
    }

    /**
     * Reads up to {@code min(len, 65536)} decoded bytes. Returns -1 at the clean end of the body.
     *
     * @throws SpillgateException a limit, a corrupt stream, a failing source, a cancellation; sticky
     * @throws IOException {@code "stream closed"} after {@link #close()}
     */
    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        Objects.checkFromIndexSize(off, len, b.length);
        if (closed) {
            throw new IOException("stream closed");
        }
        try {
            return readChecked(b, off, len);
        } catch (VirtualMachineError e) {
            abort();
            throw e;
        } catch (RuntimeException | SpillgateException e) {
            if (closed) {
                // close() from another thread released the codecs under this read: whatever it
                // tripped over (a null codec, a released input) is only "stream closed".
                throw new IOException("stream closed");
            }
            throw e;
        }
    }

    private int readChecked(byte[] b, int off, int len) throws SpillgateException {
        if (state == FAILED) {
            throw error.repeat();
        }
        if (state == END) {
            return len == 0 ? 0 : -1; // a clean end is sticky, a later cancel does not change it
        }
        if (cancel != null && cancel.isCanceled()) {
            throw fail(new SpillgateException(Kind.CANCELED, "the stream was canceled"));
        }
        if (len == 0) {
            return 0; // SG-R-RDR-02
        }
        int n;
        try {
            n = top.readStep(b, off, Math.min(len, LayerGuard.MAX_STEP));
        } catch (SpillgateException e) {
            throw fail(e);
        }
        if (n < 0) {
            state = END;
            releaseAll();
            return -1;
        }
        produced += n;
        // SG-R-RAT-03: the same ratio test, end to end. The error follows these bytes. A layer that
        // already failed has the more specific error, so it wins.
        if (!top.failed() && limits.ratioExceeded(produced, sourceMeter.consumed())) {
            fail(new SpillgateException(Kind.RATIO_LIMIT, "end-to-end expansion over the whole chain",
                    0, limits.maxRatio, null));
        }
        return n;
    }

    private SpillgateException fail(SpillgateException e) {
        if (state != FAILED) {
            error = e; // the error first: nobody may see FAILED without it
            state = FAILED;
        }
        releaseAll();
        return error;
    }

    private void abort() {
        if (state != FAILED) {
            error = new SpillgateException(Kind.INTERNAL, "aborted by a fatal JVM error");
            state = FAILED;
        }
        releaseAll();
    }

    private void releaseAll() {
        for (LayerGuard guard : guards) {
            guard.close();
        }
    }

    @Override
    public int read() throws IOException {
        int n = read(one, 0, 1);
        return n < 0 ? -1 : one[0] & 0xff;
    }

    /** Always 0: no promise about buffered data is made. */
    @Override
    public int available() {
        return 0;
    }

    /** Release all codec state. Idempotent; the caller's source is not closed (SG-R-RDR-06). */
    @Override
    public void close() {
        closed = true;
        releaseAll();
    }
}
