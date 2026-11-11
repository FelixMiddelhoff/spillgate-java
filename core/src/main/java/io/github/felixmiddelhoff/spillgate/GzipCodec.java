package io.github.felixmiddelhoff.spillgate;

import java.io.IOException;
import java.util.zip.CRC32;

/** gzip (RFC 1952), SG-R-CDC-02: members concatenated, strict framing, bounded work. */
final class GzipCodec implements Codec {
    private static final int FLAG_HCRC = 1 << 1;
    private static final int FLAG_EXTRA = 1 << 2;
    private static final int FLAG_NAME = 1 << 3;
    private static final int FLAG_COMMENT = 1 << 4;
    private static final int RESERVED_FLAGS = 0xe0;
    private static final int MAX_STRING = 512;

    @Override
    public CodecReader newReader(CodecInput input) {
        return new Reader(input);
    }

    private enum State { START, BODY, TRAILER, END, FAILED }

    private static final class Reader implements CodecReader {
        private final CodecInput input;
        private final RawInflate body = new RawInflate();
        private final CRC32 crc = new CRC32();
        private final CRC32 headerCrc = new CRC32();
        private long size;
        private boolean first = true;
        private boolean closed;
        private State state = State.START;
        private IOException failure;

        Reader(CodecInput input) {
            this.input = input;
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
            while (true) {
                switch (state) {
                    case START:
                        if (!startMember()) {
                            state = State.END;
                            body.release();
                            return -1;
                        }
                        first = false;
                        body.reset();
                        crc.reset();
                        size = 0;
                        state = State.BODY;
                        break;
                    case BODY:
                        int n = body.step(input, buf, off, len);
                        if (n < 0) {
                            state = State.TRAILER;
                            break;
                        }
                        crc.update(buf, off, n);
                        size += n;
                        return n;
                    case TRAILER:
                        checkTrailer();
                        state = State.START;
                        break;
                    default:
                        return -1;
                }
            }
        }

        private int next() throws IOException {
            if (input.fill() == 0) {
                return -1;
            }
            int b = input.buffer()[input.position()] & 0xff;
            input.consume(1);
            return b;
        }

        private int need(String what) throws IOException {
            int b = next();
            if (b < 0) {
                throw new SpillgateException(Kind.CORRUPT, "truncated gzip " + what);
            }
            return b;
        }

        private int needHeader(String what) throws IOException {
            int b = need(what);
            headerCrc.update(b);
            return b;
        }

        private void checkTrailer() throws IOException {
            long storedCrc = 0;
            for (int i = 0; i < 4; i++) {
                storedCrc |= (long) need("trailer") << (8 * i);
            }
            long storedSize = 0;
            for (int i = 0; i < 4; i++) {
                storedSize |= (long) need("trailer") << (8 * i);
            }
            if (storedCrc != crc.getValue()) {
                throw new SpillgateException(Kind.CORRUPT, "gzip checksum mismatch");
            }
            if (storedSize != (size & 0xffffffffL)) {
                throw new SpillgateException(Kind.CORRUPT, "gzip size mismatch");
            }
        }

        /** Parse a member header; false is the clean end of the input at a member boundary. */
        private boolean startMember() throws IOException {
            if (!first && input.fill() == 0) {
                return false;
            }
            headerCrc.reset();
            int id1 = needHeader("header");
            int id2 = needHeader("header");
            int cm = needHeader("header");
            if (id1 != 0x1f || id2 != 0x8b || cm != 8) {
                throw new SpillgateException(Kind.CORRUPT, "invalid gzip header");
            }
            int flags = needHeader("header");
            if ((flags & RESERVED_FLAGS) != 0) {
                throw new SpillgateException(Kind.CORRUPT, "reserved gzip header flags are set");
            }
            for (int i = 0; i < 6; i++) { // MTIME, XFL, OS
                needHeader("header");
            }
            if ((flags & FLAG_EXTRA) != 0) {
                int lo = needHeader("extra field");
                int xlen = lo | needHeader("extra field") << 8;
                while (xlen > 0) {
                    int avail = input.fill();
                    if (avail == 0) {
                        throw new SpillgateException(Kind.CORRUPT, "truncated gzip extra field");
                    }
                    int k = Math.min(avail, xlen);
                    headerCrc.update(input.buffer(), input.position(), k);
                    input.consume(k);
                    xlen -= k;
                }
            }
            if ((flags & FLAG_NAME) != 0) {
                skipString("name");
            }
            if ((flags & FLAG_COMMENT) != 0) {
                skipString("comment");
            }
            if ((flags & FLAG_HCRC) != 0) {
                long expected = headerCrc.getValue() & 0xffff;
                int stored = need("header checksum");
                stored |= need("header checksum") << 8;
                if (stored != expected) {
                    throw new SpillgateException(Kind.CORRUPT, "gzip header checksum mismatch");
                }
            }
            return true;
        }

        private void skipString(String what) throws IOException {
            for (int i = 1; i <= MAX_STRING; i++) {
                if (needHeader(what) == 0) {
                    return;
                }
            }
            throw new SpillgateException(Kind.CORRUPT, "gzip " + what + " is not terminated within 512 bytes");
        }

        @Override
        public void close() {
            closed = true;
            body.release();
        }
    }
}
