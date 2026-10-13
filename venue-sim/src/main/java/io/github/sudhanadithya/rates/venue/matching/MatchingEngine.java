package io.github.sudhanadithya.rates.venue.matching;

import io.github.sudhanadithya.rates.common.clock.Clock;
import io.github.sudhanadithya.rates.schemas.md.Side;
import org.agrona.collections.Long2ObjectHashMap;

/**
 * Price time priority (FIFO) matching for every instrument on the venue (FR.SIM.02).
 *
 * <p>An incoming order trades against the best opposite price first, and within a price against
 * the order that arrived first. Each fill is at the resting order's price. What is left either
 * rests ({@link TimeInForce#DAY}) or is discarded ({@link TimeInForce#IOC}).
 *
 * <p>Single threaded by design: one thread owns the engine, its books and its pooled orders, so
 * nothing here needs a lock. Every command reads the injected clock once and stamps all of its
 * events with that time. Order and trade ids are sequential across the venue, which together with
 * no hash iteration makes the event stream deterministic for a given command sequence.
 *
 * <p>After startup nothing on the command path allocates: orders come from a fixed pool, the id
 * index is a primitive keyed Agrona map sized so it never resizes, and results are returned as
 * {@code long} status codes instead of objects or exceptions.
 */
public final class MatchingEngine {

    private final Clock clock;
    private final MatchingEventSink sink;
    private final OrderPool pool;
    private final Long2ObjectHashMap<Order> ordersById;
    private final OrderBook[] books;

    private long nextOrderId = 1;
    private long nextTradeId = 1;

    /**
     * Startup only: preallocates {@code orderCapacity} orders.
     *
     * @param orderCapacity      most orders that can rest at once across all books
     * @param instrumentCapacity one more than the highest instrument id that will get a book
     */
    public MatchingEngine(
        final Clock clock,
        final MatchingEventSink sink,
        final int orderCapacity,
        final int instrumentCapacity) {
        if (instrumentCapacity <= 0) {
            throw new IllegalArgumentException("instrument capacity must be positive: " + instrumentCapacity);
        }
        this.clock = clock;
        this.sink = sink;
        this.pool = new OrderPool(orderCapacity);
        // Twice the capacity at a 0.65 load factor keeps the resize threshold above orderCapacity,
        // so put never rehashes (and never allocates) on the hot path.
        this.ordersById = new Long2ObjectHashMap<>(orderCapacity * 2, 0.65f);
        this.books = new OrderBook[instrumentCapacity];
    }

    /** Startup only: creates the book for one instrument covering {@code levelCount} ticks from {@code minPriceTicks}. */
    public OrderBook addBook(final int instrumentId, final long minPriceTicks, final int levelCount) {
        if (instrumentId < 0 || instrumentId >= books.length) {
            throw new IllegalArgumentException("instrument id outside capacity: " + instrumentId);
        }
        if (books[instrumentId] != null) {
            throw new IllegalArgumentException("book already exists for instrument " + instrumentId);
        }
        final OrderBook book = new OrderBook(instrumentId, minPriceTicks, levelCount);
        books[instrumentId] = book;
        return book;
    }

    /** The instrument's book, or null if it has none. */
    public OrderBook book(final int instrumentId) {
        return instrumentId >= 0 && instrumentId < books.length ? books[instrumentId] : null;
    }

    /** The resting order with this id, or null. Read only access for snapshots and tests. */
    public Order order(final long orderId) {
        return ordersById.get(orderId);
    }

    /** Orders that can still rest before the venue rejects with {@link MatchStatus#BOOK_FULL}. */
    public int availableOrders() {
        return pool.available();
    }

    /**
     * Matches a new limit order and rests or discards what is left.
     *
     * <p>A DAY order's price must be inside the book's band. An IOC order's price is only a limit
     * on how far it may trade, so it may be outside the band; {@link #submitMarket} relies on that.
     *
     * @return the new order id (positive) or a negative {@link MatchStatus} reject
     */
    public long submit(
        final int instrumentId,
        final Side side,
        final long priceTicks,
        final long quantity,
        final TimeInForce timeInForce) {
        final OrderBook book = book(instrumentId);
        if (book == null) {
            return MatchStatus.UNKNOWN_INSTRUMENT;
        }
        if (side != Side.BUY && side != Side.SELL) {
            return MatchStatus.INVALID_SIDE;
        }
        if (quantity <= 0) {
            return MatchStatus.INVALID_QUANTITY;
        }
        final boolean mayRest = timeInForce == TimeInForce.DAY;
        if (mayRest && !book.inBand(priceTicks)) {
            return MatchStatus.PRICE_OUT_OF_BAND;
        }
        // Checked before matching, so a reject never follows trades that already went out.
        if (mayRest && pool.available() == 0) {
            return MatchStatus.BOOK_FULL;
        }

        final long now = clock.nanos();
        final long orderId = nextOrderId++;
        final long remaining = match(book, now, orderId, side, priceTicks, quantity);
        if (remaining > 0 && mayRest) {
            final Order order = pool.acquire();
            order.orderId = orderId;
            order.instrumentId = instrumentId;
            order.side = side;
            order.priceTicks = priceTicks;
            order.quantity = remaining;
            book.add(order);
            ordersById.put(orderId, order);
            sink.onOrderAdded(now, instrumentId, orderId, side, priceTicks, remaining);
        }
        return orderId;
    }

    /** An IOC order with no price limit: it sweeps the opposite side until filled or the side is empty. */
    public long submitMarket(final int instrumentId, final Side side, final long quantity) {
        final long limit = side == Side.BUY ? Long.MAX_VALUE : OrderBook.NULL_PRICE;
        return submit(instrumentId, side, limit, quantity, TimeInForce.IOC);
    }

    /** Removes a resting order. @return {@link MatchStatus#OK} or a negative reject */
    public long cancel(final int instrumentId, final long orderId) {
        final OrderBook book = book(instrumentId);
        if (book == null) {
            return MatchStatus.UNKNOWN_INSTRUMENT;
        }
        final Order order = ordersById.get(orderId);
        if (order == null || order.instrumentId != instrumentId) {
            return MatchStatus.UNKNOWN_ORDER;
        }
        final long now = clock.nanos();
        book.remove(order);
        sink.onOrderDeleted(now, instrumentId, orderId, order.side, order.priceTicks);
        ordersById.remove(orderId);
        pool.release(order);
        return MatchStatus.OK;
    }

    /**
     * Changes a resting order's price or open quantity, keeping its order id.
     *
     * <p>CME priority rules: lowering the quantity at the same price keeps the order's place in the
     * queue and is published as a CHANGE. Any price change or quantity increase loses priority: the
     * order is published as a DELETE, matched again as an aggressor at its new price, and any
     * remainder rests at the back of the new level as a NEW. Replacing with the same price and
     * quantity does nothing and publishes nothing.
     *
     * @param newQuantity the new open quantity
     * @return {@link MatchStatus#OK} or a negative reject
     */
    public long replace(final int instrumentId, final long orderId, final long newPriceTicks, final long newQuantity) {
        final OrderBook book = book(instrumentId);
        if (book == null) {
            return MatchStatus.UNKNOWN_INSTRUMENT;
        }
        final Order order = ordersById.get(orderId);
        if (order == null || order.instrumentId != instrumentId) {
            return MatchStatus.UNKNOWN_ORDER;
        }
        if (newQuantity <= 0) {
            return MatchStatus.INVALID_QUANTITY;
        }
        if (!book.inBand(newPriceTicks)) {
            return MatchStatus.PRICE_OUT_OF_BAND;
        }

        final long now = clock.nanos();
        final Side side = order.side;
        if (newPriceTicks == order.priceTicks && newQuantity <= order.quantity) {
            if (newQuantity < order.quantity) {
                book.reduce(order, newQuantity);
                sink.onOrderChanged(now, instrumentId, orderId, side, newPriceTicks, newQuantity);
            }
            return MatchStatus.OK;
        }

        book.remove(order);
        sink.onOrderDeleted(now, instrumentId, orderId, side, order.priceTicks);
        // The order is off the book while it matches, so it can never trade against itself.
        final long remaining = match(book, now, orderId, side, newPriceTicks, newQuantity);
        if (remaining > 0) {
            order.priceTicks = newPriceTicks;
            order.quantity = remaining;
            book.add(order);
            sink.onOrderAdded(now, instrumentId, orderId, side, newPriceTicks, remaining);
        } else {
            ordersById.remove(orderId);
            pool.release(order);
        }
        return MatchStatus.OK;
    }

    /**
     * Trades the aggressor against the opposite side while prices cross.
     *
     * @return the aggressor's unfilled quantity
     */
    private long match(
        final OrderBook book,
        final long now,
        final long aggressorOrderId,
        final Side aggressorSide,
        final long limitPriceTicks,
        final long quantity) {
        final int instrumentId = book.instrumentId();
        final Side restingSide = aggressorSide == Side.BUY ? Side.SELL : Side.BUY;
        long remaining = quantity;
        while (remaining > 0) {
            final Order resting = book.bestOrder(restingSide);
            if (resting == null || !crosses(aggressorSide, limitPriceTicks, resting.priceTicks)) {
                break;
            }
            final long restingOrderId = resting.orderId;
            final long priceTicks = resting.priceTicks;
            final long fill = Math.min(remaining, resting.quantity);
            remaining -= fill;
            sink.onTrade(now, instrumentId, nextTradeId++, priceTicks, fill, aggressorSide, aggressorOrderId, restingOrderId);

            if (fill == resting.quantity) {
                book.remove(resting);
                ordersById.remove(restingOrderId);
                pool.release(resting);
                sink.onOrderDeleted(now, instrumentId, restingOrderId, restingSide, priceTicks);
            } else {
                final long left = resting.quantity - fill;
                book.reduce(resting, left);
                sink.onOrderChanged(now, instrumentId, restingOrderId, restingSide, priceTicks, left);
            }
        }
        return remaining;
    }

    private static boolean crosses(final Side aggressorSide, final long limitPriceTicks, final long restingPriceTicks) {
        return aggressorSide == Side.BUY ? restingPriceTicks <= limitPriceTicks : restingPriceTicks >= limitPriceTicks;
    }
}
