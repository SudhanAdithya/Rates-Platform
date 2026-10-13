package io.github.sudhanadithya.rates.venue;

import static io.github.sudhanadithya.rates.common.test.AllocationAssertions.assertNoAllocation;

import io.github.sudhanadithya.rates.common.clock.ManualClock;
import io.github.sudhanadithya.rates.venue.feed.FeedChannel;
import org.agrona.DirectBuffer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Runs with ./gradlew allocationTest. */
@Tag("allocation")
class VenueAllocationTest {

    private static final int WARMUP_CYCLES = 2_000_000;

    /** Reads one byte of each message so the encoding can't be optimized away. */
    private static final class CountingChannel implements FeedChannel {
        long messages;
        long bytes;

        @Override
        public boolean offer(final DirectBuffer buffer, final int offset, final int length) {
            messages++;
            bytes += length + buffer.getByte(offset);
            return true;
        }
    }

    @Test
    @DisplayName("FR.MD.09 flow, matching, A/B publishing, chaos, snapshots and definitions allocate nothing")
    void venueDutyCycle() {
        final ManualClock clock = new ManualClock(1_000_000_000L);
        final CountingChannel a = new CountingChannel();
        final CountingChannel b = new CountingChannel();
        final CountingChannel snapshot = new CountingChannel();
        final CountingChannel definitions = new CountingChannel();
        // Snapshots and definitions every 1 ms (50 cycles) instead of 10 ms and 100 ms, and a 2M
        // cycle warmup, so the timer paths run about 40k times and are C2 compiled before the
        // windows start. See AllocationAssertions for why late C2 compiles can show a few bytes.
        final VenueConfig base = VenueTestSupport.config(71);
        final VenueConfig config = new VenueConfig(base.seed(), base.orderCapacity(), 1_000_000L, 1_000_000L,
            base.maxOrdersPerSnapshotChunk(), base.chaosDelayCapacity(), base.flow(), base.books());
        final Venue venue = new Venue(config, VenueTestSupport.registry(), clock, a, b, snapshot, definitions);
        venue.chaosA().dropRatePpm(10_000);
        venue.chaosA().reorderRatePpm(10_000);
        venue.chaosA().delayNanos(50_000);

        // 20 us per duty cycle at 50k events per second: about one event per cycle.
        assertNoAllocation("Venue", WARMUP_CYCLES, i -> {
            clock.advance(20_000);
            venue.doWork();
        });

        if (a.messages == 0 || b.messages == 0 || snapshot.messages == 0 || definitions.messages == 0) {
            throw new AssertionError("a channel saw no messages");
        }
        if (venue.flow().rejects() != 0) {
            throw new AssertionError("flow saw rejects: " + venue.flow().rejects());
        }
    }
}
