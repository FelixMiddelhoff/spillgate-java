package io.github.felixmiddelhoff.spillgate;

/**
 * The codec interface, for the built-in codecs and for extensions such as brotli and zstd
 * (SG-R-CDC-09). A codec is registered on one decoder instance
 * ({@link DecoderBuilder#codec(String, Codec)}); there is no global registry. Every codec, built in
 * or not, runs behind the same limiter, ratio meter and input meter (SG-R-CDC-01), so a sloppy codec
 * is still bounded. Implementations must be immutable and thread safe; the readers are not.
 */
public interface Codec {
    /** A reader that takes its compressed input from {@code input}. */
    CodecReader newReader(CodecInput input);
}
