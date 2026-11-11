package io.github.felixmiddelhoff.spillgate.zstd;

/** Streaming XXH64 with seed 0, the hash behind the zstd content checksum (low 32 bits stored). */
final class Xxh64 {
    private static final long P1 = 0x9E3779B185EBCA87L;
    private static final long P2 = 0xC2B2AE3D27D4EB4FL;
    private static final long P3 = 0x165667B19E3779F9L;
    private static final long P4 = 0x85EBCA77C2B2AE63L;
    private static final long P5 = 0x27D4EB2F165667C5L;

    private long v1 = P1 + P2;
    private long v2 = P2;
    private long v3 = 0;
    private long v4 = -P1;
    private final byte[] tail = new byte[32];
    private int tailLen;
    private long total;

    private static long round(long acc, long input) {
        return Long.rotateLeft(acc + input * P2, 31) * P1;
    }

    private static long merge(long acc, long value) {
        return (acc ^ round(0, value)) * P1 + P4;
    }

    private static long le64(byte[] b, int i) {
        long v = 0;
        for (int k = 7; k >= 0; k--) {
            v = (v << 8) | (b[i + k] & 0xffL);
        }
        return v;
    }

    private static long le32(byte[] b, int i) {
        return (b[i] & 0xffL) | (b[i + 1] & 0xffL) << 8 | (b[i + 2] & 0xffL) << 16 | (b[i + 3] & 0xffL) << 24;
    }

    private void stripe(byte[] b, int i) {
        v1 = round(v1, le64(b, i));
        v2 = round(v2, le64(b, i + 8));
        v3 = round(v3, le64(b, i + 16));
        v4 = round(v4, le64(b, i + 24));
    }

    void update(byte[] data, int off, int len) {
        total += len;
        int end = off + len;
        if (tailLen > 0) {
            int take = Math.min(32 - tailLen, len);
            System.arraycopy(data, off, tail, tailLen, take);
            tailLen += take;
            off += take;
            if (tailLen < 32) {
                return;
            }
            stripe(tail, 0);
            tailLen = 0;
        }
        while (end - off >= 32) {
            stripe(data, off);
            off += 32;
        }
        int rest = end - off;
        System.arraycopy(data, off, tail, 0, rest);
        tailLen = rest;
    }

    /** The full 64-bit digest. */
    long digest() {
        long h;
        if (total >= 32) {
            h = Long.rotateLeft(v1, 1) + Long.rotateLeft(v2, 7) + Long.rotateLeft(v3, 12) + Long.rotateLeft(v4, 18);
            h = merge(merge(merge(merge(h, v1), v2), v3), v4);
        } else {
            h = P5;
        }
        h += total;
        int i = 0;
        while (tailLen - i >= 8) {
            h = Long.rotateLeft(h ^ round(0, le64(tail, i)), 27) * P1 + P4;
            i += 8;
        }
        if (tailLen - i >= 4) {
            h = Long.rotateLeft(h ^ (le32(tail, i) * P1), 23) * P2 + P3;
            i += 4;
        }
        while (i < tailLen) {
            h = Long.rotateLeft(h ^ ((tail[i] & 0xffL) * P5), 11) * P1;
            i++;
        }
        h ^= h >>> 33;
        h *= P2;
        h ^= h >>> 29;
        h *= P3;
        return h ^ (h >>> 32);
    }

    /** The low 32 bits of the digest, as a non-negative long: what a zstd frame stores. */
    long digest32() {
        return digest() & 0xffffffffL;
    }
}
