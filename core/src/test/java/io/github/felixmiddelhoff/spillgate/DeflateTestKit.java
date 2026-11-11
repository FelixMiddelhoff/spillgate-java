package io.github.felixmiddelhoff.spillgate;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.GZIPOutputStream;

/** Test-only driver and generators for the gzip and deflate codec tests (no Decoder needed). */
final class DeflateTestKit {
    private DeflateTestKit() {}

    /** A CodecInput over a byte array; windows of {@code chunk} bytes are copied into a scratch array. */
    static final class ArrayInput implements CodecInput {
        private final byte[] data;
        private final int chunk;
        private final byte[] window;
        private int pos;
        private int windowStart;
        private int windowEnd;
        private int windowPos;
        long consumed;

        ArrayInput(byte[] data, int chunk) {
            this.data = data;
            this.chunk = chunk;
            this.window = new byte[chunk + 11];
        }

        @Override
        public int fill() {
            if (windowPos == windowEnd) {
                Arrays.fill(window, (byte) 0x5a); // stale window contents must never matter
                windowStart = 0;
                int n = Math.min(chunk, data.length - pos);
                System.arraycopy(data, pos, window, 7, n);
                windowPos = 7;
                windowEnd = 7 + n;
                windowStart = pos;
            }
            return windowEnd - windowPos;
        }

        @Override
        public byte[] buffer() {
            return window;
        }

        @Override
        public int position() {
            return windowPos;
        }

        @Override
        public void consume(int n) {
            if (n < 0 || n > windowEnd - windowPos) {
                throw new IllegalStateException("consume " + n + " of " + (windowEnd - windowPos));
            }
            windowPos += n;
            pos = windowStart + (windowPos - 7);
            consumed += n;
        }
    }

    /** What a drive produced: delivered bytes, the error kind (null on a clean end) and consumed input. */
    static final class Result {
        final byte[] output;
        final Kind error;
        final String message;
        final long consumed;

        Result(byte[] output, Kind error, String message, long consumed) {
            this.output = output;
            this.error = error;
            this.message = message;
            this.consumed = consumed;
        }

        @Override
        public String toString() {
            return "Result[" + output.length + " bytes, " + error + ", " + message + ", consumed " + consumed + "]";
        }
    }

    static Result drive(Codec codec, byte[] input, int readSize, int chunk) {
        ArrayInput in = new ArrayInput(input, chunk);
        CodecReader reader = codec.newReader(in);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[readSize + 6];
        try {
            while (true) {
                Arrays.fill(buf, (byte) 0x77);
                int n = reader.read(buf, 3, readSize);
                if (n == -1) {
                    // sticky end
                    if (reader.read(buf, 3, readSize) != -1) {
                        throw new AssertionError("end not sticky");
                    }
                    return new Result(out.toByteArray(), null, null, in.consumed);
                }
                if (n < 1 || n > readSize) {
                    throw new AssertionError("bad count " + n + " for len " + readSize);
                }
                for (int i = 0; i < 3; i++) {
                    if (buf[i] != 0x77 || buf[3 + readSize + i] != 0x77) {
                        throw new AssertionError("wrote outside the window");
                    }
                }
                out.write(buf, 3, n);
            }
        } catch (SpillgateException e) {
            try {
                reader.read(buf, 3, readSize);
                throw new AssertionError("error not sticky");
            } catch (SpillgateException again) {
                if (again.kind() != e.kind()) {
                    throw new AssertionError("sticky kind differs");
                }
            } catch (IOException other) {
                throw new AssertionError(other);
            }
            return new Result(out.toByteArray(), e.kind(), e.getMessage(), in.consumed);
        } catch (IOException e) {
            throw new AssertionError("unexpected " + e, e);
        } finally {
            reader.close();
            reader.close();
        }
    }

    static Result drive(Codec codec, byte[] input) {
        return drive(codec, input, 8192, 4096);
    }

    static byte[] hex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        }
        return out;
    }

    static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            out.write(p, 0, p.length);
        }
        return out.toByteArray();
    }

    static byte[] gzip(byte[] data) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
                gz.write(data);
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    /** The JDK deflater: zlib framing or raw. */
    static byte[] deflate(byte[] data, int level, int strategy, boolean nowrap) {
        Deflater d = new Deflater(level, nowrap);
        d.setStrategy(strategy);
        d.setInput(data);
        d.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        while (!d.finished()) {
            int n = d.deflate(buf);
            out.write(buf, 0, n);
        }
        d.end();
        return out.toByteArray();
    }

    static byte[] zlib(byte[] data) {
        return deflate(data, 6, Deflater.DEFAULT_STRATEGY, false);
    }

    static byte[] raw(byte[] data) {
        return deflate(data, 6, Deflater.DEFAULT_STRATEGY, true);
    }

    /** A gzip member around a raw body with an explicit header remainder (after the 10 fixed bytes). */
    static byte[] gzipMember(int flags, byte[] headerExtra, byte[] data) {
        byte[] body = raw(data);
        CRC32 crc = new CRC32();
        crc.update(data);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] fixed = {0x1f, (byte) 0x8b, 8, (byte) flags, 0, 0, 0, 0, 0, (byte) 0xff};
        out.write(fixed, 0, 10);
        out.write(headerExtra, 0, headerExtra.length);
        out.write(body, 0, body.length);
        long c = crc.getValue();
        for (int i = 0; i < 4; i++) {
            out.write((int) (c >> (8 * i)) & 0xff);
        }
        for (int i = 0; i < 4; i++) {
            out.write((data.length >> (8 * i)) & 0xff);
        }
        return out.toByteArray();
    }

    static byte[] text(int n, long seed) {
        java.util.Random r = new java.util.Random(seed);
        String[] words = {"alpha", "beta", "gamma", "delta", "spill", "gate", "the", "of", "\n", "  "};
        StringBuilder sb = new StringBuilder();
        while (sb.length() < n) {
            sb.append(words[r.nextInt(words.length)]).append(' ');
        }
        return Arrays.copyOf(sb.toString().getBytes(java.nio.charset.StandardCharsets.US_ASCII), n);
    }

    static byte[] random(int n, long seed) {
        byte[] b = new byte[n];
        new java.util.Random(seed).nextBytes(b);
        return b;
    }
}
