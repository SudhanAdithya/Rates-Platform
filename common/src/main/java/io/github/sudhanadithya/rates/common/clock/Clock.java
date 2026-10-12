package io.github.sudhanadithya.rates.common.clock;

/**
 * The only source of time for platform code (CLAUDE.md rule 4, FR.STR.03).
 *
 * <p>Live processes inject {@link SystemClock}; replay, backtests and tests inject
 * {@link ManualClock}, so the same code produces the same output for the same input.
 */
public interface Clock {

    /** Current time in nanoseconds since the Unix epoch. */
    long nanos();
}
