package io.github.felixmiddelhoff.spillgate.zstd;

import io.github.felixmiddelhoff.spillgate.CodecInput;
import io.github.felixmiddelhoff.spillgate.CodecReader;
import io.github.felixmiddelhoff.spillgate.SpillgateException;

import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;

/** The zstd stream: frames, skippable frames and the block loop. */
final class ZstdReader implements CodecReader {
    private static final long FRAME_MAGIC = 0xfd2fb528L;
    private static final long SKIPPABLE_MAGIC = 0x184d2a50L;
    private static final long SKIPPABLE_MASK = 0xfffffff0L;
    /** Skippable frames are skipped and metered in steps of at most this many bytes. */
    private static final int SKIP_STEP = 4096;
    private static final int[] DICTIONARY_BYTES = {0, 1, 2, 4};
    private static final int[] CONTENT_SIZE_BYTES = {0, 2, 4, 8};

    private final CodecInput in;
    private final long maxWindow;
    private final Scratch scratch = new Scratch();
    private final byte[] small = new byte[8];

    private long frames;
    private Frame frame;
    private Xxh64 hasher;
    private boolean hasDeclared;
    private long declared;
    private int outPos;
    private IOException deferred;
    private IOException failed;
    private boolean finished;
    private boolean closed;

    ZstdReader(CodecInput in, long maxWindow) {
        this.in = in;
        this.maxWindow = maxWindow;
    }

    /** Heap held by this reader (test accounting). */
    long heapBytes() {
        return scratch.heapBytes() + (frame == null ? 0 : frame.windowCapacity());
    }

    private void release() {
        frame = null;
        hasher = null;
        scratch.release();
        outPos = 0;
    }

    @Override
    public void close() {
        closed = true;
        release();
    }

    @Override
    public int read(byte[] buf, int off, int len) throws IOException {
        Objects.checkFromIndexSize(off, len, buf.length);
        if (failed != null) {
            throw again(failed);
        }
        if (closed) {
            throw new IOException("zstd reader closed");
        }
        if (len == 0) {
            return 0;
        }
        if (finished) {
            return -1;
        }
        try {
            int n = readInner(buf, off, len);
            if (n < 0) {
                finished = true;
                release();
            }
            return n;
        } catch (IOException e) {
            failed = e;
            release();
            throw e;
        }
    }

    private static IOException again(IOException e) {
        return e instanceof SpillgateException s ? s.repeat() : e;
    }

    private int readInner(byte[] buf, int off, int len) throws IOException {
        while (true) {
            if (outPos < scratch.outLen) {
                int n = Math.min(scratch.outLen - outPos, len);
                System.arraycopy(scratch.out, outPos, buf, off, n);
                outPos += n;
                return n;
            }
            if (deferred != null) {
                IOException e = deferred;
                deferred = null;
                throw e;
            }
            if (frame == null) {
                if (!nextFrame()) {
                    return -1;
                }
            } else {
                block();
            }
        }
    }

    // -- input ------------------------------------------------------------------------

    private static SpillgateException truncated(String what) {
        return Err.corrupt("the input ends inside the " + what);
    }

    private int available() throws IOException {
        int n = in.fill();
        if (n < 0 || in.position() < 0 || (long) in.position() + n > in.buffer().length) {
            throw Err.internal("input bookkeeping failed");
        }
        return n;
    }

    /** Fill {@code dst[off, off + n)} completely; the end of the input first is corrupt. */
    private void readExact(byte[] dst, int off, int n, String what) throws IOException {
        int filled = 0;
        while (filled < n) {
            int avail = available();
            if (avail == 0) {
                throw truncated(what);
            }
            int take = Math.min(avail, n - filled);
            System.arraycopy(in.buffer(), in.position(), dst, off + filled, take);
            in.consume(take);
            filled += take;
        }
    }

    /** Little-endian value of the first {@code n <= 8} bytes of {@link #small}, as a long (bits, not signed). */
    private long le(int n) {
        long v = 0;
        for (int i = n - 1; i >= 0; i--) {
            v = (v << 8) | (small[i] & 0xffL);
        }
        return v;
    }

    /** Skip {@code n >= 0} bytes in steps of at most {@link #SKIP_STEP}. */
    private void skip(long n, String what) throws IOException {
        while (n > 0) {
            int avail = available();
            if (avail == 0) {
                throw truncated(what);
            }
            int step = (int) Math.min(Math.min(avail, SKIP_STEP), n);
            in.consume(step);
            n -= step;
        }
    }

    // -- frames -----------------------------------------------------------------------

    /** Start the next frame. False means a clean end of the input. */
    private boolean nextFrame() throws IOException {
        if (available() == 0) {
            if (frames > 0) {
                return false;
            }
            throw Err.corrupt("an empty input is not a zstd stream");
        }
        readExact(small, 0, 4, "zstd magic number");
        long magic = le(4);
        if (magic == FRAME_MAGIC) {
            startFrame();
        } else if ((magic & SKIPPABLE_MASK) == SKIPPABLE_MAGIC) {
            readExact(small, 0, 4, "skippable frame header");
            skip(le(4), "skippable frame");
            frames++; // a skippable frame is a frame: the input may end after it
        } else {
            throw Err.corrupt("data that is not a zstd frame follows the stream");
        }
        return true;
    }

    private void startFrame() throws IOException {
        readExact(small, 0, 1, "frame header");
        int descriptor = small[0] & 0xff;
        boolean singleSegment = (descriptor & (1 << 5)) != 0;
        boolean checksum = (descriptor & (1 << 2)) != 0;
        if ((descriptor & (1 << 3)) != 0) {
            throw Err.corrupt("reserved bit set in the frame header");
        }

        long window = 0;
        if (!singleSegment) {
            readExact(small, 0, 1, "frame header");
            int wd = small[0] & 0xff;
            long base = 1L << (10 + (wd >> 3));
            window = base + (base / 8) * (wd & 7);
        }
        int dictionaryLen = DICTIONARY_BYTES[descriptor & 3];
        if (dictionaryLen > 0) {
            readExact(small, 0, dictionaryLen, "frame header");
            if (le(dictionaryLen) != 0) {
                throw Err.corrupt("the frame needs a dictionary");
            }
        }

        int flag = descriptor >> 6;
        int fcsLen = (flag == 0 && singleSegment) ? 1 : CONTENT_SIZE_BYTES[flag];
        if (fcsLen > 0) {
            readExact(small, 0, fcsLen, "frame header");
            // for the 8-byte form the value may exceed 2^63: it is only compared as unsigned bits
            declared = le(fcsLen) + (fcsLen == 2 ? 256 : 0);
            hasDeclared = true;
        } else {
            declared = 0;
            hasDeclared = false;
        }
        if (singleSegment) {
            window = declared;
        }

        // the memory declaration of the frame is checked before anything is allocated (SG-R-CDC-05)
        if (Long.compareUnsigned(window, maxWindow) > 0) {
            throw Err.memory(
                    "the frame asks for a window of " + Long.toUnsignedString(window) + " bytes (the cap is "
                            + maxWindow + ")",
                    maxWindow);
        }
        frame = new Frame((int) window);
        hasher = checksum ? new Xxh64() : null;
    }

    // -- blocks -----------------------------------------------------------------------

    private void block() throws IOException {
        Frame f = frame;
        if (f == null) {
            throw Err.internal("no frame");
        }
        readExact(small, 0, 3, "block header");
        long header = le(3);
        boolean last = (header & 1) != 0;
        int kind = (int) ((header >> 1) & 3);
        int size = (int) (header >> 3); // below 2^21
        if (kind == 3) {
            throw Err.corrupt("reserved block type");
        }
        if (size > Scratch.BLOCK_MAX || size > f.blockMax()) {
            throw Err.corrupt("a block is larger than its maximum of " + f.blockMax() + " bytes");
        }
        Scratch s = scratch;
        switch (kind) {
            case 1 -> {
                // the size field is the regenerated size
                readExact(small, 0, 1, "block");
                s.ensureOut(size);
                Arrays.fill(s.out, 0, size, small[0]);
                s.outLen = size;
            }
            case 0 -> {
                s.ensureOut(size);
                readExact(s.out, 0, size, "block");
                s.outLen = size;
            }
            default -> {
                s.ensureContent(size);
                readExact(s.content, 0, size, "block");
                s.contentLen = size;
                f.compressed(s);
            }
        }
        f.push(s.out, s.outLen);
        if (hasher != null) {
            hasher.update(s.out, 0, s.outLen);
        }
        outPos = 0;
        if (last) {
            endFrame();
        }
    }

    /**
     * The last block was decoded. A failure found here (checksum, declared size) is kept and raised
     * after the bytes of this block were delivered, as the output limit comes first in stream order
     * (SG-R-LAY-07).
     */
    private void endFrame() {
        try {
            checkEnd();
        } catch (IOException e) {
            deferred = e;
        }
        frame = null;
        hasher = null;
        frames++;
    }

    private void checkEnd() throws IOException {
        if (hasher != null) {
            readExact(small, 0, 4, "content checksum");
            if (le(4) != hasher.digest32()) {
                throw Err.corrupt("content checksum mismatch");
            }
        }
        long produced = frame == null ? 0 : frame.total();
        if (hasDeclared && declared != produced) {
            // Only an integrity check; the declared size is never an allowance (SG-R-CDC-07).
            throw Err.corrupt("the frame size differs from its declared size");
        }
    }
}
