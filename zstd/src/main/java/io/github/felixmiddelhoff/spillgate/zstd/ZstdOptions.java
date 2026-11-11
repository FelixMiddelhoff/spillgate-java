package io.github.felixmiddelhoff.spillgate.zstd;

/**
 * Options of the zstd codec. Immutable. The cap is validated by {@link ZstdCodec#create}, not here.
 */
public final class ZstdOptions {
    /** The default window cap: 8 MiB, the 8 MB that RFC 9659 requires decoders to support. */
    public static final long DEFAULT_MAX_WINDOW = 8L * 1024 * 1024;

    private final long maxWindowBytes;

    private ZstdOptions(long maxWindowBytes) {
        this.maxWindowBytes = maxWindowBytes;
    }

    /** The default options (window cap 8 MiB). */
    public static ZstdOptions defaults() {
        return new ZstdOptions(DEFAULT_MAX_WINDOW);
    }

    /** A copy with another window cap; valid values are 1 KiB to 1 GiB, checked by {@code create}. */
    public ZstdOptions withMaxWindowBytes(long bytes) {
        return new ZstdOptions(bytes);
    }

    /** The largest window in bytes a frame may declare. */
    public long maxWindowBytes() {
        return maxWindowBytes;
    }
}
