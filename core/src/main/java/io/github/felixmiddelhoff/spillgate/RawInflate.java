package io.github.felixmiddelhoff.spillgate;

import java.io.IOException;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * The DEFLATE body (RFC 1951) for the gzip and deflate codecs: a raw {@link Inflater} behind exact
 * input accounting and bounded output. The JDK inflater is native memory: {@link #release()} frees it
 * on every terminal condition and in {@code close()}.
 *
 * <p>Bytes the inflater produced in the same call that then fails are still delivered, and the error
 * is raised on the next call, so the delivered prefix of a corrupt stream does not depend on the read
 * size or the input chunking (SG-R-RDR-01, SG-I-09).
 */
final class RawInflate {
    private final Inflater inflater = new Inflater(true);
    private final byte[] prefix = new byte[2];
    private int prefixPos;
    private int prefixLen;
    private static final byte[] NO_INPUT = new byte[0];
    private boolean done;
    private boolean released;
    private SpillgateException pending;

    /** Prepare for a new stream (gzip: the next member). */
    void reset() {
        inflater.reset();
        done = false;
        pending = null;
        prefixPos = 0;
        prefixLen = 0;
    }

    /** Input bytes that were already taken from the {@link CodecInput} and belong to the stream. */
    void setPrefix(int b0, int b1) {
        prefix[0] = (byte) b0;
        prefix[1] = (byte) b1;
        prefixPos = 0;
        prefixLen = 2;
    }

    /** Prefix bytes the stream did not use (they are trailing data once the stream has ended). */
    int unusedPrefix() {
        return prefixLen;
    }

    /**
     * Decode into {@code buf[off, off+len)}, {@code len > 0}. Returns the bytes written (at least 1), or
     * -1 once the final block has been decoded; the unused input stays in the {@code input}.
     */
    int step(CodecInput input, byte[] buf, int off, int len) throws IOException {
        while (true) {
            if (pending != null) {
                throw pending.repeat();
            }
            if (done) {
                return -1;
            }
            byte[] src;
            int pos;
            int avail;
            boolean fromPrefix = prefixLen > 0;
            boolean draining = false;
            if (fromPrefix) {
                src = prefix;
                pos = prefixPos;
                avail = prefixLen;
            } else {
                avail = input.fill();
                if (avail == 0) {
                    // The input is at its end, but the inflater may still hold decodable symbols or a
                    // pending match: drain them first, so that what a truncated or corrupt stream
                    // delivers does not depend on read sizes or chunking (SG-R-RDR-01).
                        src = NO_INPUT;
                    pos = 0;
                    draining = true;
                } else {
                    src = input.buffer();
                    pos = input.position();
                }
            }
            // The inflater keeps the array: it stays valid until inflate returns.
            inflater.setInput(src, pos, avail);
            long readBefore = inflater.getBytesRead();
            long writtenBefore = inflater.getBytesWritten();
            String failure = null;
            try {
                inflater.inflate(buf, off, len);
                if (inflater.needsDictionary()) {
                    failure = "deflate data needs a dictionary";
                }
            } catch (DataFormatException e) {
                failure = "invalid deflate data (" + e.getMessage() + ")";
            }
            int used = (int) (inflater.getBytesRead() - readBefore);
            if (draining && inflater.getBytesWritten() == writtenBefore && failure == null && !inflater.finished()) {
                throw new SpillgateException(Kind.CORRUPT, "truncated deflate data");
            }
            int written = (int) (inflater.getBytesWritten() - writtenBefore);
            if (fromPrefix) {
                prefixPos += used;
                prefixLen -= used;
            } else if (!draining) {
                input.consume(used);
            }
            if (failure != null) {
                release();
                SpillgateException error = new SpillgateException(Kind.CORRUPT, failure);
                if (written > 0) {
                    pending = error;
                    return written;
                }
                throw error;
            }
            if (inflater.finished()) {
                done = true;
                if (written > 0) {
                    return written;
                }
                return -1;
            }
            if (written > 0) {
                return written;
            }
            if (used == 0) {
                throw new SpillgateException(Kind.INTERNAL, "inflater made no progress");
            }
        }
    }

    /** Free the native state. Idempotent. */
    void release() {
        if (!released) {
            released = true;
            inflater.end();
        }
    }
}
