package io.github.sudhanadithya.rates.venue.matching;

import static io.github.sudhanadithya.rates.common.test.AllocationAssertions.assertNoAllocation;

import io.github.sudhanadithya.rates.common.clock.ManualClock;
import io.github.sudhanadithya.rates.schemas.md.Side;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Runs with ./gradlew allocationTest. */
@Tag("allocation")
class MatchingAllocationTest {

    private static final int ZN = 0;
    private static final long MIN = 7_000;

    /** Sums event fields so the JIT can't drop the sink calls as dead code. */
    private static final class SummingSink implements MatchingEventSink {
        long sum;

        @Override
        public void onOrderAdded(final long t, final int i, final long id, final Side s, final long p, final long q) {
            sum += id + p + q;
        }

        @Override
        public void onOrderChanged(final long t, final int i, final long id, final Side s, final long p, final long q) {
            sum += id + p + q;
        }

        @Override
        public void onOrderDeleted(final long t, final int i, final long id, final Side s, final long p) {
            sum += id + p;
        }

        @Override
        public void onTrade(
            final long t, final int i, final long tradeId, final long p, final long q, final Side s,
            final long aggressor, final long resting) {
            sum += tradeId + p + q;
        }
    }

    @Test
    @DisplayName("FR.SIM.02 rest, reduce, partial fill, sweep, replace and cancel allocate nothing")
    void matchingCycle() {
        final ManualClock clock = new ManualClock(0);
        final SummingSink sink = new SummingSink();
        final MatchingEngine engine = new MatchingEngine(clock, sink, 1_024, 1);
        engine.addBook(ZN, MIN, 256);
        // A standing background book so the cycle matches against real queues.
        for (int level = 0; level < 10; level++) {
            engine.submit(ZN, Side.BUY, MIN + 100 - level, 5, TimeInForce.DAY);
            engine.submit(ZN, Side.SELL, MIN + 110 + level, 5, TimeInForce.DAY);
        }

        assertNoAllocation("MatchingEngine", i -> {
            clock.advance(1);
            final long offset = i & 7;
            // Passive bid inside the spread, reduced in place, then hit by a market sell.
            final long bid = engine.submit(ZN, Side.BUY, MIN + 101 + offset, 10, TimeInForce.DAY);
            engine.replace(ZN, bid, MIN + 101 + offset, 6);
            engine.submitMarket(ZN, Side.SELL, 6);
            // Ask that is partially filled by an IOC, moved (losing priority), then cancelled.
            final long ask = engine.submit(ZN, Side.SELL, MIN + 109, 8, TimeInForce.DAY);
            engine.submit(ZN, Side.BUY, MIN + 109, 3, TimeInForce.IOC);
            engine.replace(ZN, ask, MIN + 109 - offset, 5);
            engine.cancel(ZN, ask);
        });

        if (sink.sum == 0) {
            throw new AssertionError("sink saw no events");
        }
    }
}
