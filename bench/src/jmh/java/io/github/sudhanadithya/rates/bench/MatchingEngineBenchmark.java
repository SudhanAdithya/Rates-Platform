package io.github.sudhanadithya.rates.bench;

import io.github.sudhanadithya.rates.common.clock.ManualClock;
import io.github.sudhanadithya.rates.schemas.md.Side;
import io.github.sudhanadithya.rates.venue.matching.MatchingEngine;
import io.github.sudhanadithya.rates.venue.matching.MatchingEventSink;
import io.github.sudhanadithya.rates.venue.matching.TimeInForce;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

/**
 * Per command cost of the venue's FIFO matching engine (FR.SIM.02), including the event sink calls.
 * Each benchmark leaves the book as it found it, so every iteration sees the same state. Run with
 * ./gradlew :bench:jmh.
 */
@State(Scope.Thread)
public class MatchingEngineBenchmark {

    private static final int ZN = 0;
    private static final long MIN = 7_000;
    private static final long BEST_BID = MIN + 100;
    private static final long BEST_ASK = MIN + 101;

    /** Sums event fields so the sink calls can't be removed as dead code. */
    private static final class SummingSink implements MatchingEventSink {
        long sum;

        @Override
        public void onOrderAdded(final long t, final int i, final long id, final Side s, final long p, final long q) {
            sum += id + q;
        }

        @Override
        public void onOrderChanged(final long t, final int i, final long id, final Side s, final long p, final long q) {
            sum += id + q;
        }

        @Override
        public void onOrderDeleted(final long t, final int i, final long id, final Side s, final long p) {
            sum += id;
        }

        @Override
        public void onTrade(
            final long t, final int i, final long tradeId, final long p, final long q, final Side s,
            final long aggressor, final long resting) {
            sum += tradeId + q;
        }
    }

    private final ManualClock clock = new ManualClock(0);
    private final SummingSink sink = new SummingSink();
    private MatchingEngine engine;

    @Setup(Level.Trial)
    public void setUp() {
        engine = new MatchingEngine(clock, sink, 4_096, 1);
        engine.addBook(ZN, MIN, 256);
        // Ten levels a side with a 500 order queue at the best bid, so adds land behind real depth.
        for (int level = 0; level < 10; level++) {
            engine.submit(ZN, Side.BUY, BEST_BID - level, 5, TimeInForce.DAY);
            engine.submit(ZN, Side.SELL, BEST_ASK + 1 + level, 5, TimeInForce.DAY);
        }
        for (int n = 0; n < 500; n++) {
            engine.submit(ZN, Side.BUY, BEST_BID, 1, TimeInForce.DAY);
        }
    }

    /** A passive order joins the back of a 500 deep queue, then cancels: NEW then DELETE. */
    @Benchmark
    public long addAndCancelBehindQueue() {
        clock.advance(1);
        final long id = engine.submit(ZN, Side.BUY, BEST_BID, 1, TimeInForce.DAY);
        engine.cancel(ZN, id);
        return sink.sum;
    }

    /** An ask rests, then a buy IOC takes all of it: NEW, then TRADE and DELETE. */
    @Benchmark
    public long restThenFill() {
        clock.advance(1);
        engine.submit(ZN, Side.SELL, BEST_ASK, 3, TimeInForce.DAY);
        engine.submit(ZN, Side.BUY, BEST_ASK, 3, TimeInForce.IOC);
        return sink.sum;
    }

    /** A resting ask is reduced in place (CHANGE), then moved one tick away (DELETE, NEW), then cancelled. */
    @Benchmark
    public long reduceMoveCancel() {
        clock.advance(1);
        final long id = engine.submit(ZN, Side.SELL, BEST_ASK, 5, TimeInForce.DAY);
        engine.replace(ZN, id, BEST_ASK, 4);
        engine.replace(ZN, id, BEST_ASK + 1, 4);
        engine.cancel(ZN, id);
        return sink.sum;
    }
}
