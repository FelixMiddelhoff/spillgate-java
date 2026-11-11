package io.github.felixmiddelhoff.spillgate;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/** Helpers shared by the core tests: decoders, sources, running and draining streams. */
final class CoreSupport {
    private CoreSupport() {}

    static final long INT64 = Long.MAX_VALUE;
    static final int[] READ_SIZES = {1, 7, 4096, 65536, 1 << 22};

    static byte[] payload(int n) {
        byte[] out = new byte[n];
        for (int i = 0; i < n; i++) {
            out[i] = (byte) (i * 31 + i / 7);
        }
        return out;
    }

    static byte[] data(int n) {
        byte[] out = new byte[n];
        for (int i = 0; i < n; i++) {
            out[i] = (byte) (i % 251);
        }
        return out;
    }

    static byte[] filled(int n, int value) {
        byte[] out = new byte[n];
        Arrays.fill(out, (byte) value);
        return out;
    }

    static byte[] stored(byte[] payload, int chunk) {
        return CoreCodecs.stored(payload, chunk);
    }

    static byte[] b(int... values) {
        byte[] out = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = (byte) values[i];
        }
        return out;
    }

    static byte[] prefix(byte[] a, int n) {
        return Arrays.copyOf(a, n);
    }

    static InputStream in(byte[] data) {
        return new java.io.ByteArrayInputStream(data);
    }

    /**
     * A decoder with the test codecs registered: {@code pass}, {@code up} (+1), {@code dbl} (x2),
     * {@code stored}, {@code expand10}, {@code expand1000}, {@code expand2000}, {@code bytes},
     * {@code shrink10}, {@code shrink1000}, {@code br} and {@code zstd} (both pass-through).
     */
    static Decoder decoder(Limits limits) {
        try {
            return Decoder.builder(limits)
                    .codec("pass", new CoreCodecs.Pass())
                    .codec("up", new CoreCodecs.Transform(b -> b + 1))
                    .codec("dbl", new CoreCodecs.Transform(b -> b * 2))
                    .codec("stored", new CoreCodecs.Stored())
                    .codec("expand10", new CoreCodecs.Expand(10))
                    .codec("expand1000", new CoreCodecs.Expand(1000))
                    .codec("expand2000", new CoreCodecs.Expand(2000))
                    .codec("bytes", new CoreCodecs.ByteByByte())
                    .codec("shrink10", new CoreCodecs.Shrink(10))
                    .codec("shrink1000", new CoreCodecs.Shrink(1000))
                    .codec("br", new CoreCodecs.Pass())
                    .codec("zstd", new CoreCodecs.Pass())
                    .build();
        } catch (SpillgateException e) {
            throw new AssertionError(e);
        }
    }

    /** Loose limits that never get in the way: 1 GiB output, no ratio check. */
    static Limits loose() {
        return Limits.maxOutput(1L << 30).withoutRatioLimit();
    }

    /** No ratio check with the given output budget. */
    static Limits out(long maxOutput) {
        return Limits.maxOutput(maxOutput).withoutRatioLimit();
    }

    /** The outcome of reading to the end: the bytes delivered before it and the error kind if any. */
    static final class Outcome {
        final byte[] bytes;
        final Kind kind;
        final int layer;
        final long limit;

        Outcome(byte[] bytes, Kind kind, int layer, long limit) {
            this.bytes = bytes;
            this.kind = kind;
            this.layer = layer;
            this.limit = limit;
        }

        /** Same bytes and kind (layer and limit are not part of the comparison). */
        boolean same(Outcome other) {
            return kind == other.kind && Arrays.equals(bytes, other.bytes);
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Outcome && same((Outcome) o);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(bytes) * 31 + (kind == null ? 0 : kind.hashCode());
        }

        @Override
        public String toString() {
            return "Outcome[" + bytes.length + " bytes, " + kind + ", layer " + layer + ", limit " + limit + "]";
        }
    }

    /** Read to the end with a fixed read size; a SpillgateException ends it with its kind. */
    static Outcome drain(InputStream in, int readSize) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buf = new byte[Math.min(readSize, 1 << 24)];
        while (true) {
            try {
                int n = in.read(buf, 0, buf.length);
                if (n < 0) {
                    return new Outcome(bytes.toByteArray(), null, 0, 0);
                }
                if (n == 0) {
                    throw new AssertionError("read returned 0 for a non-empty request");
                }
                bytes.write(buf, 0, n);
            } catch (SpillgateException e) {
                return new Outcome(bytes.toByteArray(), e.kind(), e.layer(), e.limit());
            } catch (IOException e) {
                throw new AssertionError("not a spillgate error: " + e, e);
            }
        }
    }

    /** Decode {@code data} through {@code header} with {@code decoder} and the given read size. */
    static Outcome run(Decoder decoder, byte[] data, String[] header, int readSize) {
        return runStream(decoder, new java.io.ByteArrayInputStream(data), header, readSize);
    }

    static Outcome run(Decoder decoder, byte[] data, String header, int readSize) {
        return run(decoder, data, new String[] {header}, readSize);
    }

    static Outcome runStream(Decoder decoder, InputStream source, String[] header, int readSize) {
        SpillgateInputStream stream;
        try {
            stream = decoder.reader(source, header);
        } catch (SpillgateException e) {
            return new Outcome(new byte[0], e.kind(), e.layer(), e.limit());
        }
        Outcome o = drain(stream, readSize);
        stream.close();
        return o;
    }

    static String[] h(String... values) {
        return values;
    }

    /** Join with ", ". */
    static String chain(String name, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(name);
        }
        return sb.toString();
    }

    /** The error a read throws, or null. */
    static SpillgateException readError(InputStream in, byte[] buf) {
        try {
            int n = in.read(buf, 0, buf.length);
            return n == 0 && buf.length > 0 ? new SpillgateException(Kind.INTERNAL, "zero read") : null;
        } catch (SpillgateException e) {
            return e;
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    /** Deterministic xorshift generator. */
    static final class Rng {
        private long state;

        Rng(long seed) {
            this.state = seed;
        }

        long next() {
            state ^= state << 13;
            state ^= state >>> 7;
            state ^= state << 17;
            return state;
        }

        long below(long n) {
            return Long.remainderUnsigned(next(), Math.max(n, 1));
        }

        byte[] bytes(int n) {
            byte[] out = new byte[n];
            for (int i = 0; i < n; i++) {
                out[i] = (byte) next();
            }
            return out;
        }
    }

    // -- sources ----------------------------------------------------------------------------------------

    /** Hands out at most {@code step} bytes per call and counts what it gave. */
    static class Chunked extends InputStream {
        private final byte[] data;
        private int pos;
        private final int step;
        final AtomicLong bytes = new AtomicLong();
        final AtomicInteger calls = new AtomicInteger();
        final AtomicInteger largestRequest = new AtomicInteger();
        final AtomicLong requested = new AtomicLong();
        final AtomicInteger singleByteCalls = new AtomicInteger();

        Chunked(byte[] data, int step) {
            this.data = data;
            this.step = Math.max(step, 1);
        }

        @Override
        public int read() {
            singleByteCalls.incrementAndGet();
            return pos < data.length ? data[pos++] & 0xff : -1;
        }

        @Override
        public int read(byte[] buf, int off, int len) {
            calls.incrementAndGet();
            largestRequest.accumulateAndGet(len, Math::max);
            requested.addAndGet(len);
            if (len == 0) {
                return 0;
            }
            if (pos >= data.length) {
                return -1;
            }
            int n = Math.min(Math.min(len, step), data.length - pos);
            System.arraycopy(data, pos, buf, off, n);
            pos += n;
            bytes.addAndGet(n);
            return n;
        }

        int remaining() {
            return data.length - pos;
        }
    }

    /** Delivers {@code data}, then throws a fresh exception on every further call. */
    static final class FailAfter extends InputStream {
        private final byte[] data;
        private int pos;
        private final Supplier<IOException> make;
        final AtomicInteger calls = new AtomicInteger();

        FailAfter(byte[] data, Supplier<IOException> make) {
            this.data = data;
            this.make = make;
        }

        @Override
        public int read() throws IOException {
            throw new AssertionError("read() must not be used");
        }

        @Override
        public int read(byte[] buf, int off, int len) throws IOException {
            calls.incrementAndGet();
            if (pos >= data.length) {
                throw make.get();
            }
            int n = Math.min(len, data.length - pos);
            System.arraycopy(data, pos, buf, off, n);
            pos += n;
            return n;
        }
    }

    /** Claims {@code extra} more bytes than the buffer holds (or a fixed count if {@code fixed}). */
    static final class OverReportingSource extends InputStream {
        private final int extra;

        OverReportingSource(int extra) {
            this.extra = extra;
        }

        @Override
        public int read() {
            return 9;
        }

        @Override
        public int read(byte[] buf, int off, int len) {
            Arrays.fill(buf, off, off + len, (byte) 9);
            return len + extra;
        }
    }

    /** Always returns the same count without writing anything. */
    static final class FixedCountSource extends InputStream {
        private final int value;
        final AtomicInteger calls = new AtomicInteger();

        FixedCountSource(int value) {
            this.value = value;
        }

        @Override
        public int read() {
            return -1;
        }

        @Override
        public int read(byte[] buf, int off, int len) {
            calls.incrementAndGet();
            return value;
        }
    }

    /** Returns 0 bytes {@code zeros} times in a row before every real read. */
    static final class Stuttering extends InputStream {
        private final InputStream inner;
        private final int zeros;
        private int left;

        Stuttering(InputStream inner, int zeros) {
            this.inner = inner;
            this.zeros = zeros;
            this.left = zeros;
        }

        @Override
        public int read() throws IOException {
            return inner.read();
        }

        @Override
        public int read(byte[] buf, int off, int len) throws IOException {
            if (left > 0) {
                left--;
                return 0;
            }
            left = zeros;
            return inner.read(buf, off, len);
        }
    }

    /** An endless source of {@code value} bytes that counts what it gave. */
    static final class Endless extends InputStream {
        private final byte value;
        final AtomicLong total = new AtomicLong();
        final AtomicLong largestRequest = new AtomicLong();

        Endless(int value) {
            this.value = (byte) value;
        }

        @Override
        public int read() {
            total.incrementAndGet();
            return value & 0xff;
        }

        @Override
        public int read(byte[] buf, int off, int len) {
            Arrays.fill(buf, off, off + len, value);
            total.addAndGet(len);
            largestRequest.accumulateAndGet(len, Math::max);
            return len;
        }
    }

    /** An empty source that counts the calls made to it. */
    static final class Counting extends InputStream {
        final AtomicInteger calls = new AtomicInteger();
        private final byte[] data;
        private int pos;

        Counting(byte[] data) {
            this.data = data;
        }

        @Override
        public int read() {
            calls.incrementAndGet();
            return pos < data.length ? data[pos++] & 0xff : -1;
        }

        @Override
        public int read(byte[] buf, int off, int len) {
            calls.incrementAndGet();
            if (pos >= data.length) {
                return -1;
            }
            int n = Math.min(len, data.length - pos);
            System.arraycopy(data, pos, buf, off, n);
            pos += n;
            return n;
        }
    }
}
