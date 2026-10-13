package io.github.sudhanadithya.rates.venue.flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.sudhanadithya.rates.common.clock.ManualClock;
import io.github.sudhanadithya.rates.schemas.md.Side;
import io.github.sudhanadithya.rates.venue.matching.MatchingEngine;
import io.github.sudhanadithya.rates.venue.matching.MatchingEventSink;
import io.github.sudhanadithya.rates.venue.matching.OrderBook;
import java.util.List;
import java.util.SplittableRandom;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SyntheticFlowTest {

    private static final long MID = 7_040;
    private static final long STEP_NANOS = 100_000;

    /** Counts events by type and folds every field into a running hash. */
    private static final class CountingSink implements MatchingEventSink {
        long adds;
        long changes;
        long deletes;
        long trades;
        long hash = 17;

        @Override
        public void onOrderAdded(final long t, final int i, final long id, final Side s, final long p, final long q) {
            adds++;
            mix(t, id, p, q);
        }

        @Override
        public void onOrderChanged(final long t, final int i, final long id, final Side s, final long p, final long q) {
            changes++;
            mix(t, id, p, q);
        }

        @Override
        public void onOrderDeleted(final long t, final int i, final long id, final Side s, final long p) {
            deletes++;
            mix(t, id, p, 0);
        }

        @Override
        public void onTrade(
            final long t, final int i, final long tradeId, final long p, final long q, final Side s,
            final long aggressor, final long resting) {
            trades++;
            mix(t, tradeId, p, q);
        }

        private void mix(final long a, final long b, final long c, final long d) {
            hash = 31 * (31 * (31 * (31 * hash + a) + b) + c) + d;
        }
    }

    private final ManualClock clock = new ManualClock(0);
    private final CountingSink sink = new CountingSink();

    private SyntheticFlow flow(final long seed, final FlowConfig config, final MatchingEngine engine) {
        engine.addBook(0, MID - 128, 256);
        return new SyntheticFlow(engine, config, List.of(new BookConfig(0, MID, 256)), new SplittableRandom(seed), clock.nanos());
    }

    @Test
    @DisplayName("FR.MD.09 events arrive as a Poisson process at the configured rate")
    void poissonArrivals() {
        final MatchingEngine engine = new MatchingEngine(clock, sink, 8_192, 1);
        final SyntheticFlow flow = flow(1, new FlowConfig(50_000, 50, 30, 10, 10, 10, 2), engine);

        // 10 virtual seconds in 1 ms buckets: Poisson counts have variance equal to their mean (50).
        final int buckets = 10_000;
        long sum = 0;
        long sumSquares = 0;
        for (int b = 0; b < buckets; b++) {
            int inBucket = 0;
            for (int s = 0; s < 10; s++) {
                clock.advance(STEP_NANOS);
                inBucket += flow.generate(clock.nanos());
            }
            sum += inBucket;
            sumSquares += (long) inBucket * inBucket;
        }
        final double mean = (double) sum / buckets;
        final double variance = (double) sumSquares / buckets - mean * mean;

        assertThat(flow.events()).isEqualTo(sum);
        assertThat(mean).isBetween(49.5, 50.5);
        assertThat(variance / mean).isBetween(0.9, 1.1);
    }

    @Test
    @DisplayName("FR.MD.09 the flow mixes adds, changes, deletes and trades and keeps a healthy two sided book")
    void healthyBook() {
        final MatchingEngine engine = new MatchingEngine(clock, sink, 8_192, 1);
        final SyntheticFlow flow = flow(2, FlowConfig.defaults(), engine);
        final OrderBook book = engine.book(0);

        int twoSided = 0;
        int samples = 0;
        int maxOrders = 0;
        for (int step = 0; step < 200_000; step++) {
            clock.advance(STEP_NANOS);
            flow.generate(clock.nanos());
            if (step % 100 == 0) {
                samples++;
                if (book.bestBidPrice() != OrderBook.NULL_PRICE && book.bestAskPrice() != OrderBook.NULL_PRICE) {
                    twoSided++;
                    assertThat(book.bestBidPrice()).isLessThan(book.bestAskPrice());
                }
                maxOrders = Math.max(maxOrders, book.orderCount());
            }
        }

        assertThat(flow.events()).isGreaterThan(300_000);
        assertThat(flow.rejects()).isZero();
        assertThat(sink.adds).isPositive();
        assertThat(sink.changes).isPositive();
        assertThat(sink.deletes).isPositive();
        assertThat(sink.trades).isPositive();
        assertThat((double) twoSided / samples).isGreaterThan(0.95);
        assertThat(maxOrders).isLessThan(2_000);
        assertThat(flow.mid(0)).isBetween(book.minPriceTicks(), book.maxPriceTicks());
    }

    @Test
    @DisplayName("FR.MD.09 the same seed gives the same flow and a different seed doesn't")
    void deterministic() {
        assertThat(runHash(5)).isEqualTo(runHash(5)).isNotEqualTo(runHash(6));
    }

    @Test
    @DisplayName("A long clock jump is caught up over several calls, not one")
    void cappedPerCall() {
        final MatchingEngine engine = new MatchingEngine(clock, sink, 8_192, 1);
        final SyntheticFlow flow = flow(3, FlowConfig.defaults(), engine);
        clock.advance(1_000_000_000L);

        assertThat(flow.generate(clock.nanos())).isEqualTo(SyntheticFlow.MAX_EVENTS_PER_CALL);
    }

    @Test
    @DisplayName("Bad flow settings fail at startup")
    void badConfig() {
        assertThatThrownBy(() -> new FlowConfig(0, 50, 30, 10, 10, 10, 2)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FlowConfig(1, 60, 30, 20, 10, 10, 2)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BookConfig(0, 10, 256)).isInstanceOf(IllegalArgumentException.class);
        final MatchingEngine engine = new MatchingEngine(clock, sink, 16, 1);
        engine.addBook(0, MID - 5, 10);
        assertThatThrownBy(() -> new SyntheticFlow(engine, FlowConfig.defaults(), List.of(new BookConfig(0, MID, 10)),
            new SplittableRandom(1), 0)).hasMessageContaining("too narrow");
    }

    private long runHash(final long seed) {
        final ManualClock runClock = new ManualClock(0);
        final CountingSink runSink = new CountingSink();
        final MatchingEngine engine = new MatchingEngine(runClock, runSink, 8_192, 1);
        engine.addBook(0, MID - 128, 256);
        final SyntheticFlow flow = new SyntheticFlow(
            engine, FlowConfig.defaults(), List.of(new BookConfig(0, MID, 256)), new SplittableRandom(seed), 0);
        for (int step = 0; step < 20_000; step++) {
            runClock.advance(STEP_NANOS);
            flow.generate(runClock.nanos());
        }
        return runSink.hash;
    }
}
