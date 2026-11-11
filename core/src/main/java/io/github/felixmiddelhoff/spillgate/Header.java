package io.github.felixmiddelhoff.spillgate;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;

/**
 * Strict parsing of {@code Content-Encoding} field values (SPEC section 4).
 *
 * <p>The evaluation order is normative because it decides the outcome class when several faults are
 * present: total length, then element grammar left to right, then element count, then support
 * (alias resolution), then layer count. {@link #parse} does steps 1 to 3, {@link #resolve} steps 4
 * and 5. All case folding is manual ASCII (no {@code toLowerCase}: the Turkish-i trap), and the
 * 1024-byte cap is applied before any splitting.
 */
final class Header {
    /** SG-R-HDR-02: longest joined list, in bytes. */
    static final int MAX_LENGTH = 1024;
    /** SG-R-HDR-06: most elements, counting {@code identity}. */
    static final int MAX_ELEMENTS = 16;
    /** SG-R-HDR-04: longest token. */
    static final int MAX_TOKEN_LENGTH = 32;
    /** The no-op coding. */
    static final String IDENTITY = "identity";
    static final String GZIP = "gzip";
    static final String X_GZIP = "x-gzip";

    private Header() {}

    private static boolean alnum(int b) {
        return (b >= '0' && b <= '9') || (b >= 'a' && b <= 'z') || (b >= 'A' && b <= 'Z');
    }

    /** SG-R-HDR-04 for the bytes {@code [from, to)}: {@code [A-Za-z0-9][A-Za-z0-9._-]{0,31}}. */
    static boolean isToken(byte[] bytes, int from, int to) {
        int length = to - from;
        if (length < 1 || length > MAX_TOKEN_LENGTH || !alnum(bytes[from] & 0xff)) {
            return false;
        }
        for (int i = from + 1; i < to; i++) {
            int b = bytes[i] & 0xff;
            if (!alnum(b) && b != '.' && b != '_' && b != '-') {
                return false;
            }
        }
        return true;
    }

    /** SG-R-HDR-04 for a string (every char must be ASCII). */
    static boolean isToken(String name) {
        if (name == null || name.length() < 1 || name.length() > MAX_TOKEN_LENGTH) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean ok = i == 0 ? alnum(c) : (alnum(c) || c == '.' || c == '_' || c == '-');
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    /** True if the name has an ASCII upper case letter. */
    static boolean hasUpperCase(String name) {
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c >= 'A' && c <= 'Z') {
                return true;
            }
        }
        return false;
    }

    /**
     * Steps 1 to 3 of the evaluation order: length, grammar, element count. Returns the non-empty
     * elements in the order listed, lower-cased ({@code identity} and {@code x-gzip} are still
     * unresolved). {@code null} values are ignored like empty ones.
     */
    static List<String> parse(String[] values) throws SpillgateException {
        if (values == null) {
            return Collections.emptyList();
        }
        // Step 1 (cheap): a UTF-8 byte is at least one per char, so more chars than the cap can
        // never fit. No char of an oversized value is looked at.
        long chars = 0;
        int present = 0;
        for (String value : values) {
            if (value == null) {
                continue;
            }
            chars += value.length();
            present++;
            if (chars + present - 1 > MAX_LENGTH) {
                throw new SpillgateException(Kind.MALFORMED_ENCODING,
                        "Content-Encoding is longer than " + MAX_LENGTH + " bytes", 0, MAX_LENGTH, null);
            }
        }
        List<byte[]> joined = new ArrayList<>(present);
        long total = Math.max(present - 1, 0);
        for (String value : values) {
            if (value == null) {
                continue;
            }
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            total += bytes.length;
            joined.add(bytes);
        }
        if (total > MAX_LENGTH) {
            throw new SpillgateException(Kind.MALFORMED_ENCODING,
                    "Content-Encoding is " + total + " bytes long, at most " + MAX_LENGTH + " are accepted",
                    0, MAX_LENGTH, null);
        }
        List<String> elements = split(joined);
        if (elements.size() > MAX_ELEMENTS) {
            throw new SpillgateException(Kind.LAYER_LIMIT,
                    elements.size() + " encodings listed, at most " + MAX_ELEMENTS + " are accepted",
                    0, MAX_ELEMENTS, null);
        }
        return elements;
    }

    /** SG-R-HDR-01, 03, 04, 05: split every value at commas (same as join and split), trim, validate, fold. */
    private static List<String> split(List<byte[]> values) throws SpillgateException {
        List<String> elements = new ArrayList<>();
        for (byte[] value : values) {
            int start = 0;
            for (int i = 0; i <= value.length; i++) {
                if (i < value.length && value[i] != ',') {
                    continue;
                }
                int from = start;
                int to = i;
                start = i + 1;
                while (from < to && (value[from] == ' ' || value[from] == '\t')) {
                    from++;
                }
                while (to > from && (value[to - 1] == ' ' || value[to - 1] == '\t')) {
                    to--;
                }
                if (from == to) {
                    continue; // RFC 9110 section 5.6.1.2: empty list elements are ignored
                }
                if (!isToken(value, from, to)) {
                    byte[] part = new byte[Math.min(to - from, MAX_TOKEN_LENGTH + 1)];
                    System.arraycopy(value, from, part, 0, part.length);
                    throw new SpillgateException(Kind.MALFORMED_ENCODING,
                            "invalid encoding name " + SpillgateException.quote(part));
                }
                char[] folded = new char[to - from];
                for (int k = from; k < to; k++) {
                    int b = value[k];
                    folded[k - from] = (char) (b >= 'A' && b <= 'Z' ? b + ('a' - 'A') : b);
                }
                elements.add(new String(folded));
            }
        }
        return elements;
    }

    /**
     * Steps 4 and 5: resolve the {@code x-gzip} alias, require support for every element, count the
     * layers against {@code maxLayers}. Returns the layers in decoding order (layer 1 first,
     * SG-R-HDR-12); {@code identity} elements are dropped.
     */
    static List<String> resolve(List<String> elements, Predicate<String> supported, int maxLayers)
            throws SpillgateException {
        List<String> layers = new ArrayList<>(elements.size());
        for (String element : elements) {
            String name = X_GZIP.equals(element) ? GZIP : element;
            if (IDENTITY.equals(name)) {
                continue;
            }
            if (!supported.test(name)) {
                throw new SpillgateException(Kind.UNSUPPORTED_ENCODING,
                        "encoding " + SpillgateException.quote(name.getBytes(StandardCharsets.UTF_8))
                                + " is not supported");
            }
            layers.add(name);
        }
        if (layers.size() > maxLayers) {
            throw new SpillgateException(Kind.LAYER_LIMIT,
                    layers.size() + " encoding layers, at most " + maxLayers + " are accepted", 0, maxLayers, null);
        }
        Collections.reverse(layers);
        return layers;
    }
}
