package io.github.felixmiddelhoff.spillgate;

import java.io.IOException;

/**
 * One decoding step for one layer. Contract:
 *
 * <ul>
 *   <li>{@link #read} returns between 1 and {@code len} bytes, or {@code -1} at the clean end of the
 *       stream (and only then, for {@code len > 0}). It never writes more than {@code len} bytes and
 *       never outside {@code [off, off + len)}.
 *   <li>It takes compressed input only from the {@link CodecInput} it was given. The bytes it
 *       <em>consumes</em> are what the ratio meter counts (SG-R-RAT-02), so it consumes only what it
 *       has used.
 *   <li>A stream that ends before its end marker or trailer is {@link Kind#CORRUPT} (SG-R-CDC-06),
 *       never a clean end. {@link CodecInput#fill()} returning 0 is the end of the input.
 *   <li>To report a broken stream it throws {@link SpillgateException} ({@link Kind#CORRUPT}, or
 *       {@link Kind#MEMORY_LIMIT} for a window cap). Any other exception, {@code Error} included, is a
 *       bug in the codec and becomes {@link Kind#INTERNAL} (SG-R-CDC-08). An exception from
 *       {@code input} (the source failing, a limit) is passed through unchanged.
 *   <li>It must not allocate from a value read from the stream, except its decoder window within the
 *       cap (SG-R-CDC-07, SG-R-MEM-01).
 * </ul>
 */
public interface CodecReader extends AutoCloseable {
    /** Decode into {@code buf[off, off + len)}; {@code -1} is the clean end of the stream. */
    int read(byte[] buf, int off, int len) throws IOException;

    /** Release decoder state (native memory, big arrays). Idempotent. Must not throw. */
    @Override
    default void close() {}
}
