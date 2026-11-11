package io.github.felixmiddelhoff.spillgate.testsupport;

import java.util.Arrays;

/** Writes DEFLATE-style fields: least significant bit first; Huffman codes most significant bit first. */
public final class BitWriter {
    private byte[] out = new byte[64];
    private int len;
    private long acc;
    private int count;

    private void push(int b) {
        if (len == out.length) {
            out = Arrays.copyOf(out, Math.multiplyExact(out.length, 2));
        }
        out[len++] = (byte) b;
    }

    /** Write the low {@code n} bits of {@code value} (n at most 32). */
    public void bits(long value, int n) {
        acc |= value << count;
        count += n;
        while (count >= 8) {
            push((int) (acc & 0xff));
            acc >>>= 8;
            count -= 8;
        }
    }

    /** Write an {@code n} bit Huffman code, most significant bit first. */
    public void code(long code, int n) {
        long reversed = 0;
        for (int i = 0; i < n; i++) {
            reversed = (reversed << 1) | ((code >>> i) & 1);
        }
        bits(reversed, n);
    }

    /** Write {@code n} zero bits; whole bytes in bulk. */
    public void zeroBits(long n) {
        long head = Math.min(n, (8 - count) % 8);
        bits(0, (int) head);
        long rest = n - head;
        if (count == 0) {
            long bytes = rest / 8;
            long total = Math.addExact(len, bytes);
            if (total > Integer.MAX_VALUE - 16) {
                throw new IllegalStateException("stream too large");
            }
            if (total > out.length) {
                out = Arrays.copyOf(out, (int) Math.max(total, Math.min((long) out.length * 2, Integer.MAX_VALUE - 16)));
            }
            len = (int) total; // bytes past len are never written, so the new region is zero
            bits(0, (int) (rest % 8));
        }
    }

    /** Pad with zero bits to the next byte boundary. */
    public void align() {
        if (count > 0) {
            push((int) (acc & 0xff));
            acc = 0;
            count = 0;
        }
    }

    /** Append whole bytes; only valid on a byte boundary. */
    public void raw(byte[] bytes, int off, int n) {
        for (int i = 0; i < n; i++) {
            push(bytes[off + i]);
        }
    }

    /** Pad to a byte boundary and return the bytes. */
    public byte[] finish() {
        align();
        return Arrays.copyOf(out, len);
    }
}
