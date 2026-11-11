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
}
