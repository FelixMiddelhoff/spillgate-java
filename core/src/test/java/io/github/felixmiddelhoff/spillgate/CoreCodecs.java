package io.github.felixmiddelhoff.spillgate;

import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntUnaryOperator;
import java.util.function.Supplier;

/** Small test-only codecs (the test equivalents of the Rust {@code tests/common/mod.rs}). */
final class CoreCodecs {
    private CoreCodecs() {}

    /** Copies its input. */
    static final class Pass implements Codec {
        @Override
        public CodecReader newReader(CodecInput in) {
            return (buf, off, len) -> {
                int available = in.fill();
                if (available == 0) {
                    return -1;
                }
                int n = Math.min(available, len);
                System.arraycopy(in.buffer(), in.position(), buf, off, n);
                in.consume(n);
                return n;
            };
        }
    }

    /** Maps every byte through {@code f}. */
    static final class Transform implements Codec {
        private final IntUnaryOperator f;

        Transform(IntUnaryOperator f) {
            this.f = f;
        }

        @Override
        public CodecReader newReader(CodecInput in) {
            return (buf, off, len) -> {
                int available = in.fill();
                if (available == 0) {
                    return -1;
                }
                int n = Math.min(available, len);
                byte[] src = in.buffer();
                int p = in.position();
                for (int i = 0; i < n; i++) {
                    buf[off + i] = (byte) f.applyAsInt(src[p + i] & 0xff);
                }
                in.consume(n);
                return n;
            };
        }
    }

    /** Emits {@code factor} copies of every input byte it consumes, lazily, one input byte at a time. */
    static final class Expand implements Codec {
        private final long factor;

        Expand(long factor) {
            this.factor = factor;
        }

        @Override
        public CodecReader newReader(CodecInput in) {
            return new CodecReader() {
                private byte value;
                private long left;

                @Override
                public int read(byte[] buf, int off, int len) throws IOException {
                    if (left == 0) {
                        if (in.fill() == 0) {
                            return -1;
                        }
                        value = in.buffer()[in.position()];
                        in.consume(1);
                        left = factor;
                    }
                    int n = (int) Math.min(len, left);
                    Arrays.fill(buf, off, off + n, value);
                    left -= n;
                    return n;
                }
            };
        }
    }

    /** A bomb: ignores its input and emits {@code total} zero bytes, then ends cleanly. Consumes nothing. */
    static final class Zeros implements Codec {
        private final long total;

        Zeros(long total) {
            this.total = total;
        }

        @Override
        public CodecReader newReader(CodecInput in) {
            return new CodecReader() {
                private long left = total;

                @Override
                public int read(byte[] buf, int off, int len) {
                    if (left == 0) {
                        return -1;
                    }
                    int n = (int) Math.min(len, left);
                    Arrays.fill(buf, off, off + n, (byte) 0);
                    left -= n;
                    return n;
                }
            };
        }
    }

    /** Throws a RuntimeException after delivering {@code after} bytes. */
    static final class Panicker implements Codec {
        private final int after;

        Panicker(int after) {
            this.after = after;
        }

        @Override
        public CodecReader newReader(CodecInput in) {
            return new CodecReader() {
                private int left = after;

                @Override
                public int read(byte[] buf, int off, int len) {
                    if (left == 0) {
                        throw new IllegalStateException("test codec panics on purpose");
                    }
                    int n = Math.min(len, left);
                    Arrays.fill(buf, off, off + n, (byte) 7);
                    left -= n;
                    return n;
                }
            };
        }
    }

    /** Throws whatever the supplier makes (an Error, a hostile RuntimeException) on the first read. */
    static final class Throws implements Codec {
        private final Supplier<Throwable> make;

        Throws(Supplier<Throwable> make) {
            this.make = make;
        }

        @Override
        public CodecReader newReader(CodecInput in) {
            return (buf, off, len) -> {
                sneaky(make.get());
                return 0;
            };
        }
    }

    @SuppressWarnings("unchecked")
    static <T extends Throwable> void sneaky(Throwable t) throws T {
        throw (T) t;
    }

    /** Throws when it is constructed. */
    static final class ThrowsOnNew implements Codec {
        private final Supplier<Throwable> make;

        ThrowsOnNew(Supplier<Throwable> make) {
            this.make = make;
        }

        @Override
        public CodecReader newReader(CodecInput in) {
            sneaky(make.get());
            return null;
        }
    }

    /** Returns null from newReader. */
    static final class NullReader implements Codec {
        @Override
        public CodecReader newReader(CodecInput in) {
            return null;
        }
    }

    /** Claims to have written {@code len + extra} bytes (or {@code fixed} if not null). */
    static final class OverReport implements Codec {
        private final int extra;

        OverReport(int extra) {
            this.extra = extra;
        }

        @Override
        public CodecReader newReader(CodecInput in) {
            return (buf, off, len) -> {
                Arrays.fill(buf, off, off + len, (byte) 1);
                return len + extra;
            };
        }
    }

    /** Always returns the same count, writing nothing. */
    static final class Returns implements Codec {
        private final int value;

        Returns(int value) {
            this.value = value;
        }

        @Override
        public CodecReader newReader(CodecInput in) {
            return (buf, off, len) -> value;
        }
    }

    /** Ignores {@code len} and writes {@code amount} bytes starting at {@code off + len}, reporting {@code len}. */
    static final class WritesOutside implements Codec {
        private final int amount;

        WritesOutside(int amount) {
            this.amount = amount;
        }

        @Override
        public CodecReader newReader(CodecInput in) {
            return (buf, off, len) -> {
                int end = Math.min(buf.length, off + len + amount);
                Arrays.fill(buf, off, end, (byte) 9);
                return len;
            };
        }
    }

    /** Ignores {@code len} and fills the whole array it was given, returning the array length. */
    static final class IgnoresLen implements Codec {
        @Override
        public CodecReader newReader(CodecInput in) {
            return (buf, off, len) -> {
                Arrays.fill(buf, (byte) 3);
                return buf.length;
            };
        }
    }

    /** Always fails with the given kind. */
    static final class Fails implements Codec {
        private final Kind kind;

        Fails(Kind kind) {
            this.kind = kind;
        }

        @Override
        public CodecReader newReader(CodecInput in) {
            return (buf, off, len) -> {
                throw new SpillgateException(kind, "the test codec fails");
            };
        }
    }

    /** Throws a plain IOException (not a SpillgateException). */
    static final class FailsWithIo implements Codec {
        @Override
        public CodecReader newReader(CodecInput in) {
            return (buf, off, len) -> {
                throw new IOException("plain io failure \n\u001b[31mevil");
            };
        }
    }

    /** Consumes {@code n} input bytes (fewer at the end) and emits the first of them: a shrinking layer. */
    static final class Shrink implements Codec {
        private final long n;

        Shrink(long n) {
            this.n = n;
        }

        @Override
        public CodecReader newReader(CodecInput in) {
            return (buf, off, len) -> {
                long got = 0;
                byte first = 0;
                while (got < n) {
                    int available = in.fill();
                    if (available == 0) {
                        break;
                    }
                    if (got == 0) {
                        first = in.buffer()[in.position()];
                    }
                    int take = (int) Math.min(available, n - got);
                    in.consume(take);
                    got += take;
                }
                if (got == 0) {
                    return -1;
                }
                buf[off] = first;
                return 1;
            };
        }
    }

    /**
     * Copies exactly {@code n} input bytes and then ends without looking at the input again (so it
     * never probes beyond its stream). Fewer than {@code n} bytes in the input are corrupt.
     */
    static final class TakeN implements Codec {
        private final int n;

        TakeN(int n) {
            this.n = n;
        }

        @Override
        public CodecReader newReader(CodecInput in) {
            return new CodecReader() {
                private int left = n;

                @Override
                public int read(byte[] buf, int off, int len) throws IOException {
                    if (left == 0) {
                        return -1;
                    }
                    int available = in.fill();
                    if (available == 0) {
                        throw new SpillgateException(Kind.CORRUPT, "take: the stream ends too early");
                    }
                    int k = Math.min(Math.min(available, len), left);
                    System.arraycopy(in.buffer(), in.position(), buf, off, k);
                    in.consume(k);
                    left -= k;
                    return k;
                }
            };
        }
    }

    /**
     * Reads and consumes its input one byte at a time, returns one byte per call, and reports every
     * input failure as corrupt (it swallows the real cause).
     */
    static final class ByteByByte implements Codec {
        @Override
        public CodecReader newReader(CodecInput in) {
            return (buf, off, len) -> {
                int available;
                try {
                    available = in.fill();
                } catch (IOException e) {
                    throw new SpillgateException(Kind.CORRUPT, "input failed (swallowed)");
                }
                if (available == 0) {
                    return -1;
                }
                buf[off] = in.buffer()[in.position()];
                in.consume(1);
                return 1;
            };
        }
    }

    /** Swallows an input failure and then ends cleanly, as if all was well. */
    static final class SwallowsAndEnds implements Codec {
        @Override
        public CodecReader newReader(CodecInput in) {
            return (buf, off, len) -> {
                try {
                    int available = in.fill();
                    if (available == 0) {
                        return -1;
                    }
                    int n = Math.min(available, len);
                    System.arraycopy(in.buffer(), in.position(), buf, off, n);
                    in.consume(n);
                    return n;
                } catch (IOException e) {
                    return -1;
                }
            };
        }
    }

    /** A pass-through that counts its readers created and closed, and can throw from close(). */
    static final class Counting implements Codec {
        final AtomicInteger created = new AtomicInteger();
        final AtomicInteger closed = new AtomicInteger();
        private final boolean throwOnClose;

        Counting(boolean throwOnClose) {
            this.throwOnClose = throwOnClose;
        }

        @Override
        public CodecReader newReader(CodecInput in) {
            created.incrementAndGet();
            return new CodecReader() {
                @Override
                public int read(byte[] buf, int off, int len) throws IOException {
                    int available = in.fill();
                    if (available == 0) {
                        return -1;
                    }
                    int n = Math.min(available, len);
                    System.arraycopy(in.buffer(), in.position(), buf, off, n);
                    in.consume(n);
                    return n;
                }

                @Override
                public void close() {
                    closed.incrementAndGet();
                    if (throwOnClose) {
                        throw new IllegalStateException("close() is hostile");
                    }
                }
            };
        }
    }

    /** Records the largest request it ever saw, fills with zeros. */
    static final class Spy implements Codec {
        final AtomicInteger largest = new AtomicInteger();

        @Override
        public CodecReader newReader(CodecInput in) {
            return (buf, off, len) -> {
                largest.accumulateAndGet(len, Math::max);
                Arrays.fill(buf, off, off + len, (byte) 0);
                return len;
            };
        }
    }

    /** Consumes more than it was shown. */
    static final class Greedy implements Codec {
        @Override
        public CodecReader newReader(CodecInput in) {
            return (buf, off, len) -> {
                int available = in.fill();
                if (available == 0) {
                    return -1;
                }
                int n = Math.min(available, len);
                Arrays.fill(buf, off, off + n, (byte) 1);
                in.consume(Integer.MAX_VALUE); // lies about what it used
                return n;
            };
        }
    }

    /** Counts calls after the end was returned. */
    static final class Ends implements Codec {
        final AtomicLong callsAfterEnd = new AtomicLong();
        private final int total;

        Ends(int total) {
            this.total = total;
        }

        @Override
        public CodecReader newReader(CodecInput in) {
            return new CodecReader() {
                private int left = total;
                private boolean ended;

                @Override
                public int read(byte[] buf, int off, int len) {
                    if (ended) {
                        callsAfterEnd.incrementAndGet();
                    }
                    if (left == 0) {
                        ended = true;
                        return -1;
                    }
                    int n = Math.min(len, left);
                    Arrays.fill(buf, off, off + n, (byte) 4);
                    left -= n;
                    return n;
                }
            };
        }
    }

    // -- the stored-only mini format ----------------------------------------------------------------------

    static final byte[] TRAILER = {'E', 'N', 'D', '!'};

    /** Encode {@code payload} in the stored format with chunks of at most {@code chunk} bytes. */
    static byte[] stored(byte[] payload, int chunk) {
        chunk = Math.max(1, Math.min(chunk, 65535));
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        for (int i = 0; i < payload.length; i += chunk) {
            int n = Math.min(chunk, payload.length - i);
            out.write(n & 0xff);
            out.write(n >>> 8);
            out.write(payload, i, n);
        }
        out.write(0);
        out.write(0);
        out.write(TRAILER, 0, TRAILER.length);
        return out.toByteArray();
    }

    /**
     * Chunks of {@code [len: u16 LE][len bytes]}, a chunk of length 0 ends the data, then the four
     * trailer bytes {@code END!}, then nothing. A missing byte anywhere, a wrong trailer and trailing
     * bytes are corrupt.
     */
    static final class Stored implements Codec {
        @Override
        public CodecReader newReader(CodecInput in) {
            return new CodecReader() {
                private int state; // 0 len, 1 body, 2 trailer, 3 done
                private int left;

                private int nextByte() throws IOException {
                    if (in.fill() == 0) {
                        throw new SpillgateException(Kind.CORRUPT, "stored: the stream ends too early");
                    }
                    int b = in.buffer()[in.position()] & 0xff;
                    in.consume(1);
                    return b;
                }

                @Override
                public int read(byte[] buf, int off, int len) throws IOException {
                    while (true) {
                        switch (state) {
                            case 0: {
                                int lo = nextByte();
                                int hi = nextByte();
                                left = lo | (hi << 8);
                                state = left == 0 ? 2 : 1;
                                break;
                            }
                            case 1: {
                                int available = in.fill();
                                if (available == 0) {
                                    throw new SpillgateException(Kind.CORRUPT, "stored: the stream ends too early");
                                }
                                int n = Math.min(Math.min(available, len), left);
                                System.arraycopy(in.buffer(), in.position(), buf, off, n);
                                in.consume(n);
                                left -= n;
                                if (left == 0) {
                                    state = 0;
                                }
                                return n;
                            }
                            case 2: {
                                for (byte expected : TRAILER) {
                                    if (nextByte() != (expected & 0xff)) {
                                        throw new SpillgateException(Kind.CORRUPT, "stored: bad trailer");
                                    }
                                }
                                if (in.fill() != 0) {
                                    throw new SpillgateException(Kind.CORRUPT, "stored: bytes after the trailer");
                                }
                                state = 3;
                                break;
                            }
                            default:
                                return -1;
                        }
                    }
                }
            };
        }
    }
}
