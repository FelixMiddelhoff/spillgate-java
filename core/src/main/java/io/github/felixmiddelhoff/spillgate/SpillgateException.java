package io.github.felixmiddelhoff.spillgate;

import java.io.IOException;
import java.util.Objects;

/**
 * Every refusal of the data (or of the configuration) is a {@code SpillgateException}. It is an
 * {@link IOException}, so it passes through {@link java.io.InputStream#read} unchanged, and
 * {@link #kind()} is one of the outcome classes of the specification.
 *
 * <p>Messages never contain more than 32 bytes of attacker-controlled data, quoted and escaped
 * (SG-R-HDR-13): use {@link #quote(byte[])} for any such text. Stack traces are not part of the
 * contract and are not filled in for sticky repeats (see {@link #repeat()}).
 */
public final class SpillgateException extends IOException {
    private static final long serialVersionUID = 1L;

    private final Kind kind;
    private final int layer;
    private final long limit;

    /** A new exception of {@code kind}. The message must not contain unquoted untrusted data. */
    public SpillgateException(Kind kind, String message) {
        this(kind, message, 0, 0, null);
    }

    /** A new exception with the failing layer (1-based, 0 if none), the limit exceeded and a cause. */
    public SpillgateException(Kind kind, String message, int layer, long limit, Throwable cause) {
        super(message, cause);
        this.kind = Objects.requireNonNull(kind, "kind");
        this.layer = layer;
        this.limit = limit;
    }

    /** The outcome class. */
    public Kind kind() {
        return kind;
    }

    /** 1-based index of the failing layer (layer 1 reads from the source), 0 if none. */
    public int layer() {
        return layer;
    }

    /** The limit that was exceeded, 0 if the kind has none. */
    public long limit() {
        return limit;
    }

    /** A copy with the layer named if none was named yet; the lowest layer wins. */
    public SpillgateException withLayer(int newLayer) {
        if (layer > 0 || newLayer <= 0) {
            return this;
        }
        return new SpillgateException(kind, getMessage(), newLayer, limit, getCause());
    }

    /**
     * A new exception of the same kind, layer, limit and message, for the sticky error returned again on
     * every later read (SG-R-LAY-05). It carries no stack trace of the original failure, so a retained
     * error does not pin the frames of the decoder.
     */
    public SpillgateException repeat() {
        SpillgateException copy = new SpillgateException(kind, getMessage(), layer, limit, getCause());
        copy.setStackTrace(new StackTraceElement[0]);
        return copy;
    }

    @Override
    public String getMessage() {
        StringBuilder text = new StringBuilder("spillgate: ").append(kind.specName());
        if (layer > 0) {
            text.append(" in layer ").append(layer);
        }
        if (limit > 0) {
            text.append(" (limit ").append(limit).append(')');
        }
        String detail = super.getMessage();
        if (detail != null && !detail.isEmpty()) {
            text.append(": ").append(detail);
        }
        return text.toString();
    }

    /**
     * Quote at most 32 bytes of untrusted text for use in a message: control and non-ASCII bytes are
     * escaped, quotes and backslashes too, and a longer text is cut and marked with {@code ...}
     * (SG-R-HDR-13).
     */
    public static String quote(byte[] bytes) {
        final int max = 32;
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < bytes.length && i < max; i++) {
            int b = bytes[i] & 0xff;
            if (b == '"') {
                out.append("\\\"");
            } else if (b == '\\') {
                out.append("\\\\");
            } else if (b >= 0x20 && b <= 0x7e) {
                out.append((char) b);
            } else {
                out.append(String.format("\\x%02x", b));
            }
        }
        if (bytes.length > max) {
            out.append("...");
        }
        return out.append('"').toString();
    }
}
