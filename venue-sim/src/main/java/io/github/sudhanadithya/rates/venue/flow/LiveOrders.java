package io.github.sudhanadithya.rates.venue.flow;

import io.github.sudhanadithya.rates.venue.matching.MatchingEngine;
import java.util.SplittableRandom;

/**
 * Ids of orders the flow has rested on one book, so it can pick one at random to cancel or replace.
 *
 * <p>Orders also leave the book by being filled, which the flow doesn't hear about. Instead of
 * tracking fills, a pick checks the engine and drops ids that are no longer resting (swap with the
 * last element, constant time). The array holds at most as many ids as the engine has orders.
 */
final class LiveOrders {

    private static final int MAX_TRIES = 8;

    private final long[] ids;
    private int count;

    /** Startup only. */
    LiveOrders(final int capacity) {
        ids = new long[capacity];
    }

    int count() {
        return count;
    }

    /** Remembers a newly rested order. Compacts out filled orders first if the array is full. */
    void add(final long orderId, final MatchingEngine engine) {
        if (count == ids.length) {
            compact(engine);
            if (count == ids.length) {
                return;
            }
        }
        ids[count++] = orderId;
    }

    /** Index of a random order that is still resting, or -1 if none was found in a few tries. */
    int pickLive(final SplittableRandom random, final MatchingEngine engine) {
        for (int tries = 0; tries < MAX_TRIES && count > 0; tries++) {
            final int index = random.nextInt(count);
            if (engine.order(ids[index]) != null) {
                return index;
            }
            removeAt(index);
        }
        return -1;
    }

    long idAt(final int index) {
        return ids[index];
    }

    void removeAt(final int index) {
        ids[index] = ids[--count];
    }

    /** Drops every id that is no longer resting. Linear, and only runs when the array is full. */
    private void compact(final MatchingEngine engine) {
        int kept = 0;
        for (int i = 0; i < count; i++) {
            if (engine.order(ids[i]) != null) {
                ids[kept++] = ids[i];
            }
        }
        count = kept;
    }
}
