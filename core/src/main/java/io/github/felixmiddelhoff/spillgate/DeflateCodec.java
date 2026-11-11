package io.github.felixmiddelhoff.spillgate;

import java.io.IOException;
import java.util.zip.Adler32;

/** HTTP deflate (RFC 9110 section 8.4.1.2), SG-R-CDC-03: zlib framing with an optional raw fallback. */
final class DeflateCodec implements Codec {
    private final boolean strictZlib;

    DeflateCodec(boolean strictZlib) {
        this.strictZlib = strictZlib;
    }

    /** RFC 1950: CM = 8, CINFO <= 7 and CMF * 256 + FLG a multiple of 31. */
    static boolean isZlibHeader(int cmf, int flg) {
        return (cmf & 0x0f) == 8 && (cmf >> 4) <= 7 && ((cmf << 8) | flg) % 31 == 0;
    }

    @Override
    public CodecReader newReader(CodecInput input) {
        return new Reader(input, strictZlib);
    }

    private enum State { START, BODY, END, FAILED }

    private static final class Reader implements CodecReader {
        private final CodecInput input;
        private final boolean strict;
        private final RawInflate body = new RawInflate();
        private final Adler32 adler = new Adler32();
        private boolean zlib;
        private boolean closed;
        private State state = State.START;
        private IOException failure;

        Reader(CodecInput input, boolean strict) {
            this.input = input;
            this.strict = strict;
        }

        @Override
        public int read(byte[] buf, int off, int len) throws IOException {
            if (closed) {
                throw new IOException("stream closed");
            }
            if (len == 0) {
                return 0;
            }
            if (state == State.FAILED) {
                throw failure instanceof SpillgateException ? ((SpillgateException) failure).repeat() : failure;
            }
            try {
                return run(buf, off, len);
            } catch (IOException | RuntimeException | Error e) {
                state = State.FAILED;
                body.release();
                if (e instanceof IOException) {
                    failure = (IOException) e;
                } else {
                    failure = new SpillgateException(Kind.INTERNAL, "codec failure");
                }
                throw e;
            }
        }

        private int run(byte[] buf, int off, int len) throws IOException {
            if (state == State.END) {
                return -1;
            }
            if (state == State.START) {
                start();
                state = State.BODY;
            }
            int n = body.step(input, buf, off, len);
            if (n > 0) {
                if (zlib) {
                    adler.update(buf, off, n);
                }
                return n;
            }
            finish();
            state = State.END;
            body.release();
            return -1;
        }

        private int need() throws IOException {
            if (input.fill() == 0) {
                throw new SpillgateException(Kind.CORRUPT, "truncated deflate data");
            }
            int b = input.buffer()[input.position()] & 0xff;
            input.consume(1);
            return b;
        }

        private void start() throws IOException {
            int b0 = need();
            int b1 = need();
            body.reset();
            if (isZlibHeader(b0, b1)) {
                if ((b1 & 0x20) != 0) {
                    throw new SpillgateException(Kind.CORRUPT, "preset dictionaries are not supported");
                }
                zlib = true;
            } else if (strict) {
                throw new SpillgateException(Kind.CORRUPT, "not a zlib stream");
            } else {
                body.setPrefix(b0, b1);
            }
        }

        private void finish() throws IOException {
            if (zlib) {
                long stored = 0;
                for (int i = 0; i < 4; i++) {
                    stored = stored << 8 | need();
                }
                if (stored != adler.getValue()) {
                    throw new SpillgateException(Kind.CORRUPT, "deflate checksum mismatch");
                }
            }
            if (body.unusedPrefix() > 0 || input.fill() > 0) {
                throw new SpillgateException(Kind.CORRUPT, "unexpected data after the end of the deflate stream");
            }
        }

        @Override
        public void close() {
            closed = true;
            body.release();
        }
    }
}
