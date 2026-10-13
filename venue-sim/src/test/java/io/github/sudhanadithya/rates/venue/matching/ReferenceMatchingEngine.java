package io.github.sudhanadithya.rates.venue.matching;

import io.github.sudhanadithya.rates.schemas.md.Side;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A deliberately simple FIFO matcher used only to check {@link MatchingEngine}. It uses sorted
 * maps of plain lists and allocates freely, so it is easy to read and hard to get wrong. Both
 * engines must publish the same events for the same commands.
 */
final class ReferenceMatchingEngine {

    private static final class RefOrder {
        final long id;
        final int instrumentId;
        final Side side;
        long price;
        long quantity;

        RefOrder(final long id, final int instrumentId, final Side side, final long price, final long quantity) {
            this.id = id;
            this.instrumentId = instrumentId;
            this.side = side;
            this.price = price;
            this.quantity = quantity;
        }
    }

    private static final class RefBook {
        final long min;
        final long max;
        final TreeMap<Long, List<RefOrder>> bids = new TreeMap<>(Collections.reverseOrder());
        final TreeMap<Long, List<RefOrder>> asks = new TreeMap<>();

        RefBook(final long min, final int levels) {
            this.min = min;
            this.max = min + levels - 1;
        }

        TreeMap<Long, List<RefOrder>> side(final Side side) {
            return side == Side.BUY ? bids : asks;
        }
    }

    private final long now;
    private final MatchingEventSink sink;
    private final int capacity;
    private final Map<Integer, RefBook> books = new HashMap<>();
    private final Map<Long, RefOrder> orders = new HashMap<>();
    private long nextOrderId = 1;
    private long nextTradeId = 1;

    ReferenceMatchingEngine(final long now, final MatchingEventSink sink, final int capacity) {
        this.now = now;
        this.sink = sink;
        this.capacity = capacity;
    }

    void addBook(final int instrumentId, final long min, final int levels) {
        books.put(instrumentId, new RefBook(min, levels));
    }

    long submit(final int instrumentId, final Side side, final long price, final long quantity, final TimeInForce tif) {
        final RefBook book = books.get(instrumentId);
        if (book == null) {
            return MatchStatus.UNKNOWN_INSTRUMENT;
        }
        if (side != Side.BUY && side != Side.SELL) {
            return MatchStatus.INVALID_SIDE;
        }
        if (quantity <= 0) {
            return MatchStatus.INVALID_QUANTITY;
        }
        if (tif == TimeInForce.DAY && (price < book.min || price > book.max)) {
            return MatchStatus.PRICE_OUT_OF_BAND;
        }
        if (tif == TimeInForce.DAY && orders.size() == capacity) {
            return MatchStatus.BOOK_FULL;
        }
        final long id = nextOrderId++;
        final long left = match(instrumentId, book, id, side, price, quantity);
        if (left > 0 && tif == TimeInForce.DAY) {
            rest(book, new RefOrder(id, instrumentId, side, price, left));
        }
        return id;
    }

    long cancel(final int instrumentId, final long orderId) {
        final RefBook book = books.get(instrumentId);
        if (book == null) {
            return MatchStatus.UNKNOWN_INSTRUMENT;
        }
        final RefOrder order = orders.get(orderId);
        if (order == null || order.instrumentId != instrumentId) {
            return MatchStatus.UNKNOWN_ORDER;
        }
        unlink(book, order);
        sink.onOrderDeleted(now, instrumentId, orderId, order.side, order.price);
        return MatchStatus.OK;
    }

    long replace(final int instrumentId, final long orderId, final long price, final long quantity) {
        final RefBook book = books.get(instrumentId);
        if (book == null) {
            return MatchStatus.UNKNOWN_INSTRUMENT;
        }
        final RefOrder order = orders.get(orderId);
        if (order == null || order.instrumentId != instrumentId) {
            return MatchStatus.UNKNOWN_ORDER;
        }
        if (quantity <= 0) {
            return MatchStatus.INVALID_QUANTITY;
        }
        if (price < book.min || price > book.max) {
            return MatchStatus.PRICE_OUT_OF_BAND;
        }
        if (price == order.price && quantity <= order.quantity) {
            if (quantity < order.quantity) {
                order.quantity = quantity;
                sink.onOrderChanged(now, instrumentId, orderId, order.side, price, quantity);
            }
            return MatchStatus.OK;
        }
        unlink(book, order);
        sink.onOrderDeleted(now, instrumentId, orderId, order.side, order.price);
        final long left = match(instrumentId, book, orderId, order.side, price, quantity);
        if (left > 0) {
            order.price = price;
            order.quantity = left;
            rest(book, order);
        }
        return MatchStatus.OK;
    }

    /** Resting orders in snapshot order, formatted like the test's visitor. */
    List<String> snapshot(final int instrumentId) {
        final RefBook book = books.get(instrumentId);
        final List<String> lines = new ArrayList<>();
        for (final List<RefOrder> level : book.bids.values()) {
            for (final RefOrder o : level) {
                lines.add(o.id + " " + o.side + " " + o.price + "x" + o.quantity);
            }
        }
        for (final List<RefOrder> level : book.asks.values()) {
            for (final RefOrder o : level) {
                lines.add(o.id + " " + o.side + " " + o.price + "x" + o.quantity);
            }
        }
        return lines;
    }

    private long match(
        final int instrumentId, final RefBook book, final long aggressorId, final Side side, final long limit,
        final long quantity) {
        final Side restingSide = side == Side.BUY ? Side.SELL : Side.BUY;
        final TreeMap<Long, List<RefOrder>> opposite = book.side(restingSide);
        long left = quantity;
        while (left > 0 && !opposite.isEmpty()) {
            final long best = opposite.firstKey();
            final boolean crosses = side == Side.BUY ? best <= limit : best >= limit;
            if (!crosses) {
                break;
            }
            final RefOrder resting = opposite.get(best).get(0);
            final long fill = Math.min(left, resting.quantity);
            left -= fill;
            sink.onTrade(now, instrumentId, nextTradeId++, best, fill, side, aggressorId, resting.id);
            if (fill == resting.quantity) {
                unlink(book, resting);
                sink.onOrderDeleted(now, instrumentId, resting.id, restingSide, best);
            } else {
                resting.quantity -= fill;
                sink.onOrderChanged(now, instrumentId, resting.id, restingSide, best, resting.quantity);
            }
        }
        return left;
    }

    private void rest(final RefBook book, final RefOrder order) {
        book.side(order.side).computeIfAbsent(order.price, p -> new ArrayList<>()).add(order);
        orders.put(order.id, order);
        sink.onOrderAdded(now, order.instrumentId, order.id, order.side, order.price, order.quantity);
    }

    private void unlink(final RefBook book, final RefOrder order) {
        final TreeMap<Long, List<RefOrder>> side = book.side(order.side);
        final List<RefOrder> level = side.get(order.price);
        level.remove(order);
        if (level.isEmpty()) {
            side.remove(order.price);
        }
        orders.remove(order.id);
    }
}
