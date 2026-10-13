package io.github.sudhanadithya.rates.venue;

import io.github.sudhanadithya.rates.common.clock.Clock;
import io.github.sudhanadithya.rates.common.instrument.Instrument;
import io.github.sudhanadithya.rates.common.instrument.InstrumentRegistry;
import io.github.sudhanadithya.rates.venue.feed.ChaosChannel;
import io.github.sudhanadithya.rates.venue.feed.DefinitionsPublisher;
import io.github.sudhanadithya.rates.venue.feed.FeedChannel;
import io.github.sudhanadithya.rates.venue.feed.IncrementalFeedPublisher;
import io.github.sudhanadithya.rates.venue.feed.SnapshotPublisher;
import io.github.sudhanadithya.rates.venue.flow.BookConfig;
import io.github.sudhanadithya.rates.venue.flow.SyntheticFlow;
import io.github.sudhanadithya.rates.venue.matching.MatchingEngine;
import java.util.SplittableRandom;
import org.agrona.concurrent.Agent;

/**
 * The simulated exchange in live mode: synthetic flow into the matching engine, engine events out
 * on the A and B incremental channels, plus a snapshot loop and a definitions loop.
 *
 * <p>One thread runs {@link #doWork} in a loop (an Agrona {@link Agent}, so task 2.6 can hand it
 * to an {@code AgentRunner} with a busy spin idle strategy). That thread owns every object here,
 * including the chaos switches; nothing is shared. Each duty cycle reads the injected clock once
 * for scheduling, so with a {@code ManualClock} a whole session is reproducible.
 *
 * <p>Chaos sits between the publisher and each incremental channel. The snapshot and definitions
 * channels are left clean, so recovery always has something good to recover from.
 */
public final class Venue implements Agent {

    /** Every message the venue sends fits in this; the snapshot channel has its own sizing. */
    private static final int MAX_INCREMENTAL_LENGTH = 128;

    private final Clock clock;
    private final VenueConfig config;
    private final MatchingEngine engine;
    private final IncrementalFeedPublisher incremental;
    private final ChaosChannel chaosA;
    private final ChaosChannel chaosB;
    private final SnapshotPublisher snapshots;
    private final DefinitionsPublisher definitions;
    private final SyntheticFlow flow;

    private final int[] bookInstrumentIds;
    private final int[] bookSecurityIds;

    private long nextSnapshotNanos;
    private long nextDefinitionsNanos;

    /** Startup only: builds the engine, books, publishers and flow. */
    public Venue(
        final VenueConfig config,
        final InstrumentRegistry registry,
        final Clock clock,
        final FeedChannel incrementalA,
        final FeedChannel incrementalB,
        final FeedChannel snapshot,
        final FeedChannel definitionsChannel) {
        this.clock = clock;
        this.config = config;

        // Child generators split from one seed, so adding a chaos roll never shifts the flow.
        final SplittableRandom root = new SplittableRandom(config.seed());
        final SplittableRandom flowRandom = root.split();
        this.chaosA = new ChaosChannel(incrementalA, clock, root.split(), MAX_INCREMENTAL_LENGTH, config.chaosDelayCapacity());
        this.chaosB = new ChaosChannel(incrementalB, clock, root.split(), MAX_INCREMENTAL_LENGTH, config.chaosDelayCapacity());

        this.incremental = new IncrementalFeedPublisher(registry, chaosA, chaosB);
        this.engine = new MatchingEngine(clock, incremental, config.orderCapacity(), registry.capacity());
        final int bookCount = config.books().size();
        this.bookInstrumentIds = new int[bookCount];
        this.bookSecurityIds = new int[bookCount];
        for (int i = 0; i < bookCount; i++) {
            final BookConfig book = config.books().get(i);
            final Instrument instrument = registry.get(book.instrumentId());
            if (instrument == null) {
                throw new IllegalArgumentException("book configured for unknown instrument " + book.instrumentId());
            }
            engine.addBook(book.instrumentId(), book.minPriceTicks(), book.levelCount());
            bookInstrumentIds[i] = book.instrumentId();
            bookSecurityIds[i] = instrument.securityId();
        }

        this.snapshots = new SnapshotPublisher(snapshot, config.maxOrdersPerSnapshotChunk());
        this.definitions = new DefinitionsPublisher(registry, definitionsChannel);

        final long start = clock.nanos();
        this.flow = new SyntheticFlow(engine, config.flow(), config.books(), flowRandom, start);
        // Definitions go out on the first duty cycle; the first snapshot one interval later.
        this.nextDefinitionsNanos = start;
        this.nextSnapshotNanos = start + config.snapshotIntervalNanos();
    }

    @Override
    public int doWork() {
        final long now = clock.nanos();
        int work = 0;
        if (now >= nextDefinitionsNanos) {
            work += definitions.publish();
            nextDefinitionsNanos = now + config.definitionsIntervalNanos();
        }
        work += flow.generate(now);
        if (now >= nextSnapshotNanos) {
            work += publishSnapshots(now);
            nextSnapshotNanos = now + config.snapshotIntervalNanos();
        }
        work += chaosA.poll(now);
        work += chaosB.poll(now);
        return work;
    }

    @Override
    public String roleName() {
        return "venue-sim";
    }

    /** Switches for the A channel. Owner thread only. */
    public ChaosChannel chaosA() {
        return chaosA;
    }

    /** Switches for the B channel. Owner thread only. */
    public ChaosChannel chaosB() {
        return chaosB;
    }

    public MatchingEngine engine() {
        return engine;
    }

    public IncrementalFeedPublisher incremental() {
        return incremental;
    }

    public SnapshotPublisher snapshots() {
        return snapshots;
    }

    public SyntheticFlow flow() {
        return flow;
    }

    /**
     * Every book's snapshot carries the same {@code lastSeqNum}: they are taken between two
     * commands on the thread that owns the books, so each is consistent with that sequence number.
     */
    private int publishSnapshots(final long now) {
        final long lastSeqNum = incremental.lastSeqNum();
        int chunks = 0;
        for (int i = 0; i < bookInstrumentIds.length; i++) {
            chunks += snapshots.publish(engine.book(bookInstrumentIds[i]), bookSecurityIds[i], lastSeqNum, now);
        }
        return chunks;
    }
}
