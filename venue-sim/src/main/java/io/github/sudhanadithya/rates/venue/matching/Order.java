package io.github.sudhanadithya.rates.venue.matching;

import io.github.sudhanadithya.rates.schemas.md.Side;

/**
 * One resting order.
 *
 * <p>Pooled: every instance is created at startup by {@link OrderPool} and reused, so resting an
 * order never allocates. Intrusive: {@code prev} and {@code next} link the order straight into its
 * price level's FIFO queue, so there is no separate list node to allocate either.
 *
 * <p>Owned by the matching thread only. Fields are package private and mutated only by this
 * package; other code reads them through the getters.
 */
public final class Order {

    long orderId;
    int instrumentId;
    Side side;
    long priceTicks;
    /** Open quantity still resting on the book. */
    long quantity;

    Order prev;
    Order next;

    Order() {
    }

    public long orderId() {
        return orderId;
    }

    public int instrumentId() {
        return instrumentId;
    }

    public Side side() {
        return side;
    }

    public long priceTicks() {
        return priceTicks;
    }

    public long quantity() {
        return quantity;
    }

    /** Clears every field so a stale reference can't leak old state after the order is reused. */
    void reset() {
        orderId = 0;
        instrumentId = 0;
        side = null;
        priceTicks = 0;
        quantity = 0;
        prev = null;
        next = null;
    }
}
