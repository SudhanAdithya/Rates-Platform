package io.github.sudhanadithya.rates.bench;

import io.github.sudhanadithya.rates.common.clock.ManualClock;
import io.github.sudhanadithya.rates.common.instrument.Instrument;
import io.github.sudhanadithya.rates.common.instrument.InstrumentRegistry;
import io.github.sudhanadithya.rates.common.price.TickSize;
import io.github.sudhanadithya.rates.schemas.md.Side;
import io.github.sudhanadithya.rates.venue.Venue;
import io.github.sudhanadithya.rates.venue.VenueConfig;
import io.github.sudhanadithya.rates.venue.feed.ChaosChannel;
import io.github.sudhanadithya.rates.venue.feed.FeedChannel;
import io.github.sudhanadithya.rates.venue.feed.IncrementalFeedPublisher;
import io.github.sudhanadithya.rates.venue.feed.SnapshotPublisher;
import io.github.sudhanadithya.rates.venue.flow.BookConfig;
import io.github.sudhanadithya.rates.venue.flow.FlowConfig;
import io.github.sudhanadithya.rates.venue.matching.MatchingEngine;
import io.github.sudhanadithya.rates.venue.matching.OrderBook;
import io.github.sudhanadithya.rates.venue.matching.TimeInForce;
import java.util.List;
import java.util.SplittableRandom;
import org.agrona.DirectBuffer;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

/**
 * Per message costs on the venue's feed side: encoding one incremental to A and B, passing a
 * message through a chaos channel, a 100 order snapshot, and one venue duty cycle with flow,
 * matching and publishing. Run with ./gradlew :bench:jmh.
 */
@State(Scope.Thread)
public class VenueFeedBenchmark {

    /** Reads a byte of each message so encoding can't be dropped as dead code. */
    private static final class CountingChannel implements FeedChannel {
        long sum;

        @Override
        public boolean offer(final DirectBuffer buffer, final int offset, final int length) {
            sum += length + buffer.getByte(offset + length - 1);
            return true;
        }
    }

    private final ManualClock clock = new ManualClock(1_000_000_000L);
    private final CountingChannel channel = new CountingChannel();
    private IncrementalFeedPublisher publisher;
    private ChaosChannel chaos;
    private SnapshotPublisher snapshots;
    private OrderBook snapshotBook;
    private Venue venue;
    private final org.agrona.concurrent.UnsafeBuffer message = new org.agrona.concurrent.UnsafeBuffer(new byte[62]);
    private long orderId;

    @Setup(Level.Trial)
    public void setUp() {
        final InstrumentRegistry registry = new InstrumentRegistry(1);
        registry.register(new Instrument(0, 42001, "ZNZ6", TickSize.HALF_32ND, 1_000));

        publisher = new IncrementalFeedPublisher(registry, channel, channel);

        chaos = new ChaosChannel(channel, clock, new SplittableRandom(1), 128, 1_024);
        chaos.dropRatePpm(10_000);
        chaos.reorderRatePpm(10_000);

        snapshots = new SnapshotPublisher(channel, 32);
        final MatchingEngine engine = new MatchingEngine(clock, publisher, 1_024, 1);
        snapshotBook = engine.addBook(0, 7_000, 256);
        for (int i = 0; i < 50; i++) {
            engine.submit(0, Side.BUY, 7_100 - i % 10, 1 + i % 5, TimeInForce.DAY);
            engine.submit(0, Side.SELL, 7_101 + i % 10, 1 + i % 5, TimeInForce.DAY);
        }

        final VenueConfig config = new VenueConfig(7, 4_096, 10_000_000L, 100_000_000L, 32, 1_024,
            FlowConfig.defaults(), List.of(new BookConfig(0, 7_040, 256)));
        venue = new Venue(config, registry, clock, channel, channel, channel, channel);
    }

    /** One BookUpdate NEW encoded and offered to A and B. */
    @Benchmark
    public long publishBookUpdate() {
        publisher.onOrderAdded(clock.nanos(), 0, ++orderId, Side.BUY, 7_040, 5);
        return channel.sum;
    }

    /** One message through a chaos channel with 1% drop and 1% reorder. */
    @Benchmark
    public long chaosOffer() {
        message.putLong(0, ++orderId);
        chaos.offer(message, 0, 62);
        return channel.sum;
    }

    /** A 100 order book as 4 snapshot chunks. */
    @Benchmark
    public long snapshot100Orders() {
        snapshots.publish(snapshotBook, 42001, ++orderId, clock.nanos());
        return channel.sum;
    }

    /** One duty cycle after 50 us of virtual time: about one flow event at 20k per second. */
    @Benchmark
    public long venueDutyCycle() {
        clock.advance(50_000);
        venue.doWork();
        return channel.sum;
    }
}
