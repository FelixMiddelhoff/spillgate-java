package io.github.felixmiddelhoff.spillgate;

/** The {@link Limits} with defaults filled in and every value checked. */
final class EffectiveLimits {
    static final int DEFAULT_MAX_LAYERS = 5;
    static final long DEFAULT_MAX_RATIO = 1000;
    static final long DEFAULT_RATIO_GRACE = 64 * 1024;
    static final int HARD_MAX_LAYERS = 16;
    static final long HARD_MAX_RATIO = (1L << 31) - 1;

    /** Bytes one layer may produce. */
    final long maxOutput;
    /** Longest chain of non-identity layers. */
    final int maxLayers;
    /** Output bytes per consumed input byte; 0 means the ratio check is off. */
    final long maxRatio;
    /** Output size below which the ratio is not checked. */
    final long ratioGrace;
    /** Compressed bytes the source may deliver; 0 means no cap. */
    final long maxInput;

    private EffectiveLimits(long maxOutput, int maxLayers, long maxRatio, long ratioGrace, long maxInput) {
        this.maxOutput = maxOutput;
        this.maxLayers = maxLayers;
        this.maxRatio = maxRatio;
        this.ratioGrace = ratioGrace;
        this.maxInput = maxInput;
    }

    /** Validate {@code limits} completely. */
    static EffectiveLimits from(Limits limits) throws SpillgateException {
        if (limits == null) {
            throw invalid("limits are required (maxOutput has no default and no unlimited value)");
        }
        long output = limits.rawMaxOutput();
        if (output < 1) {
            throw invalid("maxOutput must be in 1.." + Long.MAX_VALUE + ", got " + output);
        }
        int layers = DEFAULT_MAX_LAYERS;
        if (limits.rawMaxLayers().isPresent()) {
            layers = limits.rawMaxLayers().getAsInt();
            if (layers < 1 || layers > HARD_MAX_LAYERS) {
                throw invalid("maxLayers must be in 1.." + HARD_MAX_LAYERS + ", got " + layers);
            }
        }
        if (limits.rawDisableRatioLimit() && limits.rawMaxRatio().isPresent()) {
            throw invalid("maxRatio contradicts withoutRatioLimit; set only one");
        }
        long ratio = DEFAULT_MAX_RATIO;
        if (limits.rawDisableRatioLimit()) {
            ratio = 0;
        } else if (limits.rawMaxRatio().isPresent()) {
            ratio = limits.rawMaxRatio().getAsLong();
            if (ratio < 1 || ratio > HARD_MAX_RATIO) {
                throw invalid("maxRatio must be in 1.." + HARD_MAX_RATIO + ", got " + ratio);
            }
        }
        long grace = DEFAULT_RATIO_GRACE;
        if (limits.rawRatioGrace().isPresent()) {
            grace = limits.rawRatioGrace().getAsLong();
            if (grace < 0) {
                throw invalid("ratioGrace must not be negative, got " + grace);
            }
        }
        long input = 0;
        if (limits.rawMaxInput().isPresent()) {
            input = limits.rawMaxInput().getAsLong();
            if (input < 1) {
                throw invalid("maxInput must be in 1.." + Long.MAX_VALUE + ", got " + input);
            }
        }
        return new EffectiveLimits(output, layers, ratio, grace, input);
    }

    /** SG-R-RAT-01: {@code produced > maxRatio * max(consumed, 1)}, past the grace, without overflow. */
    boolean ratioExceeded(long produced, long consumed) {
        if (maxRatio == 0 || produced <= ratioGrace) {
            return false;
        }
        long base = Math.max(consumed, 1);
        // maxRatio < 2^31; if base > Long.MAX_VALUE / maxRatio the product exceeds every possible produced
        if (base > Long.MAX_VALUE / maxRatio) {
            return false;
        }
        return produced > maxRatio * base;
    }

    private static SpillgateException invalid(String message) {
        return new SpillgateException(Kind.INVALID_LIMITS, message);
    }
}
