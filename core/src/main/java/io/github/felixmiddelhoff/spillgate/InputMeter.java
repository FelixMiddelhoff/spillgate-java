package io.github.felixmiddelhoff.spillgate;

import java.io.IOException;
import java.io.InputStream;

/**
 * The input meter: counts what a codec takes and guards the source.
 *
 * <p>A codec never talks to the caller's source directly. It takes its compressed bytes from an
 * {@code InputMeter}, which
 *
 * <ul>
 *   <li>counts the bytes the codec actually <em>consumes</em> (not the bytes it fetched from the
 *       source), so a read-ahead buffer never gives free ratio credit (SG-R-RAT-02);
 *   <li>fetches at most {@link #BUFFER_SIZE} bytes at a time, far below the 64 KiB look-ahead
 *       tolerance;
 *   <li>enforces the optional {@code maxInput} cap with one probe byte, never requesting more than
 *       {@code maxInput + 1} bytes in total (SG-R-INP-01, SG-R-INP-02);
 *   <li>defends against misbehaving sources: one that reports more than it was asked for or a
 *       negative count other than -1 is {@code internal} (SG-R-RDR-04), one that returns 0 bytes 100
 *       times in a row is {@code internal} (SG-R-RDR-03), one that throws is {@code source_error}
 *       (SG-R-RDR-05), a {@link SpillgateException} from a lower layer passes through unchanged;
 *   <li>checks the {@link CancelToken} on every fetch (SG-R-API-04);
 *   <li>never reads the source again after the first terminal condition (SG-R-LAY-09).
 * </ul>
 *
 * <p>For layer 1 the source is the caller's stream, for every other layer the guarded stream of the
 * layer below. Not thread safe (SG-R-RDR-07).
 */
final class InputMeter implements CodecInput {
    /** Bytes fetched from the source at a time. */
    static final int BUFFER_SIZE = 4096;
    /** SG-R-RDR-03: this many fruitless reads in a row are "no progress". */
    static final int MAX_EMPTY_READS = 100;

    private InputStream source;
    private final byte[] buf = new byte[BUFFER_SIZE];
    private int pos;
    private int filled;
    /** End of the part of the buffer the codec was shown by the last {@link #fill()}. */
    private int visible;
    private long fetched;
    private long consumed;
    private final long maxInput;
    private final int layer;
    private final CancelToken cancel;
    private boolean ended;
    private SpillgateException failure;

    /** {@code maxInput} 0 means no cap. {@code layer} is the 1-based index used on errors. */
    InputMeter(InputStream source, long maxInput, int layer, CancelToken cancel) {
        this.source = source;
        this.maxInput = maxInput;
        this.layer = layer;
        this.cancel = cancel;
    }

    /** Bytes the codec has consumed so far (the C(k) of the specification). */
    long consumed() {
        return consumed;
    }

    /** The terminal error this meter raised, if any: a source failure, the input limit, a lower layer's error. */
    SpillgateException failure() {
        return failure;
    }

    /** Drop the reference to the source. Does not close it (the caller owns it). */
    void release() {
        source = null;
    }

    private SpillgateException fail(SpillgateException error) {
        SpillgateException named = error.withLayer(layer);
        failure = named;
        return named;
    }

    private SpillgateException inputLimit() {
        return new SpillgateException(Kind.INPUT_LIMIT,
                "the stream needs more than " + maxInput + " input bytes", layer, maxInput, null);
    }

    /** How much to ask the source for: with a cap never beyond {@code maxInput + 1} bytes in total. */
    private int fetchSize() {
        if (maxInput == 0) {
            return BUFFER_SIZE;
        }
        long room = maxInput - fetched;
        if (room < 0) {
            return 0;
        }
        return room >= BUFFER_SIZE ? BUFFER_SIZE : (int) (room + 1);
    }

    /** Fetch the next chunk from the source into the (empty) buffer. */
    private void refill() throws SpillgateException {
        pos = 0;
        filled = 0;
        if (ended) {
            return;
        }
        InputStream in = source;
        if (in == null) {
            throw new SpillgateException(Kind.INTERNAL, "the input was released", layer, 0, null);
        }
        int want = fetchSize();
        if (want == 0) {
            throw inputLimit();
        }
        for (int empty = 0; ; ) {
            int n;
            try {
                n = in.read(buf, 0, want);
            } catch (SpillgateException e) {
                throw e;
            } catch (IOException | RuntimeException e) {
                throw new SpillgateException(Kind.SOURCE_ERROR, "reading the source failed", layer, 0, e);
            }
            if (n == -1) {
                ended = true;
                return;
            }
            if (n < -1 || n > want) {
                throw new SpillgateException(Kind.INTERNAL,
                        "the source returned an invalid byte count (not in -1.." + want + ")", layer, 0, null);
            }
            if (n == 0) {
                if (++empty >= MAX_EMPTY_READS) {
                    throw new SpillgateException(Kind.INTERNAL, "the source made no progress", layer, 0, null);
                }
                continue;
            }
            filled = n;
            fetched += n;
            return;
        }
    }

    @Override
    public int fill() throws IOException {
        if (failure != null) {
            throw failure.repeat();
        }
        if (cancel != null && cancel.isCanceled()) {
            throw fail(new SpillgateException(Kind.CANCELED, "the stream was canceled"));
        }
        if (pos >= filled) {
            try {
                refill();
            } catch (SpillgateException e) {
                throw fail(e);
            }
        }
        int end = filled;
        if (maxInput > 0 && pos < filled) {
            long allowance = maxInput - consumed;
            if (allowance <= 0) {
                // The probe byte exists: a MaxInput+1-th byte is being taken (SG-R-INP-01).
                throw fail(inputLimit());
            }
            if (allowance < end - pos) {
                end = pos + (int) allowance;
            }
        }
        visible = end;
        return end - pos;
    }

    @Override
    public byte[] buffer() {
        return buf;
    }

    @Override
    public int position() {
        return pos;
    }

    @Override
    public void consume(int n) {
        // A codec must not consume more than it was shown; clamp so that the cap cannot be
        // bypassed and the count stays exact.
        int shown = visible - pos;
        if (n > shown) {
            n = shown;
        }
        if (n <= 0) {
            return;
        }
        pos += n;
        consumed += n;
    }
}
