package io.github.felixmiddelhoff.spillgate;

/**
 * Cooperative cancellation (SG-R-API-04). Create one, hand it to
 * {@link Decoder#reader(java.io.InputStream, CancelToken, String...)} and call {@link #cancel()} from
 * anywhere, for example from another thread.
 *
 * <p>A canceled stream fails with a {@link SpillgateException} of kind {@link Kind#CANCELED}, sticky.
 * It never throws {@link java.io.InterruptedIOException}: some callers retry that, and a canceled
 * stream must not look retryable. The flag is looked at on every {@code read} call and every time a
 * layer fetches more input from its source (at least once per 4 KiB of compressed input). It is not
 * a hard CPU bound: a single codec step of at most 64 KiB of output still runs to its end.
 * {@link Thread#interrupt()} is not cancellation.
 */
public final class CancelToken {
    private volatile boolean canceled;

    /** A token that is not canceled. */
    public CancelToken() {}

    /** Cancel every stream that holds this token. Idempotent and safe to call from any thread. */
    public void cancel() {
        canceled = true;
    }

    /** Whether {@link #cancel()} was called. */
    public boolean isCanceled() {
        return canceled;
    }
}
