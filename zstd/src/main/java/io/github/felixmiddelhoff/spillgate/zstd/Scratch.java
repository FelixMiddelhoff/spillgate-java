package io.github.felixmiddelhoff.spillgate.zstd;

import io.github.felixmiddelhoff.spillgate.SpillgateException;

/**
 * The block buffers of one reader: compressed content, literals and the regenerated block. Each is
 * grown on demand to what the block needs (never beyond {@link #BLOCK_MAX}), so tiny blocks stay tiny.
 */
final class Scratch {
    /** The largest block of any frame. */
    static final int BLOCK_MAX = 128 * 1024;

    private static final byte[] EMPTY = new byte[0];

    byte[] content = EMPTY;
    int contentLen;
    byte[] lits = EMPTY;
    int litsLen;
    byte[] out = EMPTY;
    int outLen;

    static byte[] grow(byte[] a, int needed) throws SpillgateException {
        if (needed < 0 || needed > BLOCK_MAX) {
            throw Err.internal("a block buffer of " + needed + " bytes was requested");
        }
        if (a.length >= needed) {
            return a;
        }
        int size = Math.max(needed, Math.min(a.length * 2, BLOCK_MAX));
        byte[] b = new byte[size];
        System.arraycopy(a, 0, b, 0, a.length);
        return b;
    }

    void ensureContent(int n) throws SpillgateException {
        content = grow(content, n);
    }

    void ensureLits(int n) throws SpillgateException {
        lits = grow(lits, n);
    }

    void ensureOut(int n) throws SpillgateException {
        out = grow(out, n);
    }

    long heapBytes() {
        return (long) content.length + lits.length + out.length;
    }

    void release() {
        content = EMPTY;
        lits = EMPTY;
        out = EMPTY;
        contentLen = 0;
        litsLen = 0;
        outLen = 0;
    }
}
