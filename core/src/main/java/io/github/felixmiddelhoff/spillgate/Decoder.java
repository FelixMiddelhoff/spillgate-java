package io.github.felixmiddelhoff.spillgate;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Turns {@code Content-Encoding} chains into bounded streams.
 *
 * <p>A decoder is immutable after construction and thread safe: share one per process. The streams it
 * returns are independent of each other and not meant to be shared between threads (SG-R-RDR-07).
 * Every check that depends only on the limits and options happens in
 * {@link DecoderBuilder#build()}, before any byte is read (SG-R-LIM-08), and every check that
 * depends only on the header happens in {@code reader(...)}, before the source is touched
 * (SG-R-HDR-09, SG-R-API-03). The limits are captured by value (SG-R-LIM-09).
 *
 * <p>Memory: apart from codec windows within their caps, a stream holds at most 4 KiB (input buffer)
 * plus 64 KiB and a few bytes (codec scratch) per layer, so at most about 1.1 MiB for 16 layers;
 * {@code decodeAll} adds the bytes produced so far (SG-R-MEM-02), growing its buffer with the
 * output and never from {@code maxOutput}.
 */
public final class Decoder {
    /** The longest array a JVM can allocate, with a safety margin. */
    private static final int MAX_ARRAY = Integer.MAX_VALUE - 8;

    private final EffectiveLimits limits;
    private final Map<String, Codec> codecs;

    Decoder(EffectiveLimits limits, Map<String, Codec> codecs) {
        this.limits = limits;
        this.codecs = codecs;
    }

    /** A decoder with the built-in codecs ({@code identity}, {@code gzip}, {@code deflate}) under {@code limits}. */
    public static Decoder of(Limits limits) throws SpillgateException {
        return builder(limits).build();
    }

    /** Start building a decoder; limits are mandatory (SG-R-API-01). */
    public static DecoderBuilder builder(Limits limits) {
        return new DecoderBuilder(limits);
    }

    /**
     * A stream of the decoded bytes of {@code source}. {@code contentEncoding} holds the header field
     * values as received, e.g. {@code "gzip, br", "zstd"}. The source is not touched and not closed.
     *
     * @throws SpillgateException {@link Kind#MALFORMED_ENCODING}, {@link Kind#LAYER_LIMIT} or
     *     {@link Kind#UNSUPPORTED_ENCODING} if the header is refused
     */
    public SpillgateInputStream reader(InputStream source, String... contentEncoding) throws SpillgateException {
        return open(source, null, contentEncoding);
    }

    /**
     * Like {@link #reader(InputStream, String...)}, and the stream stops with {@link Kind#CANCELED}
     * (sticky) once {@code token} is canceled. See {@link CancelToken} for what is and is not
     * interrupted (SG-R-API-04).
     */
    public SpillgateInputStream reader(InputStream source, CancelToken token, String... contentEncoding)
            throws SpillgateException {
        return open(source, token, contentEncoding);
    }

    /**
     * Decode {@code source} completely: the whole body, or an exception and no data (SG-R-API-02).
     * The buffer grows with the bytes produced, never from {@code maxOutput} (SG-R-MEM-02). A body
     * that cannot be held in one Java array fails with {@link Kind#OUTPUT_LIMIT}.
     */
    public byte[] decodeAll(InputStream source, String... contentEncoding) throws IOException {
        SpillgateInputStream stream = open(source, null, contentEncoding);
        try {
            return readAll(stream);
        } finally {
            stream.close();
        }
    }

    /** Decode {@code source} completely under {@code limits}. No call omits the limits (SG-R-API-01). */
    public static byte[] decodeAll(InputStream source, Limits limits, String... contentEncoding) throws IOException {
        return of(limits).decodeAll(source, contentEncoding);
    }

    private static byte[] readAll(SpillgateInputStream stream) throws IOException {
        byte[] out = new byte[8192];
        int size = 0;
        byte[] chunk = new byte[LayerGuard.MAX_STEP];
        while (true) {
            int n = stream.read(chunk, 0, chunk.length);
            if (n < 0) {
                return size == out.length ? out : Arrays.copyOf(out, size);
            }
            if (n > MAX_ARRAY - size) {
                throw new SpillgateException(Kind.OUTPUT_LIMIT,
                        "the body does not fit in a Java array", 0, MAX_ARRAY, null);
            }
            int needed = size + n;
            if (needed > out.length) {
                long doubled = Math.max((long) out.length * 2, needed);
                out = Arrays.copyOf(out, (int) Math.min(doubled, MAX_ARRAY));
            }
            System.arraycopy(chunk, 0, out, size, n);
            size = needed;
        }
    }

    private SpillgateInputStream open(InputStream source, CancelToken token, String[] contentEncoding)
            throws SpillgateException {
        Objects.requireNonNull(source, "source");
        List<String> elements = Header.parse(contentEncoding);
        List<String> layers = Header.resolve(elements, codecs::containsKey, limits.maxLayers);
        return buildChain(source, layers, token);
    }

    private SpillgateInputStream buildChain(InputStream source, List<String> layers, CancelToken token)
            throws SpillgateException {
        int count = Math.max(layers.size(), 1);
        List<LayerGuard> guards = new ArrayList<>(count);
        InputMeter first = null;
        InputStream current = source;
        try {
            for (int index = 1; index <= count; index++) {
                // The input cap applies to the chain's source only (SG-R-INP-01).
                long cap = index == 1 ? limits.maxInput : 0;
                InputMeter meter = new InputMeter(current, cap, index, token);
                if (first == null) {
                    first = meter;
                }
                CodecReader reader;
                if (layers.isEmpty()) {
                    reader = new LayerGuard.Passthrough(meter); // identity-only chain
                } else {
                    reader = newCodecReader(layers.get(index - 1), meter, index);
                }
                LayerGuard guard = new LayerGuard(reader, meter, index, limits);
                guards.add(guard);
                current = guard;
            }
        } catch (SpillgateException | RuntimeException | Error e) {
            for (LayerGuard guard : guards) {
                guard.close();
            }
            throw e;
        }
        return new SpillgateInputStream(guards.toArray(new LayerGuard[0]), first, limits, token);
    }

    private CodecReader newCodecReader(String name, CodecInput input, int layer) throws SpillgateException {
        Codec codec = codecs.get(name);
        if (codec == null) {
            throw new SpillgateException(Kind.INTERNAL, "codec table changed", layer, 0, null);
        }
        // A codec that throws while it is constructed is contained like one that throws later.
        CodecReader reader;
        try {
            reader = codec.newReader(input);
        } catch (Throwable t) {
            if (t instanceof VirtualMachineError && !(t instanceof StackOverflowError)) {
                throw (VirtualMachineError) t;
            }
            throw new SpillgateException(Kind.INTERNAL,
                    "creating the " + SpillgateException.quote(name.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                            + " decoder failed",
                    layer, 0, t);
        }
        if (reader == null) {
            throw new SpillgateException(Kind.INTERNAL, "the codec returned no reader", layer, 0, null);
        }
        return reader;
    }
}
