package io.github.sudhanadithya.rates.venue.matching;

import io.github.sudhanadithya.rates.schemas.md.Side;

/**
 * The resting orders of one instrument: two arrays of {@link PriceLevel}, one per side, indexed by
 * {@code priceTicks - minPriceTicks}. Finding a level is one subtraction and one array load, with
 * no tree or hash lookup.
 *
 * <p>The book covers a fixed price band set at startup. The matching engine rejects resting orders
 * outside it, which is also a crude price collar.
 *
 * <p>This class only keeps the data structure consistent. The matching policy (FIFO now, pro rata
 * later per FR.SIM.02) lives in {@link MatchingEngine}. Owned by the matching thread only.
 */
public final class OrderBook {

    /** Price reported for an empty side; the same null value the TopOfBook message uses. */
    public static final long NULL_PRICE = Long.MIN_VALUE;

    private final int instrumentId;
    private final long minPriceTicks;
    private final long maxPriceTicks;
    private final PriceLevel[] bids;
    private final PriceLevel[] asks;

    /** Index of the best bid level, or -1 when there are no bids. */
    private int bestBidIndex = -1;
    /** Index of the best ask level, or {@code levelCount} when there are no asks. */
    private int bestAskIndex;
    private int orderCount;

    /** Startup only: allocates every level in the band. */
    OrderBook(final int instrumentId, final long minPriceTicks, final int levelCount) {
        if (levelCount <= 0) {
            throw new IllegalArgumentException("level count must be positive: " + levelCount);
        }
        if (minPriceTicks <= NULL_PRICE || minPriceTicks > Long.MAX_VALUE - levelCount) {
            throw new IllegalArgumentException("price band out of range: " + minPriceTicks);
        }
        this.instrumentId = instrumentId;
        this.minPriceTicks = minPriceTicks;
        this.maxPriceTicks = minPriceTicks + levelCount - 1;
        this.bids = new PriceLevel[levelCount];
        this.asks = new PriceLevel[levelCount];
        for (int i = 0; i < levelCount; i++) {
            bids[i] = new PriceLevel(minPriceTicks + i);
            asks[i] = new PriceLevel(minPriceTicks + i);
        }
        this.bestAskIndex = levelCount;
    }

    public int instrumentId() {
        return instrumentId;
    }

    public long minPriceTicks() {
        return minPriceTicks;
    }

    public long maxPriceTicks() {
        return maxPriceTicks;
    }

    /** Number of orders resting on both sides. */
    public int orderCount() {
        return orderCount;
    }

    public boolean inBand(final long priceTicks) {
        return priceTicks >= minPriceTicks && priceTicks <= maxPriceTicks;
    }

    public long bestBidPrice() {
        return bestBidIndex < 0 ? NULL_PRICE : bids[bestBidIndex].priceTicks;
    }

    public long bestAskPrice() {
        return bestAskIndex >= asks.length ? NULL_PRICE : asks[bestAskIndex].priceTicks;
    }

    /** Total open quantity at a price, or 0 for an empty or out of band price. */
    public long quantityAt(final Side side, final long priceTicks) {
        return inBand(priceTicks) ? levels(side)[index(priceTicks)].totalQuantity : 0;
    }

    /** Number of orders at a price, or 0 for an empty or out of band price. */
    public int orderCountAt(final Side side, final long priceTicks) {
        return inBand(priceTicks) ? levels(side)[index(priceTicks)].orderCount : 0;
    }

    /**
     * Walks every resting order: bids best price first, then asks best price first, and within a
     * level in queue order. This is the order a snapshot lists them in. It scans the band from the
     * best price outward, so it is meant for snapshots and checks, not for every message.
     */
    public void visitOrders(final OrderVisitor visitor) {
        for (int i = bestBidIndex; i >= 0; i--) {
            for (Order order = bids[i].head; order != null; order = order.next) {
                visitor.visit(order.orderId, order.side, order.priceTicks, order.quantity);
            }
        }
        for (int i = bestAskIndex; i < asks.length; i++) {
            for (Order order = asks[i].head; order != null; order = order.next) {
                visitor.visit(order.orderId, order.side, order.priceTicks, order.quantity);
            }
        }
    }

    /** The order with time priority at the best price on {@code side}, or null if that side is empty. */
    Order bestOrder(final Side side) {
        if (side == Side.BUY) {
            return bestBidIndex < 0 ? null : bids[bestBidIndex].head;
        }
        return bestAskIndex >= asks.length ? null : asks[bestAskIndex].head;
    }

    /** Rests the order at the back of its level. The caller has checked the price is in band. */
    void add(final Order order) {
        final int index = index(order.priceTicks);
        if (order.side == Side.BUY) {
            bids[index].append(order);
            if (index > bestBidIndex) {
                bestBidIndex = index;
            }
        } else {
            asks[index].append(order);
            if (index < bestAskIndex) {
                bestAskIndex = index;
            }
        }
        orderCount++;
    }

    /**
     * Takes the order off the book. When that empties the best level, the scan for the next best
     * level is linear in the number of empty ticks skipped, which is small for a liquid book.
     */
    void remove(final Order order) {
        final int index = index(order.priceTicks);
        if (order.side == Side.BUY) {
            final PriceLevel level = bids[index];
            level.remove(order);
            if (index == bestBidIndex && level.isEmpty()) {
                int i = index - 1;
                while (i >= 0 && bids[i].isEmpty()) {
                    i--;
                }
                bestBidIndex = i;
            }
        } else {
            final PriceLevel level = asks[index];
            level.remove(order);
            if (index == bestAskIndex && level.isEmpty()) {
                int i = index + 1;
                while (i < asks.length && asks[i].isEmpty()) {
                    i++;
                }
                bestAskIndex = i;
            }
        }
        orderCount--;
    }

    /** Lowers a resting order's open quantity in place, keeping its queue position. */
    void reduce(final Order order, final long newQuantity) {
        levels(order.side)[index(order.priceTicks)].reduce(order, newQuantity);
    }

    private PriceLevel[] levels(final Side side) {
        return side == Side.BUY ? bids : asks;
    }

    private int index(final long priceTicks) {
        return (int) (priceTicks - minPriceTicks);
    }
}
