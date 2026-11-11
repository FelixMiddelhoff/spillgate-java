package io.github.felixmiddelhoff.spillgate.brotli;

/** Options of the brotli codec. Immutable. Validated by {@link BrotliCodec#create}. */
public final class BrotliOptions {
    private static final BrotliOptions DEFAULTS = new BrotliOptions(BrotliCodec.MAX_WINDOW_BITS);

    private final int maxWindowBits;

    private BrotliOptions(int maxWindowBits) {
        this.maxWindowBits = maxWindowBits;
    }

    /** Window cap 24 bits (16 MiB - 16 bytes), the largest window of RFC 7932. */
    public static BrotliOptions defaults() {
        return DEFAULTS;
    }

    /** A copy with the window cap {@code bits} (2^bits - 16 bytes); valid values are 10..24. */
    public BrotliOptions withMaxWindowBits(int bits) {
        return new BrotliOptions(bits);
    }

    /** The window cap in bits. */
    public int maxWindowBits() {
        return maxWindowBits;
    }
}
