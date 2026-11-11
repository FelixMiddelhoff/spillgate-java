package io.github.felixmiddelhoff.spillgate;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Collects options for a {@link Decoder}. Errors surface in {@link #build()}. A builder is not
 * thread safe; the decoder it builds is.
 */
public final class DecoderBuilder {
    private static final class Registration {
        final String name;
        final Codec codec;

        Registration(String name, Codec codec) {
            this.name = name;
            this.codec = codec;
        }
    }

    private final Limits limits;
    private boolean strictZlib;
    private final List<Registration> registrations = new ArrayList<>();

    DecoderBuilder(Limits limits) {
        this.limits = limits;
    }

    /** Refuse {@code deflate} data without a zlib header instead of trying raw DEFLATE (SG-R-CDC-03). */
    public DecoderBuilder strictZlib(boolean on) {
        this.strictZlib = on;
        return this;
    }

    /**
     * Register an extension codec under {@code name} (SG-R-CDC-09), for example brotli or zstd. The
     * name must be a lower-case token (SG-R-HDR-04), must not be {@code identity}, {@code gzip},
     * {@code deflate} or {@code x-gzip}, and must not be registered twice; otherwise {@link #build()}
     * fails with {@link Kind#INVALID_OPTION}. The codec must be immutable and thread safe.
     */
    public DecoderBuilder codec(String name, Codec codec) {
        registrations.add(new Registration(name, codec));
        return this;
    }

    private static String quoted(String name) {
        String head = name.length() > Header.MAX_TOKEN_LENGTH + 1 ? name.substring(0, Header.MAX_TOKEN_LENGTH + 1) : name;
        return SpillgateException.quote(head.getBytes(StandardCharsets.UTF_8));
    }

    private static SpillgateException invalid(String message) {
        return new SpillgateException(Kind.INVALID_OPTION, message);
    }

    /**
     * Validate everything and capture it by value (SG-R-LIM-08, SG-R-LIM-09).
     *
     * @throws SpillgateException {@link Kind#INVALID_LIMITS} for bad limits, {@link Kind#INVALID_OPTION}
     *     for a bad codec registration
     */
    public Decoder build() throws SpillgateException {
        EffectiveLimits effective = EffectiveLimits.from(limits);
        Map<String, Codec> codecs = new HashMap<>();
        codecs.put(Header.GZIP, new GzipCodec());
        codecs.put("deflate", new DeflateCodec(strictZlib));
        for (Registration r : registrations) {
            if (r.name == null || !Header.isToken(r.name) || Header.hasUpperCase(r.name)) {
                throw invalid("codec name " + (r.name == null ? "null" : quoted(r.name))
                        + " must be a lower-case token (SG-R-HDR-04)");
            }
            if (Header.IDENTITY.equals(r.name) || Header.GZIP.equals(r.name) || "deflate".equals(r.name)
                    || Header.X_GZIP.equals(r.name)) {
                throw invalid("codec name " + quoted(r.name) + " is reserved for a built-in encoding");
            }
            if (r.codec == null) {
                throw invalid("codec " + quoted(r.name) + " is null");
            }
            if (codecs.containsKey(r.name)) {
                throw invalid("codec " + quoted(r.name) + " is registered twice");
            }
            codecs.put(r.name, r.codec);
        }
        return new Decoder(effective, Collections.unmodifiableMap(codecs));
    }
}
