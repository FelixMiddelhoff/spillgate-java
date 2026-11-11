package io.github.felixmiddelhoff.spillgate.zstd;

import io.github.felixmiddelhoff.spillgate.SpillgateException;

import java.util.Arrays;

/**
 * The state of one frame body: window, repeat offsets and the tables later blocks may reuse (RFC 8878
 * sections 3.1.1.2 to 3.1.1.4). One block at a time is decoded into the reader's {@link Scratch}; a
 * block never regenerates more than 128 KiB. The window is only written when a block is complete, so a
 * match reads either the block under construction or the history in the ring.
 */
final class Frame {
    private static final int[] LL_BASE = {
        0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 18, 20, 22, 24, 28, 32, 40, 48, 64, 128, 256, 512,
        1024, 2048, 4096, 8192, 16384, 32768, 65536
    };
    private static final int[] LL_BITS = {
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 3, 3, 4, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15,
        16
    };
    private static final int[] ML_BASE = {
        3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31,
        32, 33, 34, 35, 37, 39, 41, 43, 47, 51, 59, 67, 83, 99, 131, 259, 515, 1027, 2051, 4099, 8195, 16387, 32771,
        65539
    };
    private static final int[] ML_BITS = {
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2,
        2, 3, 3, 4, 4, 5, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16
    };
    private static final int[] LL_DEFAULT = {
        4, 3, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 1, 1, 1, 2, 2, 2, 2, 2, 2, 2, 2, 2, 3, 2, 1, 1, 1, 1, 1, -1, -1, -1, -1
    };
    private static final int[] ML_DEFAULT = {
        1, 4, 3, 2, 2, 2, 2, 2, 2, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
        1, 1, 1, 1, 1, 1, 1, 1, 1, -1, -1, -1, -1, -1, -1, -1
    };
    private static final int[] OF_DEFAULT = {
        1, 1, 1, 1, 1, 1, 2, 2, 2, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, -1, -1, -1, -1, -1
    };

    /** The predefined tables, built once (immutable, shared by all readers). */
    private static final class Defaults {
        static final Fse.Table LL;
        static final Fse.Table ML;
        static final Fse.Table OF;

        static {
            try {
                LL = Fse.build(LL_DEFAULT, LL_DEFAULT.length, 6);
                ML = Fse.build(ML_DEFAULT, ML_DEFAULT.length, 6);
                OF = Fse.build(OF_DEFAULT, OF_DEFAULT.length, 5);
            } catch (SpillgateException e) {
                throw new ExceptionInInitializerError(e);
            }
        }
    }

    private final int windowSize;
    private final Window window;
    private long rep0 = 1;
    private long rep1 = 4;
    private long rep2 = 8;
    private Huffman.Table huffman;
    private Fse.Table ll;
    private Fse.Table of;
    private Fse.Table ml;

    /** {@code windowSize} is the window the frame declared (already checked against the cap). */
    Frame(int windowSize) {
        this.windowSize = windowSize;
        this.window = new Window(windowSize);
    }

    long total() {
        return window.total();
    }

    int windowCapacity() {
        return window.capacity();
    }

    Window window() {
        return window;
    }

    /** Largest block this frame may contain: min(window, 128 KiB) (RFC 8878 3.1.1.2.3). */
    int blockMax() {
        return Math.min(windowSize, Scratch.BLOCK_MAX);
    }

    /** A finished block joins the window. */
    void push(byte[] block, int len) throws SpillgateException {
        window.push(block, 0, len);
    }

    /** Decode the compressed block in {@code s.content[0, contentLen)} into {@code s.out}. */
    void compressed(Scratch s) throws SpillgateException {
        int used = readLiterals(s);
        readSequences(s, used);
    }

    // -- literals ---------------------------------------------------------------------

    private static int byteAt(byte[] c, int n, int i) throws SpillgateException {
        if (i >= n) {
            throw Err.corrupt("a literals section ends too early");
        }
        return c[i] & 0xff;
    }

    private int readLiterals(Scratch s) throws SpillgateException {
        final byte[] c = s.content;
        final int n = s.contentLen;
        if (n == 0) {
            throw Err.corrupt("an empty compressed block");
        }
        int b0 = c[0] & 0xff;
        int kind = b0 & 3;
        int sizeFormat = (b0 >> 2) & 3;
        int blockMax = blockMax();
        s.litsLen = 0;

        if (kind == 0 || kind == 1) {
            int regen;
            int header;
            switch (sizeFormat) {
                case 0, 2 -> {
                    regen = b0 >> 3;
                    header = 1;
                }
                case 1 -> {
                    regen = (b0 >> 4) + (byteAt(c, n, 1) << 4);
                    header = 2;
                }
                default -> {
                    regen = (b0 >> 4) + (byteAt(c, n, 1) << 4) + (byteAt(c, n, 2) << 12);
                    header = 3;
                }
            }
            if (regen > blockMax) {
                throw Err.corrupt("a literals section is larger than a block");
            }
            if (kind == 0) {
                int end = header + regen;
                if (end > n) {
                    throw Err.corrupt("a literals section ends too early");
                }
                s.ensureLits(regen);
                System.arraycopy(c, header, s.lits, 0, regen);
                s.litsLen = regen;
                return end;
            }
            int fill = byteAt(c, n, header);
            s.ensureLits(regen);
            Arrays.fill(s.lits, 0, regen, (byte) fill);
            s.litsLen = regen;
            return header + 1;
        }

        // compressed (2) or treeless (3): sizes of 10, 10, 14 or 18 bits
        long regenL;
        long compressedL;
        int header;
        int streams;
        switch (sizeFormat) {
            case 0, 1 -> {
                long v = b0 + ((long) byteAt(c, n, 1) << 8) + ((long) byteAt(c, n, 2) << 16);
                regenL = (v >> 4) & 0x3ff;
                compressedL = (v >> 14) & 0x3ff;
                header = 3;
                streams = sizeFormat == 0 ? 1 : 4;
            }
            case 2 -> {
                long v = b0 + ((long) byteAt(c, n, 1) << 8) + ((long) byteAt(c, n, 2) << 16)
                        + ((long) byteAt(c, n, 3) << 24);
                regenL = (v >> 4) & 0x3fff;
                compressedL = v >> 18;
                header = 4;
                streams = 4;
            }
            default -> {
                long v = b0 + ((long) byteAt(c, n, 1) << 8) + ((long) byteAt(c, n, 2) << 16)
                        + ((long) byteAt(c, n, 3) << 24) + ((long) byteAt(c, n, 4) << 32);
                regenL = (v >> 4) & 0x3ffff;
                compressedL = (v >> 22) & 0x3ffff;
                header = 5;
                streams = 4;
            }
        }
        if (regenL > blockMax) {
            throw Err.corrupt("a literals section is larger than a block");
        }
        int regen = (int) regenL;
        long endL = header + compressedL;
        if (endL > n) {
            throw Err.corrupt("a literals section ends too early");
        }
        int end = (int) endL;
        int bodyFrom = header;

        if (kind == 2) {
            Huffman.Parsed parsed = Huffman.read(c, bodyFrom, end);
            huffman = parsed.table;
            bodyFrom += parsed.used;
            if (bodyFrom > end) {
                throw Err.corrupt("a Huffman tree description ends too early");
            }
        }
        Huffman.Table table = huffman;
        if (table == null) {
            throw Err.corrupt("treeless literals without an earlier Huffman table");
        }
        s.ensureLits(regen);
        s.litsLen = regen;
        if (streams == 1) {
            Huffman.decodeStream(table, c, bodyFrom, end, s.lits, 0, regen);
        } else {
            if (end - bodyFrom < 6) {
                throw Err.corrupt("a literals jump table ends too early");
            }
            int s1 = (c[bodyFrom] & 0xff) | ((c[bodyFrom + 1] & 0xff) << 8);
            int s2 = (c[bodyFrom + 2] & 0xff) | ((c[bodyFrom + 3] & 0xff) << 8);
            int s3 = (c[bodyFrom + 4] & 0xff) | ((c[bodyFrom + 5] & 0xff) << 8);
            long restL = (long) (end - bodyFrom - 6) - s1 - s2 - s3;
            if (restL < 0) {
                throw Err.corrupt("literal stream sizes exceed the section");
            }
            int part = (regen + 3) / 4;
            int last = regen - 3 * part;
            if (last < 0) {
                throw Err.corrupt("a literals section is too small for four streams");
            }
            int at = bodyFrom + 6;
            int outAt = 0;
            int[] sizes = {s1, s2, s3, (int) restL};
            int[] counts = {part, part, part, last};
            for (int i = 0; i < 4; i++) {
                Huffman.decodeStream(table, c, at, at + sizes[i], s.lits, outAt, counts[i]);
                at += sizes[i];
                outAt += counts[i];
            }
        }
        return end;
    }

    // -- sequences --------------------------------------------------------------------

    private Fse.Table readMode(
            int mode, int maxLog, int maxSymbol, Fse.Table fallback, Fse.Table previous, byte[] sec, int secEnd,
            int[] at) throws SpillgateException {
        switch (mode) {
            case 0:
                return fallback;
            case 1: {
                if (at[0] >= secEnd) {
                    throw Err.corrupt("an RLE table ends too early");
                }
                int symbol = sec[at[0]] & 0xff;
                at[0]++;
                if (symbol > maxSymbol) {
                    throw Err.corrupt("an RLE table names an impossible symbol");
                }
                return Fse.rle(symbol);
            }
            case 2: {
                Fse.Parsed parsed = Fse.read(sec, at[0], secEnd, maxLog, maxSymbol);
                at[0] += parsed.used;
                return parsed.table;
            }
            default:
                if (previous == null) {
                    throw Err.corrupt("a repeated table without an earlier one");
                }
                return previous;
        }
    }

    private void readSequences(Scratch s, int from) throws SpillgateException {
        final byte[] sec = s.content;
        final int end = s.contentLen;
        s.outLen = 0;
        if (from >= end) {
            throw Err.corrupt("a sequences section is missing");
        }
        int b0 = sec[from] & 0xff;
        int at = from + 1;
        int count;
        if (b0 == 0) {
            // no sequences: the literals are the whole block, and nothing may follow the count
            if (end - from != 1) {
                throw Err.corrupt("bytes follow a sequences section without sequences");
            }
            s.ensureOut(s.litsLen);
            System.arraycopy(s.lits, 0, s.out, 0, s.litsLen);
            s.outLen = s.litsLen;
            return;
        } else if (b0 < 128) {
            count = b0;
        } else if (b0 < 255) {
            if (at >= end) {
                throw Err.corrupt("a sequences header ends too early");
            }
            count = ((b0 - 128) << 8) + (sec[at] & 0xff);
            at += 1;
        } else {
            if (at + 1 >= end) {
                throw Err.corrupt("a sequences header ends too early");
            }
            count = (sec[at] & 0xff) + ((sec[at + 1] & 0xff) << 8) + 0x7f00;
            at += 2;
        }
        if (at >= end) {
            throw Err.corrupt("a sequences header ends too early");
        }
        int modes = sec[at] & 0xff;
        at++;
        if ((modes & 3) != 0) {
            throw Err.corrupt("reserved bits are set in the symbol compression modes");
        }
        int[] cursor = {at};
        Fse.Table nl = readMode(modes >> 6, 9, 35, Defaults.LL, ll, sec, end, cursor);
        Fse.Table no = readMode((modes >> 4) & 3, 8, 31, Defaults.OF, of, sec, end, cursor);
        Fse.Table nm = readMode((modes >> 2) & 3, 9, 52, Defaults.ML, ml, sec, end, cursor);
        ll = nl;
        of = no;
        ml = nm;
        execute(s, cursor[0], end, count, nl, no, nm);
    }

    private void execute(Scratch s, int from, int end, int count, Fse.Table llT, Fse.Table ofT, Fse.Table mlT)
            throws SpillgateException {
        Bits.Backward bits = new Bits.Backward(s.content, from, end);
        long llState = bits.read(llT.accuracyLog);
        long ofState = bits.read(ofT.accuracyLog);
        long mlState = bits.read(mlT.accuracyLog);

        final long limit = blockMax();
        final long windowLimit = windowSize;
        final long history = window.total();
        final byte[] lits = s.lits;
        final int litsLen = s.litsLen;
        int litPos = 0;

        for (int i = 0; i < count; i++) {
            if (llState >= llT.size || ofState >= ofT.size || mlState >= mlT.size) {
                throw Err.corrupt("a table state is out of range");
            }
            int llI = (int) llState;
            int ofI = (int) ofState;
            int mlI = (int) mlState;
            int ofCode = ofT.symbol[ofI] & 0xff;
            int mlCode = mlT.symbol[mlI] & 0xff;
            int llCode = llT.symbol[llI] & 0xff;
            if (ofCode > 31 || mlCode > 52 || llCode > 35) {
                throw Err.corrupt("a sequence names an impossible code");
            }
            long offsetValue = (1L << ofCode) + bits.read(ofCode);
            long matchLength = ML_BASE[mlCode] + bits.read(ML_BITS[mlCode]);
            long literalLength = LL_BASE[llCode] + bits.read(LL_BITS[llCode]);

            // repeat offsets (RFC 8878 3.1.1.5)
            long offset;
            if (offsetValue > 3) {
                offset = offsetValue - 3;
                rep2 = rep1;
                rep1 = rep0;
                rep0 = offset;
            } else {
                long index = offsetValue;
                if (literalLength == 0) {
                    index++;
                }
                if (index == 1) {
                    offset = rep0;
                } else if (index == 2) {
                    offset = rep1;
                    rep1 = rep0;
                    rep0 = offset;
                } else if (index == 3) {
                    offset = rep2;
                    rep2 = rep1;
                    rep1 = rep0;
                    rep0 = offset;
                } else {
                    offset = rep0 - 1;
                    if (offset == 0) {
                        throw Err.corrupt("a repeat offset of zero");
                    }
                    rep2 = rep1;
                    rep1 = rep0;
                    rep0 = offset;
                }
            }

            if ((long) s.outLen + literalLength + matchLength > limit) {
                throw Err.corrupt("a block regenerates more than its maximum");
            }
            if (litPos + literalLength > litsLen) {
                throw Err.corrupt("a sequence uses more literals than the block has");
            }
            int need = s.outLen + (int) literalLength + (int) matchLength;
            s.ensureOut(need);
            int ln = (int) literalLength;
            System.arraycopy(lits, litPos, s.out, s.outLen, ln);
            s.outLen += ln;
            litPos += ln;

            if (offset <= 0 || offset > history + s.outLen || offset > windowLimit) {
                throw Err.corrupt("a match reaches before the start of the window");
            }
            copyMatch(s, (int) offset, (int) matchLength);

            if (i + 1 < count) {
                llState = llT.baseline[llI] + bits.read(llT.nbBits[llI] & 0xff);
                mlState = mlT.baseline[mlI] + bits.read(mlT.nbBits[mlI] & 0xff);
                ofState = ofT.baseline[ofI] + bits.read(ofT.nbBits[ofI] & 0xff);
            }
        }
        if (bits.remaining != 0) {
            throw Err.corrupt("the sequence bit stream was not consumed exactly");
        }

        // the literals that no sequence used come last
        int tail = litsLen - litPos;
        if ((long) s.outLen + tail > limit) {
            throw Err.corrupt("a block regenerates more than its maximum");
        }
        s.ensureOut(s.outLen + tail);
        System.arraycopy(lits, litPos, s.out, s.outLen, tail);
        s.outLen += tail;
    }

    /**
     * Append a match of {@code len} bytes at distance {@code offset} to the block under construction.
     * The caller checked {@code offset <= history + outLen}, {@code offset <= window} and that the
     * output buffer has room for {@code len} more bytes.
     */
    private void copyMatch(Scratch s, int offset, int len) throws SpillgateException {
        byte[] out = s.out;
        int remaining = len;
        while (remaining > 0) {
            if (offset <= s.outLen) {
                int start = s.outLen - offset;
                if (offset == 1) {
                    Arrays.fill(out, s.outLen, s.outLen + remaining, out[start]);
                    s.outLen += remaining;
                    return;
                }
                // the period is `offset`; each full copy doubles what is available
                while (remaining > 0) {
                    int n = Math.min(remaining, s.outLen - start);
                    System.arraycopy(out, start, out, s.outLen, n);
                    s.outLen += n;
                    remaining -= n;
                }
            } else {
                // d bytes of the match lie in the history before this block
                int d = offset - s.outLen;
                int n = Math.min(remaining, d);
                window.copyHistory(d, n, out, s.outLen);
                s.outLen += n;
                remaining -= n;
            }
        }
    }
}
