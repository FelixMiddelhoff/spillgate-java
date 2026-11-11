package io.github.felixmiddelhoff.spillgate.brotli;

import io.github.felixmiddelhoff.spillgate.CodecInput;
import io.github.felixmiddelhoff.spillgate.CodecReader;
import io.github.felixmiddelhoff.spillgate.Kind;
import io.github.felixmiddelhoff.spillgate.SpillgateException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import org.brotli.dec.BrotliInputStream;

/** Helpers shared by the brotli tests. */
final class Support {
    private Support() {}

    /** A CodecInput over a byte array that shows at most {@code chunk} bytes per window and counts. */
    static final class ArrayInput implements CodecInput {
        private final byte[] data;
        private final int chunk;
        private int pos;
        private int shown;
        long consumed;
        int violations;
        int fills;

        ArrayInput(byte[] data, int chunk) {
            this.data = data;
            this.chunk = Math.max(1, chunk);
        }

        @Override
        public int fill() {
            fills++;
            shown = Math.min(chunk, data.length - pos);
            return shown;
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
            if (n < 0 || n > shown) {
                violations++;
                n = Math.max(0, Math.min(n, shown));
            }
            pos += n;
            shown -= n;
            consumed += n;
        }
    }

    /** What a run delivered: the bytes, the error kind (null = clean end), the largest single read. */
    record Outcome(byte[] bytes, Kind kind, int maxRead, long consumed, int violations) {}

    static CodecReader reader(byte[] stream, int cap, int chunk, ArrayInput[] holder) throws IOException {
        ArrayInput in = new ArrayInput(stream, chunk);
        holder[0] = in;
        return BrotliCodec.create(BrotliOptions.defaults().withMaxWindowBits(cap)).newReader(in);
    }

    /** Drive the codec to its end (or an error, or {@code limit} bytes) with reads of {@code readSize}. */
    static Outcome run(byte[] stream, int cap, int chunk, int readSize, long limit) {
        ArrayInput[] h = new ArrayInput[1];
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int maxRead = 0;
        Kind kind = null;
        try (CodecReader r = reader(stream, cap, chunk, h)) {
            byte[] buf = new byte[readSize];
            while (true) {
                int n = r.read(buf, 0, buf.length);
                if (n < 0) {
                    break;
                }
                if (n == 0 || n > readSize) {
                    throw new AssertionError("bad read result " + n);
                }
                maxRead = Math.max(maxRead, n);
                out.write(buf, 0, n);
                if (out.size() >= limit) {
                    kind = Kind.OUTPUT_LIMIT;
                    break;
                }
            }
        } catch (SpillgateException e) {
            kind = e.kind();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new Outcome(out.toByteArray(), kind, maxRead, h[0].consumed, h[0].violations);
    }

    static Outcome run(byte[] stream) {
        return run(stream, 24, Integer.MAX_VALUE, 4096, Long.MAX_VALUE);
    }

    /** Decode with the reference decoder; null if it fails. */
    static byte[] reference(byte[] stream) {
        try (InputStream in = new BrotliInputStream(new ByteArrayInputStream(stream))) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] b = new byte[65536];
            int n;
            while ((n = in.read(b, 0, b.length)) >= 0) {
                out.write(b, 0, n);
                if (out.size() > (64 << 20)) {
                    return null;
                }
            }
            return out.toByteArray();
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    static byte[] resource(String name) {
        try (InputStream in = Support.class.getResourceAsStream("/golden/" + name)) {
            if (in == null) {
                throw new AssertionError("missing resource " + name);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** All golden stream names with their payload names. */
    static List<String[]> goldens() {
        List<String[]> l = new ArrayList<>();
        for (String n : new String[] {"text", "prose", "noise", "mixed", "empty", "one", "zeros"}) {
            for (int q : new int[] {0, 5, 11}) {
                for (int w : new int[] {10, 16, 24}) {
                    l.add(new String[] {n + ".q" + q + ".w" + w + ".br", n + ".raw", String.valueOf(w)});
                }
            }
        }
        return l;
    }

    /** Bit writer, least significant bit first (RFC 7932). */
    static final class BitWriter {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private long acc;
        private int count;

        BitWriter bits(long value, int n) {
            acc |= value << count;
            count += n;
            while (count >= 8) {
                out.write((int) (acc & 0xff));
                acc >>>= 8;
                count -= 8;
            }
            return this;
        }

        BitWriter align() {
            if (count > 0) {
                out.write((int) (acc & 0xff));
                acc = 0;
                count = 0;
            }
            return this;
        }

        BitWriter raw(byte[] b) {
            out.write(b, 0, b.length);
            return this;
        }

        byte[] finish() {
            align();
            return out.toByteArray();
        }
    }

    static void windowBits(BitWriter w, int wbits) {
        if (wbits == 16) {
            w.bits(0, 1);
        } else if (wbits == 17) {
            w.bits(1, 1).bits(0, 3).bits(0, 3);
        } else if (wbits >= 18) {
            w.bits(1, 1).bits(wbits - 17, 3);
        } else {
            w.bits(1, 1).bits(0, 3).bits(wbits - 8, 3);
        }
    }

    private static void mlen(BitWriter w, long mlen) {
        long v = mlen - 1;
        w.bits(0, 1);
        if (v < 1 << 16) {
            w.bits(0, 2).bits(v, 16);
        } else if (v < 1 << 20) {
            w.bits(1, 2).bits(v, 20);
        } else {
            w.bits(2, 2).bits(v, 24);
        }
    }

    /** {@code size} zero bytes in a few bytes per 16 MiB meta-block. */
    static byte[] zeros(long size, int wbits) {
        BitWriter w = new BitWriter();
        windowBits(w, wbits);
        long left = size;
        long min = 4 + 2118;
        while (left > 0) {
            long chunk = Math.min(left, 1L << 24);
            long rest = left - chunk;
            if (rest > 0 && rest < min) {
                chunk -= min;
            }
            mlen(w, chunk);
            w.bits(0, 1).bits(0, 3).bits(0, 2).bits(0, 4).bits(0, 2).bits(0, 2);
            long[][] codes = {{0, 8}, {423, 10}, {0, 6}};
            for (long[] c : codes) {
                w.bits(1, 2).bits(0, 2).bits(c[0], (int) c[1]);
            }
            w.bits(chunk - 4 - 2118, 24);
            left -= chunk;
        }
        w.bits(1, 1).bits(1, 1);
        return w.finish();
    }

    /** Uncompressed meta-blocks around {@code payload}. */
    static byte[] stored(byte[] payload, int wbits) {
        BitWriter w = new BitWriter();
        windowBits(w, wbits);
        for (int i = 0; i < payload.length; i += 1 << 24) {
            byte[] chunk = java.util.Arrays.copyOfRange(payload, i, Math.min(payload.length, i + (1 << 24)));
            mlen(w, chunk.length);
            w.bits(1, 1).align().raw(chunk);
        }
        w.bits(1, 1).bits(1, 1);
        return w.finish();
    }

    // -- hostile table header ------------------------------------------------------------------

    private static void varLen(BitWriter w, int value) {
        if (value == 1) {
            w.bits(0, 1);
            return;
        }
        int v = value - 1;
        w.bits(1, 1);
        if (v == 1) {
            w.bits(0, 3);
            return;
        }
        int n = 31 - Integer.numberOfLeadingZeros(v);
        w.bits(n, 3).bits(v - (1 << n), n);
    }

    private static void oneSymbol(BitWriter w, int symbol, int bits) {
        w.bits(1, 2).bits(0, 2).bits(symbol, bits);
    }

    /** A first meta-block asking for the most table memory: 256 block types and trees of each kind. */
    static byte[] tableHog(int wbits, int trees) {
        BitWriter w = new BitWriter();
        windowBits(w, wbits);
        w.bits(0, 1).bits(0, 2).bits(0xffff, 16).bits(0, 1);
        for (int i = 0; i < 3; i++) {
            varLen(w, trees);
            oneSymbol(w, 0, 9);
            oneSymbol(w, 0, 5);
            w.bits(0, 2);
        }
        w.bits(0, 2).bits(0, 4);
        for (int i = 0; i < trees; i++) {
            w.bits(0, 2);
        }
        for (int i = 0; i < 2; i++) {
            varLen(w, trees);
            w.bits(0, 1);
            oneSymbol(w, 0, 8);
            w.bits(0, 1);
        }
        for (int bits : new int[] {8, 10, 6}) {
            for (int i = 0; i < trees; i++) {
                oneSymbol(w, 0, bits);
            }
        }
        return w.finish();
    }
}
