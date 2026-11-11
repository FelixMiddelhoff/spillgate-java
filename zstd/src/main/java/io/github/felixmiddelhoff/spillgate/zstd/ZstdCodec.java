package io.github.felixmiddelhoff.spillgate.zstd;

import io.github.felixmiddelhoff.spillgate.Codec;
import io.github.felixmiddelhoff.spillgate.CodecInput;
import io.github.felixmiddelhoff.spillgate.CodecReader;
import io.github.felixmiddelhoff.spillgate.Kind;
import io.github.felixmiddelhoff.spillgate.SpillgateException;

/**
 * The zstd codec (RFC 8878, RFC 9659), an own decoder written for hostile input. Frames whose window
 * exceeds the cap are refused as {@code MEMORY_LIMIT} before any memory is allocated for them.
 * Immutable and thread safe; readers are not.
 */
public final class ZstdCodec implements Codec {
    static final long MIN_WINDOW = 1L << 10;
    static final long MAX_WINDOW = 1L << 30;

    private final long maxWindow;

    private ZstdCodec(long maxWindow) {
        this.maxWindow = maxWindow;
    }

    /** Build the codec; the cap must be in 1 KiB to 1 GiB, else {@code INVALID_OPTION}. */
    public static ZstdCodec create(ZstdOptions options) throws SpillgateException {
        if (options == null) {
            throw new SpillgateException(Kind.INVALID_OPTION, "zstd: options are missing");
        }
        long cap = options.maxWindowBytes();
        if (cap < MIN_WINDOW || cap > MAX_WINDOW) {
            throw new SpillgateException(
                    Kind.INVALID_OPTION, "zstd: maxWindowBytes must be in " + MIN_WINDOW + ".." + MAX_WINDOW);
        }
        return new ZstdCodec(cap);
    }

    /** The window cap of this codec. */
    public long maxWindowBytes() {
        return maxWindow;
    }

    @Override
    public CodecReader newReader(CodecInput input) {
        return new ZstdReader(input, maxWindow);
    }
}
