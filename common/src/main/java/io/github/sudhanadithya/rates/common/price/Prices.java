package io.github.sudhanadithya.rates.common.price;

/**
 * Hot path price arithmetic. A price is a {@code long} count of the instrument's ticks
 * (CLAUDE.md rule 3, FR.MD.06); these helpers move between ticks and 256ths of a point.
 *
 * <p>Every method is allocation free, branch light and never throws, so it is safe per message.
 * Validation belongs at the edges, see {@link TreasuryPriceFormat}.
 */
public final class Prices {

    private Prices() {
    }

    /** Converts a price in ticks to 256ths of a point. */
    public static long ticksTo256ths(final long priceTicks, final int tickSize256ths) {
        return priceTicks * tickSize256ths;
    }

    /**
     * Converts a price in 256ths of a point to ticks, rounding toward zero.
     * Check {@link #isOnTick} first when the input may be off tick.
     */
    public static long ticksFrom256ths(final long price256ths, final int tickSize256ths) {
        return price256ths / tickSize256ths;
    }

    /** True when a price in 256ths is a whole number of ticks. */
    public static boolean isOnTick(final long price256ths, final int tickSize256ths) {
        return price256ths % tickSize256ths == 0;
    }
}
