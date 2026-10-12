package io.github.sudhanadithya.rates.common.clock;

/**
 * Virtual time for replay, backtests and tests. Time only moves when the owner calls
 * {@link #set} or {@link #advance}.
 *
 * <p>Not thread safe by design: one thread (the simulation thread) owns and drives it,
 * following the single writer rule.
 */
public final class ManualClock implements Clock {

    private long nanos;

    public ManualClock(final long startNanos) {
        this.nanos = startNanos;
    }

    @Override
    public long nanos() {
        return nanos;
    }

    /** Moves time to {@code newNanos}. Time never goes backwards. */
    public void set(final long newNanos) {
        if (newNanos < nanos) {
            throw new IllegalArgumentException("time went backwards: " + newNanos + " < " + nanos);
        }
        nanos = newNanos;
    }

    /** Moves time forward by {@code deltaNanos}. */
    public void advance(final long deltaNanos) {
        if (deltaNanos < 0) {
            throw new IllegalArgumentException("negative advance: " + deltaNanos);
        }
        nanos += deltaNanos;
    }
}
