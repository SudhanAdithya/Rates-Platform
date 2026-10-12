package io.github.sudhanadithya.rates.common.clock;

import java.time.Instant;

/**
 * Wall clock time for live processes. This is the only class allowed to read system time;
 * {@code checkBannedTimeCalls} enforces that.
 *
 * <p>Epoch time is read once at construction, then advanced with {@link System#nanoTime()}.
 * That makes {@link #nanos()} monotonic and cheap (no syscall on Linux or macOS), at the cost
 * of not following NTP corrections made after startup, which is the right trade for latency
 * measurement within one session.
 */
public final class SystemClock implements Clock {

    private final long epochAnchorNanos;
    private final long monotonicAnchorNanos;

    public SystemClock() {
        final Instant now = Instant.now();
        this.monotonicAnchorNanos = System.nanoTime();
        this.epochAnchorNanos = now.getEpochSecond() * 1_000_000_000L + now.getNano();
    }

    @Override
    public long nanos() {
        return epochAnchorNanos + (System.nanoTime() - monotonicAnchorNanos);
    }
}
