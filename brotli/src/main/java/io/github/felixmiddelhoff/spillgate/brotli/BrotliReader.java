package io.github.felixmiddelhoff.spillgate.brotli;

import io.github.felixmiddelhoff.spillgate.CodecInput;
import io.github.felixmiddelhoff.spillgate.CodecReader;
import io.github.felixmiddelhoff.spillgate.Kind;
import io.github.felixmiddelhoff.spillgate.SpillgateException;
import java.io.IOException;

final class BrotliReader implements CodecReader {
    static final int MAX_STEP = 64 * 1024;

    private final CodecInput input;
    private final int maxWindowBits;
    private State state;
    private boolean ended;
    private boolean pendingEnd;
    private IOException failure;
    /** A failure that follows the bytes the failing call had already produced. */
    private IOException deferred;

    BrotliReader(CodecInput input, int maxWindowBits) {
        this.input = input;
        this.maxWindowBits = maxWindowBits;
    }

    /** True while decoder state is held (tests). */
    boolean holdsState() {
        return state != null;
    }

    @Override
    public int read(byte[] buf, int off, int len) throws IOException {
        if (failure != null) {
            throw failure instanceof SpillgateException s ? s.repeat() : failure;
        }
        if (len == 0) {
            return 0;
        }
        if (deferred != null) {
            return fail(deferred);
        }
        if (ended) {
            return -1;
        }
        try {
            return step(buf, off, len);
        } catch (SpillgateException e) {
            return fail(e);
        } catch (InputFailure e) {
            return fail(e.io());
        } catch (RuntimeException e) {
            // the vendored decoder reports every invalid stream through unchecked exceptions
            return fail(new SpillgateException(Kind.CORRUPT, "brotli: invalid data", 0, 0, e));
        }
    }

    /**
     * The decoder failed in this call after it had already copied bytes into the caller's buffer: they
     * are delivered, the failure follows on the next call, so what is delivered before a failure does
     * not depend on the read size (SG-R-RDR-01).
     */
    private int runDecoder(State s) throws IOException {
        try {
            Decode.decompress(s);
        } catch (RuntimeException e) {
            int produced = s.outputUsed;
            s.output = null;
            if (produced <= 0) {
                throw e;
            }
            state = null;
            deferred = e instanceof InputFailure f
                    ? f.io()
                    : new SpillgateException(Kind.CORRUPT, "brotli: invalid data", 0, 0, e);
            return produced;
        }
        return -1;
    }

    private int fail(IOException e) throws IOException {
        state = null;
        failure = e;
        throw e;
    }

    private void start() throws IOException {
        if (input.fill() <= 0) {
            throw new SpillgateException(Kind.CORRUPT, "brotli: an empty input is not a brotli stream");
        }
        int bits = BrotliCodec.windowBits(input.buffer()[input.position()] & 0xff);
        if (bits == 0) {
            throw new SpillgateException(Kind.MEMORY_LIMIT, "brotli stream asks for a large window (not supported)");
        }
        if (bits > maxWindowBits) {
            throw new SpillgateException(
                    Kind.MEMORY_LIMIT,
                    "brotli stream asks for a window of 2^" + bits + " bytes (the cap is 2^" + maxWindowBits + ")",
                    0,
                    1L << maxWindowBits,
                    null);
        }
        State s = new State();
        State.setInput(s, input);
        state = s;
    }

    private int step(byte[] buf, int off, int len) throws IOException {
        if (pendingEnd) {
            checkEnd();
            return -1;
        }
        if (state == null) {
            start();
        }
        State s = state;
        s.output = buf;
        s.outputOffset = off;
        s.outputLength = Math.min(len, MAX_STEP);
        s.outputUsed = 0;
        int failed = runDecoder(s);
        if (failed > 0) {
            return failed;
        }
        int n = s.outputUsed;
        s.output = null;
        if (s.runningState == RunningState.FINISHED) {
            if (n > 0) {
                pendingEnd = true;
                return n;
            }
            checkEnd();
            return -1;
        }
        if (n <= 0) {
            throw new SpillgateException(Kind.INTERNAL, "brotli: the decoder made no progress");
        }
        return n;
    }

    /** The last meta-block is complete: nothing may follow it. */
    private void checkEnd() throws IOException {
        BitReader br = state.br;
        int unused = BitReader.unusedBytes(br);
        if (unused != 0 || (!br.atEndOfInput() && input.fill() > 0)) {
            throw new SpillgateException(Kind.CORRUPT, "brotli: unexpected data after the end of the stream");
        }
        pendingEnd = false;
        ended = true;
        state = null;
    }

    @Override
    public void close() {
        state = null;
    }
}
