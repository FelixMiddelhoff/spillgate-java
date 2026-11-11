package io.github.felixmiddelhoff.spillgate.zstd;

import io.github.felixmiddelhoff.spillgate.Kind;
import io.github.felixmiddelhoff.spillgate.SpillgateException;

/** The errors of the decoder. Messages are constant text: no stream data is quoted. */
final class Err {
    private Err() {}

    static SpillgateException corrupt(String message) {
        return new SpillgateException(Kind.CORRUPT, "zstd: " + message);
    }

    static SpillgateException internal(String message) {
        return new SpillgateException(Kind.INTERNAL, "zstd: " + message);
    }

    static SpillgateException memory(String message, long limit) {
        return new SpillgateException(Kind.MEMORY_LIMIT, "zstd: " + message, 0, limit, null);
    }
}
