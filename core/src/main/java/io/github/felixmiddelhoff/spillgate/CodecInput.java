package io.github.felixmiddelhoff.spillgate;

import java.io.IOException;

/**
 * The compressed input of one layer, as a window onto a buffer with exact accounting: what a codec
 * consumes is counted, what it only looks at is not.
 *
 * <p>Usage: {@code int n = input.fill();} makes up to {@code n} bytes available at
 * {@code buffer()[position() .. position() + n)}; {@code n == 0} is the end of the input. Then
 * {@code input.consume(k)} with {@code k <= n} for the bytes actually used. {@code fill()} returns
 * the remaining bytes of the current window without reading again if there are some.
 */
public interface CodecInput {
    /** Make input available; returns the number of bytes in the window, 0 at the end of the input. */
    int fill() throws IOException;

    /** The array holding the window. Valid until the next {@link #fill()} or {@link #consume(int)}. */
    byte[] buffer();

    /** Start of the window in {@link #buffer()}. */
    int position();

    /** Mark {@code n} bytes of the window as used ({@code 0 <= n <=} the size {@code fill()} returned). */
    void consume(int n);

    /**
     * Give back the byte that was consumed last if it is the one after the input limit (the probe,
     * number {@code maxInput + 1}): a codec that only looked at it, for instance because it buffered
     * ahead, has not taken it (SG-R-INP-01). Returns whether the byte was given back; false, and
     * nothing changes, for any other byte or without an input limit.
     */
    default boolean unreadProbe() {
        return false;
    }
}
