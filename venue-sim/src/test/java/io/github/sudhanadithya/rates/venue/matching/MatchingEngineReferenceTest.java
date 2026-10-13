package io.github.sudhanadithya.rates.venue.matching;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sudhanadithya.rates.common.clock.ManualClock;
import io.github.sudhanadithya.rates.schemas.md.Side;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Drives the engine and {@link ReferenceMatchingEngine} with the same long seeded command stream
 * and checks they agree on every return code, every event and the full book after every command.
 * The stream mixes passive and crossing limits, IOC and market orders, cancels, replaces, out of
 * band prices, unknown ids and a small pool, so every branch gets exercised many times.
 */
class MatchingEngineReferenceTest {

    private static final long NOW = 42L;
    private static final long MIN = 1_000;
    private static final int LEVELS = 60;
    private static final int CAPACITY = 150;
    private static final int COMMANDS = 200_000;

    @Test
    @DisplayName("FR.SIM.02 FIFO engine matches a reference matcher over 200k seeded commands")
    void matchesReference() {
        final RecordingSink engineSink = new RecordingSink();
        final RecordingSink referenceSink = new RecordingSink();
        final MatchingEngine engine = new MatchingEngine(new ManualClock(NOW), engineSink, CAPACITY, 2);
        final ReferenceMatchingEngine reference = new ReferenceMatchingEngine(NOW, referenceSink, CAPACITY);
        for (int instrument = 0; instrument < 2; instrument++) {
            engine.addBook(instrument, MIN, LEVELS);
            reference.addBook(instrument, MIN, LEVELS);
        }

        final Random random = new Random(20261012L);
        final List<Long> recentIds = new ArrayList<>();
        int trades = 0;
        int bookFull = 0;
        for (int n = 0; n < COMMANDS; n++) {
            final int instrument = random.nextInt(2);
            final Side side = random.nextBoolean() ? Side.BUY : Side.SELL;
            // Mid wanders so both sides of the band and its edges get hit.
            final long price = MIN - 3 + random.nextInt(LEVELS + 6);
            final long quantity = random.nextInt(12) - 1;
            final long id = recentIds.isEmpty() ? 999_999 : recentIds.get(random.nextInt(recentIds.size()));
            final int kind = random.nextInt(100);

            final long expected;
            final long actual;
            if (kind < 45) {
                expected = reference.submit(instrument, side, price, quantity, TimeInForce.DAY);
                actual = engine.submit(instrument, side, price, quantity, TimeInForce.DAY);
            } else if (kind < 55) {
                expected = reference.submit(instrument, side, price, quantity, TimeInForce.IOC);
                actual = engine.submit(instrument, side, price, quantity, TimeInForce.IOC);
            } else if (kind < 58) {
                final long limit = side == Side.BUY ? Long.MAX_VALUE : OrderBook.NULL_PRICE;
                expected = reference.submit(instrument, side, limit, quantity, TimeInForce.IOC);
                actual = engine.submitMarket(instrument, side, quantity);
            } else if (kind < 80) {
                expected = reference.cancel(instrument, id);
                actual = engine.cancel(instrument, id);
            } else {
                expected = reference.replace(instrument, id, price, quantity);
                actual = engine.replace(instrument, id, price, quantity);
            }

            assertThat(actual).as("command %d return", n).isEqualTo(expected);
            final List<String> events = engineSink.drain();
            assertThat(events).as("command %d events", n).isEqualTo(referenceSink.drain());
            for (final String event : events) {
                if (event.startsWith("TRADE")) {
                    trades++;
                }
            }
            if (actual == MatchStatus.BOOK_FULL) {
                bookFull++;
            }
            if (actual > 0) {
                recentIds.add(actual);
                if (recentIds.size() > 400) {
                    recentIds.remove(0);
                }
            }
            assertBooksAgree(engine.book(instrument), reference, instrument, n);
        }

        // The stream really exercised matching and the pool limit, not just rejects.
        assertThat(trades).isGreaterThan(COMMANDS / 10);
        assertThat(bookFull).isPositive();
    }

    @Test
    @DisplayName("FR.SIM.02 the same command sequence gives the same events")
    void deterministic() {
        assertThat(run(7L)).isEqualTo(run(7L)).isNotEqualTo(run(8L));
    }

    private static List<String> run(final long seed) {
        final RecordingSink sink = new RecordingSink();
        final MatchingEngine engine = new MatchingEngine(new ManualClock(NOW), sink, CAPACITY, 1);
        engine.addBook(0, MIN, LEVELS);
        final Random random = new Random(seed);
        for (int n = 0; n < 5_000; n++) {
            final Side side = random.nextBoolean() ? Side.BUY : Side.SELL;
            final long price = MIN + random.nextInt(LEVELS);
            if (random.nextInt(4) == 0) {
                engine.cancel(0, 1 + random.nextInt(n + 1));
            } else {
                engine.submit(0, side, price, 1 + random.nextInt(10), TimeInForce.DAY);
            }
        }
        return sink.events;
    }

    private static void assertBooksAgree(
        final OrderBook book, final ReferenceMatchingEngine reference, final int instrument, final int n) {
        final List<String> resting = new ArrayList<>();
        final long[] bidQty = new long[LEVELS];
        final long[] askQty = new long[LEVELS];
        book.visitOrders((orderId, side, priceTicks, quantity) -> {
            resting.add(orderId + " " + side + " " + priceTicks + "x" + quantity);
            (side == Side.BUY ? bidQty : askQty)[(int) (priceTicks - MIN)] += quantity;
        });
        assertThat(resting).as("command %d book", n).isEqualTo(reference.snapshot(instrument));
        assertThat(book.orderCount()).isEqualTo(resting.size());

        // Level totals and best prices are maintained incrementally; check them against the orders.
        long bestBid = OrderBook.NULL_PRICE;
        long bestAsk = OrderBook.NULL_PRICE;
        for (int i = 0; i < LEVELS; i++) {
            assertThat(book.quantityAt(Side.BUY, MIN + i)).isEqualTo(bidQty[i]);
            assertThat(book.quantityAt(Side.SELL, MIN + i)).isEqualTo(askQty[i]);
            if (bidQty[i] > 0) {
                bestBid = MIN + i;
            }
            if (askQty[LEVELS - 1 - i] > 0) {
                bestAsk = MIN + LEVELS - 1 - i;
            }
        }
        assertThat(book.bestBidPrice()).as("command %d best bid", n).isEqualTo(bestBid);
        assertThat(book.bestAskPrice()).as("command %d best ask", n).isEqualTo(bestAsk);
        if (bestBid != OrderBook.NULL_PRICE && bestAsk != OrderBook.NULL_PRICE) {
            assertThat(bestBid).as("command %d crossed book", n).isLessThan(bestAsk);
        }
    }
}
