package io.github.felixmiddelhoff.spillgate;

/**
 * The outcome classes of SPEC section 2 ({@code ok} is the absence of an exception).
 */
public enum Kind {
    /** A limit parameter is missing, out of range or contradicts another one. */
    INVALID_LIMITS("invalid_limits", 500),
    /** A codec registration (name, options) is invalid. */
    INVALID_OPTION("invalid_option", 500),
    /** A layer would have produced more than {@code maxOutput} bytes. */
    OUTPUT_LIMIT("output_limit", 413),
    /** A layer (or the whole chain) expanded its input by more than {@code maxRatio}. */
    RATIO_LIMIT("ratio_limit", 413),
    /** The {@code Content-Encoding} chain has too many layers or elements. */
    LAYER_LIMIT("layer_limit", 413),
    /** More than {@code maxInput} compressed bytes were requested from the source. */
    INPUT_LIMIT("input_limit", 413),
    /** A codec would need more memory (window) than its cap allows. */
    MEMORY_LIMIT("memory_limit", 413),
    /** A well-formed token that no registered codec handles. */
    UNSUPPORTED_ENCODING("unsupported_encoding", 415),
    /** The {@code Content-Encoding} header itself is malformed. */
    MALFORMED_ENCODING("malformed_encoding", 400),
    /** The compressed data is invalid or truncated. */
    CORRUPT("corrupt", 400),
    /** A bug: a codec threw or broke its contract, or a source made no progress. */
    INTERNAL("internal", 500),
    /** The reader was canceled. */
    CANCELED("canceled", 503),
    /** The caller's source failed; the original exception is the cause. */
    SOURCE_ERROR("source_error", 400);

    private final String specName;
    private final int httpStatus;

    Kind(String specName, int httpStatus) {
        this.specName = specName;
        this.httpStatus = httpStatus;
    }

    /** The name used by the specification and the conformance corpus, e.g. {@code "output_limit"}. */
    public String specName() {
        return specName;
    }

    /**
     * The HTTP status a server should answer for this outcome when decoding a request body: 413 for
     * output, ratio, input, layer and memory limits, 415 for an unsupported encoding, 400 for a malformed
     * header, corrupt data and a failed read, 503 for cancellation, 500 otherwise.
     */
    public int httpStatus() {
        return httpStatus;
    }
}
