package io.github.sudhanadithya.rates.venue.flow;

/**
 * Shape of the synthetic order flow. Percentages pick the command type for each event; whatever
 * is left after adds, cancels and replaces is marketable orders.
 *
 * @param eventsPerSecond mean rate of the Poisson process, across all instruments
 * @param addPercent      passive limit orders near the mid
 * @param cancelPercent   cancels of a random resting order
 * @param replacePercent  replaces of a random resting order (reduce, or move one tick away)
 * @param maxQuantity     order sizes are uniform from 1 to this
 * @param maxDepthTicks   passive orders rest up to this many ticks behind the touch
 * @param midMovePercent  chance per event that the reference mid steps one tick up or down
 */
public record FlowConfig(
    long eventsPerSecond,
    int addPercent,
    int cancelPercent,
    int replacePercent,
    int maxQuantity,
    int maxDepthTicks,
    int midMovePercent) {

    /** A mix loosely like a quiet Treasury futures book: mostly adds and cancels, some trades. */
    public static FlowConfig defaults() {
        return new FlowConfig(20_000, 50, 30, 10, 10, 10, 2);
    }

    public FlowConfig {
        if (eventsPerSecond <= 0 || eventsPerSecond > 1_000_000_000L) {
            throw new IllegalArgumentException("events per second must be 1 to 1e9: " + eventsPerSecond);
        }
        if (addPercent < 0 || cancelPercent < 0 || replacePercent < 0
            || addPercent + cancelPercent + replacePercent > 100) {
            throw new IllegalArgumentException("add, cancel and replace percents must be 0 or more and sum to 100 or less");
        }
        if (maxQuantity <= 0 || maxDepthTicks < 0) {
            throw new IllegalArgumentException("max quantity must be positive and max depth 0 or more");
        }
        if (midMovePercent < 0 || midMovePercent > 100) {
            throw new IllegalArgumentException("mid move percent must be 0 to 100: " + midMovePercent);
        }
    }
}
