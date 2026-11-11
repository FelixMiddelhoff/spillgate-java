package io.github.felixmiddelhoff.spillgate.testsupport;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Builds the generated conformance inputs of {@code spec/generators.md} without a compression library. The SHA-256 of
 * each generated stream in {@code conformance/manifest.json} proves that this code builds the reference bytes.
 */
public final class Generators {
    private Generators() { }

    private static final long MAX_MATCH = 258;
    private static final int STORED_BLOCK_LIMIT = 65535;
    /** The largest brotli meta-block this generator writes. */
    public static final long BROTLI_METABLOCK_MAX = 1L << 24;
    private static final long BROTLI_MIN_COMMAND_BYTES = 4 + 2118;
    /** The largest zstd block. */
    public static final long ZSTD_BLOCK_MAX = 128 * 1024;

    /** Data(seed, size): xorshift64 bytes that do not compress. */
    public static byte[] data(long seed, int size) {
        long state = seed * 0x9e3779b97f4a7c15L + 1;
        byte[] out = new byte[size];
        for (int i = 0; i < size; i++) {
            state ^= state << 13;
            state ^= state >>> 7;
            state ^= state << 17;
            out[i] = (byte) (state >>> 24);
        }
        return out;
    }

    private static byte[] concat(byte[]... parts) {
        long total = 0;
        for (byte[] p : parts) {
            total += p.length;
        }
        byte[] out = new byte[Math.toIntExact(total)];
        int pos = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, pos, p.length);
            pos += p.length;
        }
        return out;
    }

    private static void le16(byte[] a, int at, int v) {
        a[at] = (byte) v;
        a[at + 1] = (byte) (v >>> 8);
    }

    private static byte[] le32(long v) {
        return new byte[] {(byte) v, (byte) (v >>> 8), (byte) (v >>> 16), (byte) (v >>> 24)};
    }

    /** StoredDeflate: stored blocks of at most 65535 bytes; empty data is one empty final block. */
    public static byte[] storedDeflate(byte[] payload) {
        int blocks = Math.max(1, (payload.length + STORED_BLOCK_LIMIT - 1) / STORED_BLOCK_LIMIT);
        byte[] out = new byte[Math.toIntExact(payload.length + 5L * blocks)];
        int pos = 0;
        int o = 0;
        while (true) {
            int n = Math.min(payload.length - pos, STORED_BLOCK_LIMIT);
            boolean last = pos + n == payload.length;
            out[o++] = (byte) (last ? 1 : 0);
            le16(out, o, n);
            le16(out, o + 2, ~n & 0xffff);
            o += 4;
            System.arraycopy(payload, pos, out, o, n);
            o += n;
            pos += n;
            if (pos >= payload.length) {
                return out;
            }
        }
    }

    private static void zeroRun(BitWriter w, int count) {
        w.code(0, 1);
        w.bits(count - 11, 7);
    }

    /** ZerosDeflate: one dynamic Huffman block decoding to {@code 1 + 258 * matches} zero bytes. */
    public static byte[] zerosDeflate(long matches) {
        BitWriter w = new BitWriter();
        w.bits(1, 1); // BFINAL
        w.bits(2, 2); // BTYPE dynamic
        w.bits(29, 5); // HLIT
        w.bits(1, 5); // HDIST
        w.bits(14, 4); // HCLEN
        for (int length : new int[] {0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2, 0, 2}) {
            w.bits(length, 3);
        }
        w.code(0b11, 2); // literal 0: length 2
        zeroRun(w, 138);
        zeroRun(w, 117);
        w.code(0b11, 2); // symbol 256
        zeroRun(w, 28);
        w.code(0b10, 2); // symbol 285
        w.code(0b10, 2); // distance symbol 0
        w.code(0b10, 2); // distance symbol 1
        w.code(0b10, 2); // literal 0
        w.zeroBits(Math.multiplyExact(2, matches));
        w.code(0b11, 2); // end of block
        return w.finish();
    }

    /** The decoded size of {@code zerosDeflate(matches)}. */
    public static long zerosDecodedSize(long matches) {
        return 1 + MAX_MATCH * matches;
    }

    private static final int CRC_POLY = 0xedb88320;

    private static final int[] CRC_TABLE = new int[256];

    static {
        for (int i = 0; i < 256; i++) {
            int c = i;
            for (int k = 0; k < 8; k++) {
                c = (c & 1) != 0 ? (c >>> 1) ^ CRC_POLY : c >>> 1;
            }
            CRC_TABLE[i] = c;
        }
    }

    /** CRC-32 (IEEE) of {@code payload}. */
    public static int crc32(byte[] payload) {
        int crc = ~0;
        for (byte b : payload) {
            crc = CRC_TABLE[(crc ^ b) & 0xff] ^ (crc >>> 8);
        }
        return ~crc;
    }

    /** Multiplication modulo the CRC polynomial in the reflected domain (zlib's multmodp). */
    private static int multmodp(int a, int b) {
        int m = 1 << 31;
        int p = 0;
        int bb = b;
        while (true) {
            if ((a & m) != 0) {
                p ^= bb;
                if ((a & (m - 1)) == 0) {
                    break;
                }
            }
            m >>>= 1;
            bb = (bb & 1) != 0 ? (bb >>> 1) ^ CRC_POLY : bb >>> 1;
        }
        return p;
    }

    /** The CRC-32 of {@code n} zero bytes, in O(log n). */
    public static int crc32OfZeros(long n) {
        int[] powers = new int[32];
        int p = 1 << 30; // x^1
        powers[0] = p;
        for (int i = 1; i < 32; i++) {
            p = multmodp(p, p);
            powers[i] = p;
        }
        int result = 1 << 31; // x^0
        int k = 3;
        long rest = n;
        while (rest != 0) {
            if ((rest & 1) != 0) {
                result = multmodp(powers[k & 31], result);
            }
            rest >>>= 1;
            k++;
        }
        return ~multmodp(result, ~0);
    }

    /** The Adler-32 of n zero bytes: a stays 1, b counts the bytes. */
    public static int adler32OfZeros(long n) {
        return (int) (((n % 65521) << 16) | 1);
    }

    /** Adler-32 (RFC 1950). */
    public static int adler32(byte[] payload) {
        long a = 1;
        long b = 0;
        int i = 0;
        while (i < payload.length) {
            int end = Math.min(payload.length, i + 5552);
            for (; i < end; i++) {
                a += payload[i] & 0xff;
                b += a;
            }
            a %= 65521;
            b %= 65521;
        }
        return (int) ((b << 16) | a);
    }

    /** A gzip member around a raw DEFLATE stream; {@code crc} and {@code size} describe the decoded data. */
    public static byte[] gzipMember(byte[] deflate, int crc, long size) {
        return concat(new byte[] {0x1f, (byte) 0x8b, 8, 0, 0, 0, 0, 0, 0, (byte) 0xff}, deflate,
                le32(crc & 0xffffffffL), le32(size & 0xffffffffL));
    }

    /** A zlib stream around a raw DEFLATE stream. */
    public static byte[] zlibWrap(byte[] deflate, int adler) {
        return concat(new byte[] {0x78, 0x01}, deflate,
                new byte[] {(byte) (adler >>> 24), (byte) (adler >>> 16), (byte) (adler >>> 8), (byte) adler});
    }

    /** gzip around stored blocks. */
    public static byte[] gzipStored(byte[] payload) {
        return gzipMember(storedDeflate(payload), crc32(payload), payload.length);
    }

    /** zlib around stored blocks. */
    public static byte[] zlibStored(byte[] payload) {
        return zlibWrap(storedDeflate(payload), adler32(payload));
    }

    // -- brotli (RFC 7932) -------------------------------------------------------------------------------

    private static void brotliWindowBits(BitWriter w, int wbits) {
        if (wbits == 16) {
            w.bits(0, 1);
        } else if (wbits == 17) {
            w.bits(1, 1);
            w.bits(0, 3);
            w.bits(0, 3);
        } else if (wbits >= 18 && wbits <= 24) {
            w.bits(1, 1);
            w.bits(wbits - 17, 3);
        } else if (wbits >= 10 && wbits <= 15) {
            w.bits(1, 1);
            w.bits(0, 3);
            w.bits(wbits - 8, 3);
        } else {
            throw new IllegalArgumentException("brotli window bits must be in 10..24");
        }
    }

    private static void brotliMlen(BitWriter w, long mlen) {
        long value = mlen - 1;
        w.bits(0, 1); // ISLAST
        if (value < 1L << 16) {
            w.bits(0, 2);
            w.bits(value, 16);
        } else if (value < 1L << 20) {
            w.bits(1, 2);
            w.bits(value, 20);
        } else {
            w.bits(2, 2);
            w.bits(value, 24);
        }
    }

    private static void brotliEnd(BitWriter w) {
        w.bits(1, 1);
        w.bits(1, 1);
    }

    private static void brotliZerosMetablock(BitWriter w, long mlen) {
        brotliMlen(w, mlen);
        w.bits(0, 1); // ISUNCOMPRESSED
        w.bits(0, 3); // NBLTYPES*
        w.bits(0, 2); // NPOSTFIX
        w.bits(0, 4); // NDIRECT
        w.bits(0, 2); // context mode
        w.bits(0, 2); // NTREESL, NTREESD
        long[][] codes = {{0, 8}, {423, 10}, {0, 6}};
        for (long[] c : codes) {
            w.bits(1, 2); // HSKIP
            w.bits(0, 2); // NSYM - 1
            w.bits(c[0], (int) c[1]);
        }
        w.bits(mlen - 4 - 2118, 24);
    }

    /** BrotliZeros: {@code size} zero bytes in a few bytes per 16 MiB meta-block. */
    public static byte[] brotliZeros(long size, int wbits) {
        BitWriter w = new BitWriter();
        brotliWindowBits(w, wbits);
        long left = size;
        while (left > 0) {
            long chunk = Math.min(left, BROTLI_METABLOCK_MAX);
            long remainder = left - chunk;
            if (remainder > 0 && remainder < BROTLI_MIN_COMMAND_BYTES) {
                chunk -= BROTLI_MIN_COMMAND_BYTES;
            }
            brotliZerosMetablock(w, chunk);
            left -= chunk;
        }
        brotliEnd(w);
        return w.finish();
    }

    /** BrotliStored: uncompressed meta-blocks around {@code payload}. */
    public static byte[] brotliStored(byte[] payload, int wbits) {
        BitWriter w = new BitWriter();
        brotliWindowBits(w, wbits);
        for (long pos = 0; pos < payload.length; pos += BROTLI_METABLOCK_MAX) {
            int n = (int) Math.min(BROTLI_METABLOCK_MAX, payload.length - pos);
            brotliMlen(w, n);
            w.bits(1, 1); // ISUNCOMPRESSED
            w.align();
            w.raw(payload, (int) pos, n);
        }
        brotliEnd(w);
        return w.finish();
    }

    /** The reserved WBITS pattern 1000001 that announces a large-window stream. */
    public static byte[] brotliLargeWindowPrefix() {
        return new byte[] {0x11, 0, 0, 0};
    }

    // -- zstd (RFC 8878) ---------------------------------------------------------------------------------

    /** Magic, descriptor and window descriptor of a frame without content size, dictionary or checksum. */
    public static byte[] zstdHeader(int windowLog) {
        if (windowLog < 10 || windowLog > 41) {
            throw new IllegalArgumentException("zstd window log must be in 10..41");
        }
        return new byte[] {0x28, (byte) 0xb5, 0x2f, (byte) 0xfd, 0, (byte) ((windowLog - 10) << 3)};
    }

    private static byte[] zstdBlockHeader(boolean last, int kind, long size) {
        long v = (size << 3) | ((long) kind << 1) | (last ? 1 : 0);
        return new byte[] {(byte) v, (byte) (v >>> 8), (byte) (v >>> 16)};
    }

    /** The block size limit of a frame with this window log. */
    public static long zstdBlockMax(int windowLog) {
        return windowLog >= 17 ? ZSTD_BLOCK_MAX : 1L << windowLog;
    }

    /** RLE blocks of up to {@code blockMax} bytes: 4 bytes per block. */
    public static byte[] zstdRleBlocks(long size, long blockMax, int value, boolean last) {
        long blocks = Math.max(1, (size + blockMax - 1) / blockMax);
        byte[] out = new byte[Math.toIntExact(blocks * 4)];
        long left = size;
        int o = 0;
        while (true) {
            long chunk = Math.min(left, blockMax);
            left -= chunk;
            byte[] h = zstdBlockHeader(last && left == 0, 1, chunk);
            System.arraycopy(h, 0, out, o, 3);
            out[o + 3] = (byte) value;
            o += 4;
            if (left == 0) {
                return out;
            }
        }
    }

    /** Raw blocks of up to {@code blockMax} bytes. */
    public static byte[] zstdRawBlocks(byte[] payload, long blockMax, boolean last) {
        BitWriter w = new BitWriter();
        int pos = 0;
        while (true) {
            int end = (int) Math.min(payload.length, pos + blockMax);
            byte[] h = zstdBlockHeader(last && end >= payload.length, 0, end - pos);
            w.raw(h, 0, 3);
            w.raw(payload, pos, end - pos);
            pos = end;
            if (pos >= payload.length) {
                return w.finish();
            }
        }
    }

    /** ZstdZeros: {@code size} zero bytes in RLE blocks. */
    public static byte[] zstdZeros(long size, int windowLog) {
        return concat(zstdHeader(windowLog), zstdRleBlocks(size, zstdBlockMax(windowLog), 0, true));
    }

    /** ZstdStored: {@code payload} in raw blocks. */
    public static byte[] zstdStored(byte[] payload, int windowLog) {
        return concat(zstdHeader(windowLog), zstdRawBlocks(payload, zstdBlockMax(windowLog), true));
    }

    /** A skippable frame with {@code n} zero bytes of payload. */
    public static byte[] zstdSkippable(int n) {
        return concat(new byte[] {0x50, 0x2a, 0x4d, 0x18}, le32(n), new byte[n]);
    }

    // -- generator specs ---------------------------------------------------------------------------------

    private static byte[] zerosStream(String codec, long matches) {
        long size = zerosDecodedSize(matches);
        byte[] deflate = zerosDeflate(matches);
        switch (codec) {
            case "gzip":
                return gzipMember(deflate, crc32OfZeros(size), size);
            case "zlib":
                return zlibWrap(deflate, adler32OfZeros(size));
            case "raw":
                return deflate;
            default:
                throw new IllegalArgumentException("zeros generator for codec " + codec);
        }
    }

    private static byte[] wrapOnce(String codec, byte[] payload, int window) {
        switch (codec) {
            case "br":
                return brotliStored(payload, window);
            case "zstd":
                return zstdStored(payload, window);
            case "gzip":
                return gzipStored(payload);
            case "zlib":
                return zlibStored(payload);
            case "raw":
                return storedDeflate(payload);
            default:
                throw new IllegalArgumentException("wrap generator for codec " + codec);
        }
    }

    /** Build the compressed stream a generator spec describes. */
    public static byte[] generate(Spec spec) {
        if (spec instanceof Spec.Zeros z) {
            switch (z.codec()) {
                case "br":
                    return brotliZeros(z.size(), z.window());
                case "zstd":
                    return zstdZeros(z.size(), z.window());
                default:
                    return zerosStream(z.codec(), z.matches());
            }
        } else if (spec instanceof Spec.Data d) {
            return wrapOnce(d.codec(), data(d.seed(), Math.toIntExact(d.size())), d.window());
        } else if (spec instanceof Spec.Wrap w) {
            byte[] stream = generate(w.inner());
            for (long i = 0; i < w.times(); i++) {
                stream = wrapOnce(w.codec(), stream, w.window());
            }
            return stream;
        }
        throw new IllegalArgumentException("unknown spec");
    }

    /** Lower-case hex SHA-256. */
    public static String sha256Hex(byte[] payload) {
        return hex(newSha256().digest(payload));
    }

    /** A new SHA-256 digest. */
    public static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Lower-case hex of bytes. */
    public static String hex(byte[] bytes) {
        char[] digits = "0123456789abcdef".toCharArray();
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            out[2 * i] = digits[(bytes[i] >> 4) & 15];
            out[2 * i + 1] = digits[bytes[i] & 15];
        }
        return new String(out);
    }
}
