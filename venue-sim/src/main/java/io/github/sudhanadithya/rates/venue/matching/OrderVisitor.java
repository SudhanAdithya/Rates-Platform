package io.github.sudhanadithya.rates.venue.matching;

import io.github.sudhanadithya.rates.schemas.md.Side;

/**
 * Receives resting orders from {@link OrderBook#visitOrders}. Pass a long lived instance (a field,
 * not a capturing lambda) so walking the book doesn't allocate.
 */
@FunctionalInterface
public interface OrderVisitor {

    void visit(long orderId, Side side, long priceTicks, long quantity);
}
