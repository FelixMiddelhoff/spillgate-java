package io.github.felixmiddelhoff.spillgate;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Objects;

/**
 * The layer guard: everything the specification demands of one layer.
 *
 * <p>Whatever the codec does, the guard never hands out more than {@code maxOutput} bytes in total
 * (SG-I-01), never turns a limit hit into a clean end (SG-I-02), keeps failing with the same error
 * once one occurred (SG-I-03) and contains the failures of the codec (SG-I-05, SG-R-CDC-08): a
 * {@link SpillgateException} passes, a {@code RuntimeException}, {@code StackOverflowError} or any
 * other {@code Throwable} that is not a fatal {@link VirtualMachineError} becomes {@code internal}.
 * An {@code OutOfMemoryError} (or another {@code VirtualMachineError}) releases the layer's state and
 * propagates: the JVM is no longer in a state the library can vouch for.
 *
 * <p>The codec never sees the caller's array. It writes into a private scratch array of at most
 * {@link #MAX_STEP} bytes and the guard copies the validated bytes out, so a codec that ignores its
 * length cannot damage the caller's memory, and it is caught by a sentinel behind the range (or by
 * the array bounds) and ends the layer with {@code internal}.
 *
 * <p>A guard is also the {@link InputStream} that feeds the next layer's {@link InputMeter}. When the
 * layer is terminal (clean end or error) the codec is closed at once and its state released.
 */
final class LayerGuard extends InputStream {
    /**
     * The most a guard asks a codec for in one call (64 KiB). It bounds the memory of one step
     * (SG-R-MEM-02) whatever the size of the caller's buffer; callers get short reads, which every
     * reader contract allows. It is also the tolerance of SG-R-RAT-02: the ratio is judged once per
     * step.
     */
    static final int MAX_STEP = 64 * 1024;

    private static final int SENTINEL = 16;
    private static final byte PATTERN = (byte) 0xA5;
    private static final int MIN_SCRATCH = 512;

    private static final int RUNNING = 0;
    private static final int END = 1;
    private static final int FAILED = 2;

    private volatile CodecReader codec;
    private final InputMeter meter;
    private final int layer;
    private final EffectiveLimits limits;
    private long produced;
    private volatile int state = RUNNING;
    private volatile SpillgateException error;
    private volatile byte[] scratch;
    private final byte[] one = new byte[1];

    LayerGuard(CodecReader codec, InputMeter meter, int layer, EffectiveLimits limits) {
        this.codec = codec;
        this.meter = meter;
        this.layer = layer;
        this.limits = limits;
    }

    /** True once the layer has a terminal error (a clean end is not a failure). */
    boolean failed() {
        return state == FAILED;
    }

    /** Bytes this layer has delivered so far. */
    long produced() {
        return produced;
    }

    private SpillgateException fail(SpillgateException e) {
        SpillgateException named = e.withLayer(layer);
        if (state == RUNNING) {
            error = named; // the error first: nobody may see FAILED without it
            state = FAILED;
        }
        release();
        return state == FAILED ? error : named;
    }

    private void end() {
        if (state == RUNNING) {
            state = END;
        }
        release();
    }

    /** Close the codec (best effort, it must not throw) and drop every buffer and reference. */
    private void release() {
        CodecReader c = codec;
        codec = null;
        scratch = null;
        meter.release();
        if (c != null) {
            try {
                c.close();
            } catch (Throwable ignored) {
                // a close() that throws is a bug of the codec; the layer is released anyway
            }
        }
    }

    /**
     * Up to {@code len} decoded bytes (one step at most) into {@code buf[off, off + len)}; -1 is the
     * clean end of a non-empty request.
     */
    int readStep(byte[] buf, int off, int len) throws SpillgateException {
        if (state == FAILED) {
            throw error.repeat();
        }
        if (len == 0) {
            return 0; // SG-R-RDR-02: a zero-length read changes nothing
        }
        if (state == END) {
            return -1;
        }
        long remaining = limits.maxOutput - produced;
        if (remaining <= 0) {
            return probe();
        }
        int step = (int) Math.min(Math.min((long) len, remaining), (long) MAX_STEP);
        int n = call(step);
        if (n < 0) {
            return -1;
        }
        System.arraycopy(scratch, 0, buf, off, n);
        produced += n;
        // A codec may have hidden a failure of its input and gone on to hold back output. Nothing is
        // dropped here: the codec keeps delivering what it holds, it meets the failure again at its
        // next fetch, and a clean end that hides one is refused in call(). The bytes delivered
        // before a source failure thus do not depend on the read size (SG-R-RDR-01).
        // The bytes that broke the ratio were produced first, so they are still delivered; the
        // error follows on the next read (SG-R-LAY-07).
        if (limits.ratioExceeded(produced, meter.consumed())) {
            fail(new SpillgateException(Kind.RATIO_LIMIT,
                    "output of " + produced + " bytes from " + meter.consumed() + " input bytes",
                    layer, limits.maxRatio, null));
        }
        return n;
    }

    /**
     * The budget is used up: is there one more byte? (SG-R-LAY-03) If the codec yields one, the limit
     * is exceeded and the byte is thrown away. If the stream ends cleanly, the output was exactly at
     * the limit and all is well. The codec's own validation, such as a trailer checksum, still runs
     * inside this call and can still report corruption.
     */
    private int probe() throws SpillgateException {
        int n = call(1);
        if (n < 0) {
            return -1;
        }
        throw fail(new SpillgateException(Kind.OUTPUT_LIMIT,
                "the layer would produce more output than allowed", layer, limits.maxOutput, null));
    }

    /**
     * One codec read of {@code step} bytes into the scratch array with its failures contained
     * (SG-R-CDC-08). Returns -1 for the clean end (the layer is then released) or a count in
     * {@code 1..step}; an error makes the layer terminal and is thrown.
     */
    private int call(int step) throws SpillgateException {
        ensureScratch(step);
        CodecReader c = codec;
        byte[] s = scratch;
        SpillgateException problem = null;
        int n = 0;
        for (int empty = 0; ; ) {
            Arrays.fill(s, step, step + SENTINEL, PATTERN);
            try {
                n = c.read(s, 0, step);
            } catch (SpillgateException e) {
                problem = e;
            } catch (Throwable t) {
                if (t instanceof VirtualMachineError && !(t instanceof StackOverflowError)) {
                    abort();
                    throw (VirtualMachineError) t;
                }
                // Never touch the message or toString() of a hostile throwable.
                problem = new SpillgateException(Kind.INTERNAL, "codec threw " + t.getClass().getName(), layer, 0, t);
            }
            if (problem == null) {
                if (n < -1 || n > step) {
                    problem = new SpillgateException(Kind.INTERNAL,
                            "codec returned an invalid byte count (not in -1.." + step + ")", layer, 0, null);
                } else if (!sentinelIntact(s, step)) {
                    problem = new SpillgateException(Kind.INTERNAL,
                            "codec wrote outside the range it was given", layer, 0, null);
                } else if (n == 0) {
                    if (++empty < InputMeter.MAX_EMPTY_READS) {
                        continue;
                    }
                    problem = new SpillgateException(Kind.INTERNAL, "codec made no progress", layer, 0, null);
                }
            }
            break;
        }
        if (problem == null && n < 0) {
            // A clean end that hides a failure of the input is not a clean end.
            SpillgateException hidden = meter.failure();
            if (hidden == null) {
                end();
                return -1;
            }
            problem = hidden;
        }
        if (problem != null) {
            // A source failure is reported as such, whatever the codec made of it (SG-R-RDR-05).
            SpillgateException failure = meter.failure();
            throw fail(failure != null ? failure : problem);
        }
        return n;
    }

    private static boolean sentinelIntact(byte[] s, int step) {
        for (int i = step; i < step + SENTINEL; i++) {
            if (s[i] != PATTERN) {
                return false;
            }
        }
        return true;
    }

    private void ensureScratch(int step) {
        if (scratch != null && scratch.length - SENTINEL >= step) {
            return;
        }
        int cap = Math.min(MAX_STEP, Math.max(MIN_SCRATCH, Integer.highestOneBit(step - 1) << 1));
        scratch = new byte[cap + SENTINEL];
    }

    private void abort() {
        if (state == RUNNING) {
            error = new SpillgateException(Kind.INTERNAL, "aborted by a fatal JVM error", layer, 0, null);
            state = FAILED;
        }
        release();
    }

    // InputStream view, for the InputMeter of the next layer.

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        Objects.checkFromIndexSize(off, len, b.length);
        return readStep(b, off, len);
    }

    @Override
    public int read() throws IOException {
        int n = readStep(one, 0, 1);
        return n < 0 ? -1 : one[0] & 0xff;
    }

    @Override
    public int available() {
        return 0;
    }

    /** Release the codec. A running layer becomes terminal (internal: it was closed under a reader). */
    @Override
    public void close() {
        if (state == RUNNING) {
            error = new SpillgateException(Kind.INTERNAL, "the layer is closed", layer, 0, null);
            state = FAILED;
        }
        release();
    }

    /** A codec that reads its input and copies it: the codec of an identity-only chain (SG-R-CDC-10). */
    static final class Passthrough implements CodecReader {
        private final CodecInput input;

        Passthrough(CodecInput input) {
            this.input = input;
        }

        @Override
        public int read(byte[] buf, int off, int len) throws IOException {
            if (len == 0) {
                return 0;
            }
            int available = input.fill();
            if (available == 0) {
                return -1;
            }
            int n = Math.min(available, len);
            System.arraycopy(input.buffer(), input.position(), buf, off, n);
            input.consume(n);
            return n;
        }
    }
}
