package io.github.sudhanadithya.rates.venue.flow;

import io.github.sudhanadithya.rates.schemas.md.Side;
import io.github.sudhanadithya.rates.venue.matching.MatchStatus;
import io.github.sudhanadithya.rates.venue.matching.MatchingEngine;
import io.github.sudhanadithya.rates.venue.matching.Order;
import io.github.sudhanadithya.rates.venue.matching.OrderBook;
import io.github.sudhanadithya.rates.venue.matching.TimeInForce;
import java.util.List;
import java.util.SplittableRandom;

/**
 * Seeded synthetic order flow that drives the matching engine in live mode.
 *
 * <p>Events arrive as a Poisson process: gaps between events are exponential with mean
 * {@code 1 / eventsPerSecond}, scheduled on the injected clock. Each event picks an instrument
 * uniformly, may step that instrument's reference mid by one tick, then sends one command: a
 * passive limit order near the mid, a cancel, a replace, or a marketable order.
 *
 * <p>Everything comes from one {@link SplittableRandom}, which is seeded, has no atomics and never
 * allocates. The same seed and the same clock readings give the same commands, which is what
 * makes a whole venue session reproducible. The only {@code double} is the exponential sample for
 * the time gap; prices and quantities stay integer.
 */
public final class SyntheticFlow {

    /** Caps one call so a long clock jump catches up over several duty cycles, not one huge one. */
    public static final int MAX_EVENTS_PER_CALL = 1_000;

    private static final double NANOS_PER_SECOND = 1_000_000_000.0;

    private final MatchingEngine engine;
    private final FlowConfig config;
    private final SplittableRandom random;
    private final double meanGapNanos;

    private final int[] instrumentIds;
    private final long[] mids;
    private final long[] minMids;
    private final long[] maxMids;
    private final LiveOrders[] live;

    private long nextEventNanos;
    private long events;
    private long rejects;

    /** Startup only. The engine must already have a book for each config. */
    public SyntheticFlow(
        final MatchingEngine engine,
        final FlowConfig config,
        final List<BookConfig> books,
        final SplittableRandom random,
        final long startNanos) {
        if (books.isEmpty()) {
            throw new IllegalArgumentException("at least one book is required");
        }
        this.engine = engine;
        this.config = config;
        this.random = random;
        this.meanGapNanos = NANOS_PER_SECOND / config.eventsPerSecond();

        final int n = books.size();
        instrumentIds = new int[n];
        mids = new long[n];
        minMids = new long[n];
        maxMids = new long[n];
        live = new LiveOrders[n];
        final int capacity = engine.availableOrders();
        for (int i = 0; i < n; i++) {
            final BookConfig book = books.get(i);
            final OrderBook orderBook = engine.book(book.instrumentId());
            if (orderBook == null) {
                throw new IllegalArgumentException("engine has no book for instrument " + book.instrumentId());
            }
            instrumentIds[i] = book.instrumentId();
            mids[i] = book.initialMidTicks();
            // Keep the mid far enough inside the band that every passive price is in band.
            minMids[i] = orderBook.minPriceTicks() + config.maxDepthTicks() + 1;
            maxMids[i] = orderBook.maxPriceTicks() - config.maxDepthTicks() - 2;
            if (minMids[i] > maxMids[i]) {
                throw new IllegalArgumentException("price band too narrow for max depth on instrument " + book.instrumentId());
            }
            live[i] = new LiveOrders(capacity);
        }
        this.nextEventNanos = startNanos + nextGapNanos();
    }

    /** Events generated so far. */
    public long events() {
        return events;
    }

    /** Commands the engine rejected (for example a full book). Expected to stay at or near 0. */
    public long rejects() {
        return rejects;
    }

    /** The reference mid for book {@code index} (the order of the book configs). */
    public long mid(final int index) {
        return mids[index];
    }

    /** Runs every event scheduled at or before {@code nowNanos}, up to a cap. @return events run */
    public int generate(final long nowNanos) {
        int count = 0;
        while (nextEventNanos <= nowNanos && count < MAX_EVENTS_PER_CALL) {
            event();
            nextEventNanos += nextGapNanos();
            count++;
        }
        events += count;
        return count;
    }

    private void event() {
        final int book = random.nextInt(instrumentIds.length);
        final int instrumentId = instrumentIds[book];
        if (random.nextInt(100) < config.midMovePercent()) {
            final long moved = mids[book] + (random.nextBoolean() ? 1 : -1);
            mids[book] = Math.max(minMids[book], Math.min(maxMids[book], moved));
        }

        final int roll = random.nextInt(100);
        if (roll < config.addPercent()) {
            addPassive(book, instrumentId);
        } else if (roll < config.addPercent() + config.cancelPercent()) {
            cancelRandom(book, instrumentId);
        } else if (roll < config.addPercent() + config.cancelPercent() + config.replacePercent()) {
            replaceRandom(book, instrumentId);
        } else {
            final Side side = random.nextBoolean() ? Side.BUY : Side.SELL;
            count(engine.submitMarket(instrumentId, side, quantity()));
        }
    }

    /** A limit order at the touch or behind it; depth is skewed toward the touch. */
    private void addPassive(final int book, final int instrumentId) {
        final Side side = random.nextBoolean() ? Side.BUY : Side.SELL;
        final int maxDepth = config.maxDepthTicks();
        final int depth = Math.min(random.nextInt(maxDepth + 1), random.nextInt(maxDepth + 1));
        // The reference bid is the mid tick and the reference ask is one tick above it.
        final long price = side == Side.BUY ? mids[book] - depth : mids[book] + 1 + depth;
        final long orderId = engine.submit(instrumentId, side, price, quantity(), TimeInForce.DAY);
        if (count(orderId) && engine.order(orderId) != null) {
            live[book].add(orderId, engine);
        }
    }

    private void cancelRandom(final int book, final int instrumentId) {
        final LiveOrders orders = live[book];
        final int index = orders.pickLive(random, engine);
        if (index >= 0) {
            count(engine.cancel(instrumentId, orders.idAt(index)));
            orders.removeAt(index);
        }
    }

    /** Reduces a random resting order by one lot, or if it has one lot left, moves it a tick away. */
    private void replaceRandom(final int book, final int instrumentId) {
        final LiveOrders orders = live[book];
        final int index = orders.pickLive(random, engine);
        if (index < 0) {
            return;
        }
        final Order order = engine.order(orders.idAt(index));
        final long orderId = order.orderId();
        final long quantity = order.quantity();
        final long price = order.priceTicks();
        if (quantity > 1) {
            count(engine.replace(instrumentId, orderId, price, quantity - 1));
            return;
        }
        final long away = order.side() == Side.BUY ? price - 1 : price + 1;
        if (engine.book(instrumentId).inBand(away)) {
            count(engine.replace(instrumentId, orderId, away, quantity));
        }
    }

    private long quantity() {
        return 1 + random.nextInt(config.maxQuantity());
    }

    /** Counts rejects. @return true if the command was accepted */
    private boolean count(final long status) {
        if (MatchStatus.isReject(status)) {
            rejects++;
            return false;
        }
        return true;
    }

    private long nextGapNanos() {
        // Inverse transform sampling: -ln(U) is exponential with mean 1 for U uniform in (0, 1].
        final double uniform = 1.0 - random.nextDouble();
        return Math.max(1L, (long) (-Math.log(uniform) * meanGapNanos));
    }
}
