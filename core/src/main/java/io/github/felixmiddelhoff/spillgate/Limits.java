package io.github.felixmiddelhoff.spillgate;

import java.util.OptionalInt;
import java.util.OptionalLong;

/**
 * What a {@link Decoder} enforces (SPEC section 3). Immutable; build one with
 * {@link #maxOutput(long)} and the {@code with...} methods. It is validated completely when the
 * decoder is constructed (SG-R-LIM-08), and a decoder keeps its own copy (SG-R-LIM-09).
 *
 * <p>{@code maxOutput} has no default and no "unlimited" value. Zero and negative values are
 * {@link Kind#INVALID_LIMITS}; values up to {@link Long#MAX_VALUE} (2^63-1) are valid.
 */
public final class Limits {
    private final long maxOutput;
    private final Integer maxLayers;
    private final Long maxRatio;
    private final Long ratioGrace;
    private final Long maxInput;
    private final boolean disableRatioLimit;

    private Limits(long maxOutput, Integer maxLayers, Long maxRatio, Long ratioGrace, Long maxInput, boolean disableRatioLimit) {
        this.maxOutput = maxOutput;
        this.maxLayers = maxLayers;
        this.maxRatio = maxRatio;
        this.ratioGrace = ratioGrace;
        this.maxInput = maxInput;
        this.disableRatioLimit = disableRatioLimit;
    }

    /** Limits with the required output bound: the bytes one layer may produce, {@code 1 .. 2^63-1}. */
    public static Limits maxOutput(long bytes) {
        return new Limits(bytes, null, null, null, null, false);
    }

    /** The longest chain of non-identity encodings, {@code 1 .. 16}. Default 5. An explicit 0 is invalid. */
    public Limits withMaxLayers(int layers) {
        return new Limits(maxOutput, layers, maxRatio, ratioGrace, maxInput, disableRatioLimit);
    }

    /** Output bytes per consumed input byte, {@code 1 .. 2^31-1}. Default 1000. Cannot be combined with {@link #withoutRatioLimit()}. */
    public Limits withMaxRatio(long ratio) {
        return new Limits(maxOutput, maxLayers, ratio, ratioGrace, maxInput, disableRatioLimit);
    }

    /** Output size in bytes below which the ratio is not checked. Default 64 KiB; 0 checks from the first produced byte. */
    public Limits withRatioGrace(long bytes) {
        return new Limits(maxOutput, maxLayers, maxRatio, bytes, maxInput, disableRatioLimit);
    }

    /** An optional cap on the compressed bytes taken from the source, {@code 1 .. 2^63-1}. Zero is invalid and does not mean unlimited. */
    public Limits withMaxInput(long bytes) {
        return new Limits(maxOutput, maxLayers, maxRatio, ratioGrace, bytes, disableRatioLimit);
    }

    /** The one named opt-out of the ratio check (SG-R-LIM-04). {@code maxOutput}, {@code maxLayers} and {@code maxInput} stay in force. */
    public Limits withoutRatioLimit() {
        return new Limits(maxOutput, maxLayers, maxRatio, ratioGrace, maxInput, true);
    }

    long rawMaxOutput() {
        return maxOutput;
    }

    OptionalInt rawMaxLayers() {
        return maxLayers == null ? OptionalInt.empty() : OptionalInt.of(maxLayers);
    }

    OptionalLong rawMaxRatio() {
        return maxRatio == null ? OptionalLong.empty() : OptionalLong.of(maxRatio);
    }

    OptionalLong rawRatioGrace() {
        return ratioGrace == null ? OptionalLong.empty() : OptionalLong.of(ratioGrace);
    }

    OptionalLong rawMaxInput() {
        return maxInput == null ? OptionalLong.empty() : OptionalLong.of(maxInput);
    }

    boolean rawDisableRatioLimit() {
        return disableRatioLimit;
    }

    @Override
    public String toString() {
        return "Limits[maxOutput=" + maxOutput + ", maxLayers=" + (maxLayers == null ? "default" : maxLayers)
                + ", maxRatio=" + (disableRatioLimit ? "disabled" : maxRatio == null ? "default" : maxRatio)
                + ", ratioGrace=" + (ratioGrace == null ? "default" : ratioGrace)
                + ", maxInput=" + (maxInput == null ? "none" : maxInput) + "]";
    }
}
