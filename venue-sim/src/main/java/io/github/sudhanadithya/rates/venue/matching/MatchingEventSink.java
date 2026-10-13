package io.github.sudhanadithya.rates.venue.matching;

import io.github.sudhanadithya.rates.schemas.md.Side;

/**
 * Market by order events out of the {@link MatchingEngine}, in the order the venue publishes them.
 *
 * <p>One inbound command can produce several events, and they all carry the same
 * {@code transactTimeNanos}. When an aggressor fills a resting order the engine emits the trade
 * first, then the resting order's {@code onOrderChanged} (partial fill) or {@code onOrderDeleted}
 * (full fill). An unfilled remainder that rests is emitted last as {@code onOrderAdded}.
 *
 * <p>A feed handler that applies only the added, changed and deleted events rebuilds the venue's
 * book exactly. Trades are informational.
 */
public interface MatchingEventSink {

    /** A new order now rests at the back of its price level's queue (MBO NEW). */
    void onOrderAdded(long transactTimeNanos, int instrumentId, long orderId, Side side, long priceTicks, long quantity);

    /**
     * A resting order's open quantity went down and it kept its queue position (MBO CHANGE).
     * {@code quantity} is the new open quantity. A change that loses priority is sent as a delete
     * then an add instead, so CHANGE never moves an order in the queue.
     */
    void onOrderChanged(long transactTimeNanos, int instrumentId, long orderId, Side side, long priceTicks, long quantity);

    /** A resting order left the book: filled, cancelled, or replaced with lost priority (MBO DELETE). */
    void onOrderDeleted(long transactTimeNanos, int instrumentId, long orderId, Side side, long priceTicks);

    /** An aggressor matched a resting order. The price is always the resting order's price. */
    void onTrade(
        long transactTimeNanos,
        int instrumentId,
        long tradeId,
        long priceTicks,
        long quantity,
        Side aggressorSide,
        long aggressorOrderId,
        long restingOrderId);
}
