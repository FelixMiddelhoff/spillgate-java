package io.github.felixmiddelhoff.spillgate.brotli;

import io.github.felixmiddelhoff.spillgate.Codec;
import io.github.felixmiddelhoff.spillgate.CodecInput;
import io.github.felixmiddelhoff.spillgate.CodecReader;
import io.github.felixmiddelhoff.spillgate.Kind;
import io.github.felixmiddelhoff.spillgate.SpillgateException;
import java.util.Objects;

/**
 * Brotli (RFC 7932) codec, register it as {@code "br"}. The decoder is a vendored copy of Google's
 * pure Java decoder (see VENDORED.md) behind own framing: the window is read from the first byte and
 * checked against the cap before any decoder state exists, the large-window marker is
 * {@code memory_limit} whatever the cap, output steps are at most 64 KiB, truncation and bytes after
 * the last meta-block are {@code corrupt}. Memory per stream: the window plus up to about 3.5 MiB of
 * tables for a hostile stream, about 0.1 MiB for an ordinary one. Output is delivered when the
 * decoder's ring buffer (the window) is full or the stream ends, not per meta-block.
 */
public final class BrotliCodec implements Codec {
    static final int MIN_WINDOW_BITS = 10;
    static final int MAX_WINDOW_BITS = 24;

    private final int maxWindowBits;

    private BrotliCodec(int maxWindowBits) {
        this.maxWindowBits = maxWindowBits;
    }

    /**
     * Create the codec.
     *
     * @throws SpillgateException {@code INVALID_OPTION} if the window cap is outside 10..24
     */
    public static BrotliCodec create(BrotliOptions options) throws SpillgateException {
        Objects.requireNonNull(options, "options");
        int bits = options.maxWindowBits();
        if (bits < MIN_WINDOW_BITS || bits > MAX_WINDOW_BITS) {
            throw new SpillgateException(
                    Kind.INVALID_OPTION, "maxWindowBits must be in " + MIN_WINDOW_BITS + ".." + MAX_WINDOW_BITS);
        }
        return new BrotliCodec(bits);
    }

    @Override
    public CodecReader newReader(CodecInput input) {
        return new BrotliReader(input, maxWindowBits);
    }

    /** RFC 7932 section 9.1: the window bits of the first byte; 0 means the large-window marker. */
    static int windowBits(int first) {
        if ((first & 1) == 0) {
            return 16;
        }
        int n = (first >> 1) & 7;
        if (n != 0) {
            return 17 + n;
        }
        int m = (first >> 4) & 7;
        if (m == 0) {
            return 17;
        }
        if (m == 1) {
            return 0;
        }
        return 8 + m;
    }
}
