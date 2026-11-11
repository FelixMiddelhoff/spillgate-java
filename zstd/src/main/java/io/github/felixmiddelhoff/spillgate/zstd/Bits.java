package io.github.felixmiddelhoff.spillgate.zstd;

import io.github.felixmiddelhoff.spillgate.SpillgateException;

/** Bit readers (RFC 8878): forward for table descriptions, backward for the data. */
final class Bits {
    private Bits() {}

    /**
     * Little-endian value of {@code n <= 32} bits starting at bit {@code pos} of {@code a[from, to)};
     * bits past the end read as 0. {@code pos >= 0}.
     */
    static long bitsAt(byte[] a, int from, int to, long pos, int n) {
        if (n == 0) {
            return 0;
        }
        int shift = (int) (pos & 7);
        long first = (pos >>> 3) + from;
        int count = ((shift + n - 1) >> 3) + 1; // at most 5 bytes: 40 bits
        long value = 0;
        for (int k = 0; k < count; k++) {
            long i = first + k;
            if (i < to) {
                value |= (long) (a[(int) i] & 0xff) << (8 * k);
            }
        }
        return (value >>> shift) & ((1L << n) - 1);
    }

    /** Reads bits starting at the least significant bit of the first byte (FSE table descriptions). */
    static final class Forward {
        private final byte[] a;
        private final int from;
        private final int to;
        private long pos;

        Forward(byte[] a, int from, int to) {
            this.a = a;
            this.from = from;
            this.to = to;
        }

        /** Read {@code n <= 24} bits. */
        int read(int n) {
            long value = bitsAt(a, from, to, pos, n);
            pos += n;
            return (int) value;
        }

        /** Put back {@code n} bits just read. */
        void unread(int n) {
            pos = Math.max(0, pos - n);
        }

        /** The number of bytes touched so far (the position rounded up to a whole byte). */
        int bytesUsed() {
            return (int) Math.min(Integer.MAX_VALUE, (pos + 7) >>> 3);
        }

        /** True if more bits were read than the buffer holds. */
        boolean overrun() {
            return pos > (long) (to - from) * 8;
        }
    }

    /**
     * Reads bits from the end of a buffer towards its start. The last byte holds a padding sentinel:
     * its highest set bit marks where the data ends. Reading past the start yields zero bits and
     * {@link #remaining} goes negative, so the caller can tell.
     */
    static final class Backward {
        private final byte[] a;
        private final int from;
        private final int to;
        /** Bits left to read; negative after reading past the start. */
        long remaining;

        Backward(byte[] a, int from, int to) throws SpillgateException {
            if (from < 0 || to > a.length || to <= from || a[to - 1] == 0) {
                throw Err.corrupt("a bit stream ends without its end mark");
            }
            this.a = a;
            this.from = from;
            this.to = to;
            int last = a[to - 1] & 0xff;
            int leading = Integer.numberOfLeadingZeros(last) - 24; // within 8 bits
            this.remaining = (long) (to - from - 1) * 8 + (7 - leading);
        }

        /** Read {@code n <= 32} bits and move back. */
        long read(int n) {
            if (n == 0) {
                return 0;
            }
            long pos = remaining - n;
            remaining = pos;
            return at(pos, n);
        }

        /** Look at the next {@code n} bits without moving. */
        long peek(int n) {
            if (n == 0) {
                return 0;
            }
            return at(remaining - n, n);
        }

        private long at(long pos, int n) {
            if (pos >= 0) {
                return bitsAt(a, from, to, pos, n);
            }
            // some of the bits lie before the start: they read as zeros at the low end
            long missing = -pos;
            if (missing >= n) {
                return 0;
            }
            return bitsAt(a, from, to, 0, n - (int) missing) << missing;
        }
    }
}
