package io.github.felixmiddelhoff.spillgate.testsupport;

/** A generator spec (the {@code generator} object of a manifest vector). */
public sealed interface Spec {
    /** {@code zeros}: gzip/zlib/raw by {@code matches}, br/zstd by {@code size} and {@code window}. */
    record Zeros(String codec, long matches, long size, int window) implements Spec { }

    /** {@code data}: the stored construction of {@code Data(seed, size)} wrapped by the codec. */
    record Data(String codec, long seed, long size, int window) implements Spec { }

    /** {@code wrap}: the output of {@code inner}, wrapped {@code times} times in a stored stream. */
    record Wrap(String codec, long times, Spec inner, int window) implements Spec { }
}
