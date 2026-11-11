package io.github.felixmiddelhoff.spillgate.zstd;

import com.github.luben.zstd.Zstd;
import com.github.luben.zstd.ZstdCompressCtx;
import com.github.luben.zstd.ZstdInputStream;

import io.github.felixmiddelhoff.spillgate.CodecInput;
import io.github.felixmiddelhoff.spillgate.CodecReader;
import io.github.felixmiddelhoff.spillgate.Kind;
import io.github.felixmiddelhoff.spillgate.SpillgateException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Helpers of the zstd tests: data, frames built by hand, zstd-jni as compressor and oracle, drivers. */
final class Z {
    private Z() {}

    static final int MIB = 1 << 20;
    static final int BLOCK_MAX = 128 * 1024;
    static final long DEFAULT_MAX_WINDOW = ZstdOptions.DEFAULT_MAX_WINDOW;

    // -- data -------------------------------------------------------------------------

    /** Deterministic pseudo random bytes (xorshift), the same for every run. */
    static byte[] data(long seed, int size) {
        long x = (seed * 0x9e3779b97f4a7c15L) | 1;
        byte[] out = new byte[size];
        for (int i = 0; i < size; i++) {
            x ^= x << 13;
            x ^= x >>> 7;
            x ^= x << 17;
            out[i] = (byte) (x >>> 24);
        }
        return out;
    }

    static byte[] text() {
        return "the quick brown fox jumps over the lazy dog. ".repeat(3000).getBytes(StandardCharsets.US_ASCII);
    }

    static byte[] mixed() {
        byte[] text = text();
        return concat(Arrays.copyOf(text, 20_000), data(5, 70_000), new byte[100_000], text);
    }

    static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            out.write(p, 0, p.length);
        }
        return out.toByteArray();
    }

    static byte[] bytes(int... values) {
        byte[] b = new byte[values.length];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) values[i];
        }
        return b;
    }

    static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    static byte[] filled(int size, int value) {
        byte[] b = new byte[size];
        Arrays.fill(b, (byte) value);
        return b;
    }

    static byte[] repeat(byte[] b, int times) {
        byte[] out = new byte[b.length * times];
        for (int i = 0; i < times; i++) {
            System.arraycopy(b, 0, out, i * b.length, b.length);
        }
        return out;
    }

    // -- the reference (zstd-jni) -----------------------------------------------------

    /** Parameters of the reference compressor; null means the library default. */
    static final class Params {
        Boolean checksum;
        Boolean contentSize;
        Integer windowLog;
        Integer strategy;
        boolean ldm;

        Params checksum(boolean on) {
            checksum = on;
            return this;
        }

        Params contentSize(boolean on) {
            contentSize = on;
            return this;
        }

        Params windowLog(int w) {
            windowLog = w;
            return this;
        }

        Params strategy(int s) {
            strategy = s;
            return this;
        }

        Params ldm() {
            ldm = true;
            return this;
        }
    }

    static Params params() {
        return new Params();
    }

    static byte[] compress(byte[] payload, int level, Params p) {
        try (ZstdCompressCtx ctx = new ZstdCompressCtx()) {
            ctx.setLevel(level);
            if (p.checksum != null) {
                ctx.setChecksum(p.checksum);
            }
            if (p.contentSize != null) {
                ctx.setContentSize(p.contentSize);
            }
            if (p.windowLog != null) {
                ctx.setWindowLog(p.windowLog);
            }
            if (p.strategy != null) {
                ctx.setStrategy(p.strategy);
            }
            if (p.ldm) {
                ctx.setEnableLongDistanceMatching(Zstd.ParamSwitch.ENABLE);
            }
            return ctx.compress(payload);
        }
    }

    static byte[] compress(byte[] payload) {
        return compress(payload, 3, params());
    }

    static byte[] checksummed(byte[] payload, int level) {
        return compress(payload, level, params().checksum(true));
    }

    /** What the reference decoder makes of a stream: its output, or null if it refuses. */
    static byte[] oracle(byte[] stream) {
        try (ZstdInputStream in = new ZstdInputStream(new ByteArrayInputStream(stream))) {
            return in.readAllBytes();
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    // -- driving the codec ------------------------------------------------------------

    /** The result of decoding: the error kind (null if clean) and the bytes delivered before it. */
    static final class Outcome {
        final Kind kind;
        final byte[] out;
        final boolean overflow;

        Outcome(Kind kind, byte[] out, boolean overflow) {
            this.kind = kind;
            this.out = out;
            this.overflow = overflow;
        }

        boolean ok() {
            return kind == null && !overflow;
        }

        @Override
        public String toString() {
            return "Outcome[" + kind + ", " + out.length + " bytes]";
        }
    }

    /** A CodecInput over a byte array that hands out at most {@code step} bytes per fill and counts. */
    static final class ArrayInput implements CodecInput {
        final byte[] data;
        final int step;
        int pos;
        int maxConsume;
        int fills;

        ArrayInput(byte[] data, int step) {
            this.data = data;
            this.step = step;
        }

        ArrayInput(byte[] data) {
            this(data, Integer.MAX_VALUE);
        }

        int window() {
            return (int) Math.min((long) step, (long) data.length - pos);
        }

        @Override
        public int fill() throws IOException {
            fills++;
            return window();
        }

        @Override
        public byte[] buffer() {
            return data;
        }

        @Override
        public int position() {
            return pos;
        }

        @Override
        public void consume(int n) {
            if (n < 0 || n > window()) {
                throw new AssertionError("consume of " + n + " with a window of " + window());
            }
            pos += n;
            maxConsume = Math.max(maxConsume, n);
        }

        int consumed() {
            return pos;
        }
    }

    /** An input that fails after {@code ok} bytes. */
    static final class FailingInput implements CodecInput {
        final byte[] data;
        final int ok;
        int pos;

        FailingInput(byte[] data, int ok) {
            this.data = data;
            this.ok = ok;
        }

        @Override
        public int fill() throws IOException {
            if (pos >= ok) {
                throw new IOException("source failed");
            }
            return Math.min(ok, data.length) - pos;
        }

        @Override
        public byte[] buffer() {
            return data;
        }

        @Override
        public int position() {
            return pos;
        }

        @Override
        public void consume(int n) {
            pos += n;
        }
    }

    static ZstdCodec codec(long cap) {
        try {
            return ZstdCodec.create(ZstdOptions.defaults().withMaxWindowBytes(cap));
        } catch (SpillgateException e) {
            throw new AssertionError(e);
        }
    }

    static ZstdReader reader(CodecInput in, long cap) {
        return new ZstdReader(in, cap);
    }

    /** Drain {@code r} with reads of {@code bufSize}; at most {@code maxOut} bytes are kept. */
    static Outcome drain(CodecReader r, int bufSize, long maxOut) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[bufSize];
        // a guard cell around the buffer window checks that the codec stays inside [off, off + len)
        int off = 3;
        byte[] big = new byte[bufSize + 6];
        while (true) {
            Arrays.fill(big, (byte) 0x5a);
            int n;
            try {
                n = r.read(big, off, bufSize);
            } catch (SpillgateException e) {
                return new Outcome(e.kind(), out.toByteArray(), false);
            } catch (IOException e) {
                throw new AssertionError("unexpected " + e, e);
            }
            if (n < 0) {
                return new Outcome(null, out.toByteArray(), false);
            }
            if (n == 0 || n > bufSize) {
                throw new AssertionError("a codec returns 1..len or -1, got " + n);
            }
            for (int i = 0; i < off; i++) {
                if (big[i] != 0x5a) {
                    throw new AssertionError("wrote before off");
                }
            }
            for (int i = off + bufSize; i < big.length; i++) {
                if (big[i] != 0x5a) {
                    throw new AssertionError("wrote after off + len");
                }
            }
            out.write(big, off, n);
            if (out.size() > maxOut) {
                return new Outcome(null, out.toByteArray(), true);
            }
            if (buf == null) {
                throw new AssertionError();
            }
        }
    }

    static Outcome drain(CodecReader r, int bufSize) {
        return drain(r, bufSize, 1L << 30);
    }

    static Outcome runCap(byte[] stream, long cap) {
        ZstdReader r = reader(new ArrayInput(stream), cap);
        try {
            return drain(r, 4096);
        } finally {
            r.close();
        }
    }

    static Outcome run(byte[] stream) {
        return runCap(stream, 1L << 27);
    }

    /** Result of {@link #count}. */
    static final class Count {
        final Kind kind;
        final long total;
        final ZstdReader reader;
        long peakHeap;

        Count(Kind kind, long total, ZstdReader reader, long peakHeap) {
            this.kind = kind;
            this.total = total;
            this.reader = reader;
            this.peakHeap = peakHeap;
        }
    }

    /** Decode and count the bytes without keeping them; the peak heap of the reader is tracked. */
    static Count count(CodecInput in, long cap, long limit) {
        ZstdReader r = reader(in, cap);
        byte[] buf = new byte[64 * 1024];
        long total = 0;
        long peak = 0;
        while (true) {
            int n;
            try {
                n = r.read(buf, 0, buf.length);
            } catch (SpillgateException e) {
                return new Count(e.kind(), total, r, Math.max(peak, r.heapBytes()));
            } catch (IOException e) {
                throw new AssertionError(e);
            }
            peak = Math.max(peak, r.heapBytes());
            if (n < 0) {
                return new Count(null, total, r, peak);
            }
            total += n;
            if (total >= limit) {
                return new Count(null, total, r, peak);
            }
        }
    }

    // -- frames built by hand ---------------------------------------------------------

    static final class Header {
        Integer windowLog;
        int mantissa;
        boolean singleSegment;
        Long contentSize;
        Long dictionaryId;
        boolean checksum;
        boolean reservedBit;

        Header windowLog(int w) {
            windowLog = w;
            return this;
        }

        Header mantissa(int m) {
            mantissa = m;
            return this;
        }

        Header single() {
            singleSegment = true;
            return this;
        }

        Header size(long s) {
            contentSize = s;
            return this;
        }

        Header dict(long id) {
            dictionaryId = id;
            return this;
        }

        Header checksum() {
            checksum = true;
            return this;
        }

        Header reserved() {
            reservedBit = true;
            return this;
        }
    }

    static Header h() {
        return new Header();
    }

    static byte[] le(long v, int n) {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) {
            b[i] = (byte) (v >>> (8 * i));
        }
        return b;
    }

    static byte[] header(Header h) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(bytes(0x28, 0xb5, 0x2f, 0xfd));
        int fcsFlag;
        byte[] fcs;
        if (h.contentSize == null) {
            fcsFlag = 0;
            fcs = new byte[0];
        } else {
            long size = h.contentSize;
            if (h.singleSegment && size >= 0 && size <= 255) {
                fcsFlag = 0;
                fcs = le(size, 1);
            } else if (size >= 256 && size <= 65791) {
                fcsFlag = 1;
                fcs = le(size - 256, 2);
            } else if (size >= 0 && size <= 0xffffffffL) {
                fcsFlag = 2;
                fcs = le(size, 4);
            } else {
                fcsFlag = 3;
                fcs = le(size, 8);
            }
        }
        int dictFlag = h.dictionaryId == null ? 0 : 3;
        int descriptor = (fcsFlag << 6) | dictFlag;
        if (h.singleSegment) {
            descriptor |= 1 << 5;
        }
        if (h.checksum) {
            descriptor |= 1 << 2;
        }
        if (h.reservedBit) {
            descriptor |= 1 << 3;
        }
        out.write(descriptor);
        if (!h.singleSegment) {
            int wl = h.windowLog == null ? 10 : h.windowLog;
            out.write(((wl - 10) << 3) | (h.mantissa & 7));
        }
        if (h.dictionaryId != null) {
            out.writeBytes(le(h.dictionaryId, 4));
        }
        out.writeBytes(fcs);
        return out.toByteArray();
    }

    static byte[] windowHeader(int windowLog) {
        return header(h().windowLog(windowLog));
    }

    static byte[] blockHeader(boolean last, int kind, int size) {
        long v = ((long) size << 3) | ((long) kind << 1) | (last ? 1 : 0);
        return le(v, 3);
    }

    /** Raw blocks of at most {@code block} bytes carrying {@code payload} (one empty block if empty). */
    static byte[] rawBlocks(byte[] payload, int block, boolean last) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (payload.length == 0) {
            out.writeBytes(blockHeader(last, 0, 0));
            return out.toByteArray();
        }
        int n = (payload.length + block - 1) / block;
        for (int i = 0; i < n; i++) {
            int from = i * block;
            int to = Math.min(payload.length, from + block);
            out.writeBytes(blockHeader(last && i + 1 == n, 0, to - from));
            out.write(payload, from, to - from);
        }
        return out.toByteArray();
    }

    /** RLE blocks regenerating {@code total} bytes of {@code value} in blocks of at most {@code block}. */
    static byte[] rleBlocks(long total, int block, int value, boolean last) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        long left = total;
        while (true) {
            int size = (int) Math.min(left, block);
            left -= size;
            boolean isLast = left == 0;
            out.writeBytes(blockHeader(last && isLast, 1, size));
            out.write(value);
            if (isLast) {
                return out.toByteArray();
            }
        }
    }

    /** A frame of {@code size} zero bytes with window 2^windowLog, as RLE blocks (a bomb in a few bytes). */
    static byte[] zeros(long size, int windowLog) {
        int block = Math.min(1 << windowLog, BLOCK_MAX);
        return concat(windowHeader(windowLog), rleBlocks(size, block, 0, true));
    }

    static byte[] skippable(int n) {
        return concat(bytes(0x50, 0x2a, 0x4d, 0x18), le(n, 4), filled(n, 0xab));
    }
}
