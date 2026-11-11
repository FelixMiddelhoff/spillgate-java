package io.github.felixmiddelhoff.spillgate.zstd;

import io.github.felixmiddelhoff.spillgate.SpillgateException;

/**
 * The window of recent output: a ring of the declared size that grows with the output, never eagerly
 * to the declared size, so a hostile header cannot turn a few input bytes into a large allocation.
 */
final class Window {
    private byte[] buf = new byte[0];
    /** The ring size (the declared window, at least 1). Positions wrap here, not at {@code buf.length}. */
    private final int ring;
    /** Write position, always below ring. Until the ring is full this is the number of bytes written. */
    private int pos;
    /** Bytes produced by the frame so far. */
    private long total;

    Window(int windowSize) {
        this.ring = Math.max(windowSize, 1);
    }

    long total() {
        return total;
    }

    int capacity() {
        return buf.length;
    }

    int posForTest() {
        return pos;
    }

    private void ensure(int extra) throws SpillgateException {
        long wanted = Math.min(total + extra, (long) ring);
        int target = (int) Math.max(wanted, 1);
        int len = buf.length;
        if (len >= target) {
            return;
        }
        int grown = (int) Math.min(Math.max((long) target, (long) len * 2), (long) ring);
        try {
            byte[] b = new byte[grown];
            System.arraycopy(buf, 0, b, 0, len);
            buf = b;
        } catch (OutOfMemoryError e) {
            buf = new byte[0];
            throw Err.memory("the window could not be allocated", ring);
        }
    }

    /** Append a finished block to the window. */
    void push(byte[] bytes, int off, int len) throws SpillgateException {
        if (len == 0) {
            return;
        }
        ensure(len);
        int n = buf.length;
        if (len >= n && n == ring) {
            // the newest n bytes fill the whole ring; the oldest of them sits at index 0
            System.arraycopy(bytes, off + len - n, buf, 0, n);
            pos = 0;
        } else {
            int start = pos;
            if (start > n) {
                throw Err.internal("the window ring is inconsistent");
            }
            int first = Math.min(len, n - start);
            System.arraycopy(bytes, off, buf, start, first);
            int rest = len - first;
            if (rest > n) {
                throw Err.internal("the window ring is inconsistent");
            }
            System.arraycopy(bytes, off + first, buf, 0, rest);
            pos = (int) (((long) start + len) % ring);
        }
        total += len;
    }

    /**
     * Append {@code n} bytes to {@code out} at {@code outPos}, taken from the history {@code d} bytes
     * before the write position. The caller guarantees {@code n <= d <= min(total, ring)}.
     */
    void copyHistory(int d, int n, byte[] out, int outPos) throws SpillgateException {
        long idxLong = d <= pos ? (long) pos - d : (long) pos + ring - d;
        if (idxLong < 0 || idxLong > buf.length) {
            throw Err.internal("the window ring is inconsistent");
        }
        int idx = (int) idxLong;
        int first = Math.min(n, buf.length - idx);
        System.arraycopy(buf, idx, out, outPos, first);
        int rest = n - first;
        if (rest > 0) {
            if (rest > buf.length) {
                throw Err.internal("the window ring is inconsistent");
            }
            System.arraycopy(buf, 0, out, outPos + first, rest);
        }
    }
}
