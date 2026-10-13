package io.github.sudhanadithya.rates.venue.flow;

/**
 * One instrument's book on the venue.
 *
 * @param instrumentId    internal instrument id from the registry
 * @param initialMidTicks where the synthetic mid starts, in instrument ticks
 * @param levelCount      ticks in the book's price band, centred on the initial mid
 */
public record BookConfig(int instrumentId, long initialMidTicks, int levelCount) {

    public BookConfig {
        if (instrumentId < 0 || levelCount <= 0) {
            throw new IllegalArgumentException("instrument id must be 0 or more and level count positive");
        }
        if (initialMidTicks - levelCount / 2 < 0) {
            throw new IllegalArgumentException("price band would go below zero ticks: " + initialMidTicks);
        }
    }

    /** Lowest price in the band. */
    public long minPriceTicks() {
        return initialMidTicks - levelCount / 2;
    }
}
