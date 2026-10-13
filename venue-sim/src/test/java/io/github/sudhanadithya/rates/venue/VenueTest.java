package io.github.sudhanadithya.rates.venue;

import static io.github.sudhanadithya.rates.venue.VenueTestSupport.ZF;
import static io.github.sudhanadithya.rates.venue.VenueTestSupport.ZF_SECURITY_ID;
import static io.github.sudhanadithya.rates.venue.VenueTestSupport.ZN;
import static io.github.sudhanadithya.rates.venue.VenueTestSupport.ZN_SECURITY_ID;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.sudhanadithya.rates.common.clock.ManualClock;
import io.github.sudhanadithya.rates.schemas.md.InstrumentDefinitionDecoder;
import io.github.sudhanadithya.rates.schemas.md.MessageHeaderDecoder;
import io.github.sudhanadithya.rates.schemas.md.SnapshotFullRefreshDecoder;
import java.util.ArrayList;
import java.util.List;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class VenueTest {

    private static final long STEP_NANOS = 100_000;
    /** Two virtual seconds. */
    private static final int STEPS = 20_000;

    /** One session's output on every channel. */
    private static final class Session {
        final ManualClock clock = new ManualClock(1_760_000_000_000_000_000L);
        final CapturingChannel a = new CapturingChannel();
        final CapturingChannel b = new CapturingChannel();
        final CapturingChannel snapshot = new CapturingChannel();
        final CapturingChannel definitions = new CapturingChannel();
        final Venue venue;

        Session(final long seed) {
            venue = new Venue(VenueTestSupport.config(seed), VenueTestSupport.registry(), clock, a, b, snapshot, definitions);
        }

        Session run(final int steps) {
            for (int i = 0; i < steps; i++) {
                clock.advance(STEP_NANOS);
                venue.doWork();
            }
            return this;
        }
    }

    @Test
    @DisplayName("FR.SIM.01 the same seed gives byte identical output on every channel, chaos included")
    void deterministic() {
        final Session first = withChaos(new Session(11)).run(STEPS);
        final Session second = withChaos(new Session(11)).run(STEPS);
        final Session other = withChaos(new Session(12)).run(STEPS);

        assertThat(first.a.messages).isNotEmpty();
        assertSameBytes(first.a.messages, second.a.messages);
        assertSameBytes(first.b.messages, second.b.messages);
        assertSameBytes(first.snapshot.messages, second.snapshot.messages);
        assertSameBytes(first.definitions.messages, second.definitions.messages);
        assertThat(other.a.messages.size()).isNotEqualTo(first.a.messages.size());
    }

    @Test
    @DisplayName("FR.MD.01 with chaos on A, channel B alone carries every sequence number and rebuilds the book")
    void channelBIsComplete() {
        final Session session = withChaos(new Session(21)).run(STEPS);
        final Venue venue = session.venue;
        final long last = venue.incremental().lastSeqNum();

        assertThat(last).isGreaterThan(50_000);
        for (int i = 0; i < session.b.messages.size(); i++) {
            assertThat(VenueTestSupport.seqNum(session.b.messages.get(i))).isEqualTo(i + 1);
        }
        assertThat(session.b.messages).hasSize((int) last);
        assertThat(venue.chaosA().dropped()).isPositive();
        assertThat(venue.chaosA().reordered()).isPositive();
        assertThat(session.a.messages).hasSizeLessThan((int) last);

        final FeedBook fromB = new FeedBook();
        for (final byte[] message : session.b.messages) {
            fromB.applyIncremental(message);
        }
        assertThat(fromB.describe(ZN_SECURITY_ID)).isEqualTo(FeedBook.describe(venue.engine().book(ZN))).isNotEmpty();
        assertThat(fromB.describe(ZF_SECURITY_ID)).isEqualTo(FeedBook.describe(venue.engine().book(ZF))).isNotEmpty();
    }

    @Test
    @DisplayName("FR.MD.02 a mid session snapshot plus the incrementals after its lastSeqNum rebuild the book")
    void snapshotRecovery() {
        final Session session = new Session(31).run(STEPS);
        final Venue venue = session.venue;

        // Snapshots go out every 10 ms, so two seconds gives 200 rounds, each one chunk 0 per book,
        // and every round sends all of its chunks.
        final List<byte[]> snapshots = session.snapshot.messages;
        int firstChunks = 0;
        int expectedChunks = 0;
        for (final byte[] message : snapshots) {
            final SnapshotFullRefreshDecoder chunk = decodeSnapshot(message);
            if (chunk.chunkIndex() == 0) {
                firstChunks++;
                expectedChunks += chunk.chunkCount();
            }
        }
        assertThat(firstChunks).isEqualTo(2 * 200);
        assertThat(snapshots).hasSize(expectedChunks);
        final long recoverySeq = lastSeqNum(snapshots.get(snapshots.size() / 2));
        final FeedBook recovered = new FeedBook();
        for (final byte[] message : snapshots) {
            if (lastSeqNum(message) == recoverySeq) {
                recovered.applySnapshot(message);
            }
        }
        for (final byte[] message : session.a.messages) {
            if (VenueTestSupport.seqNum(message) > recoverySeq) {
                recovered.applyIncremental(message);
            }
        }

        assertThat(recoverySeq).isBetween(1L, venue.incremental().lastSeqNum() - 1);
        assertThat(recovered.describe(ZN_SECURITY_ID)).isEqualTo(FeedBook.describe(venue.engine().book(ZN)));
        assertThat(recovered.describe(ZF_SECURITY_ID)).isEqualTo(FeedBook.describe(venue.engine().book(ZF)));
    }

    @Test
    @DisplayName("FR.MD.09 definitions go out on the first duty cycle and then on their timer")
    void definitionsLoop() {
        final Session session = new Session(41).run(1);
        assertThat(session.definitions.messages).hasSize(2);
        final InstrumentDefinitionDecoder first = new InstrumentDefinitionDecoder()
            .wrapAndApplyHeader(new UnsafeBuffer(session.definitions.messages.get(0)), 0, new MessageHeaderDecoder());
        assertThat(first.symbol()).isEqualTo("ZNZ6");

        session.run(STEPS);
        // Every 100 ms for 2 s, plus the first round.
        assertThat(session.definitions.messages.size()).isBetween(2 * 20, 2 * 21);
    }

    @Test
    @DisplayName("FR.MD.01 with chaos off A and B are identical")
    void cleanChannelsMatch() {
        final Session session = new Session(51).run(2_000);
        assertSameBytes(session.a.messages, session.b.messages);
        assertThat(session.venue.flow().rejects()).isZero();
    }

    private static Session withChaos(final Session session) {
        session.venue.chaosA().dropRatePpm(5_000);
        session.venue.chaosA().reorderRatePpm(5_000);
        session.venue.chaosA().delayNanos(250_000);
        return session;
    }

    private static long lastSeqNum(final byte[] message) {
        return decodeSnapshot(message).lastSeqNum();
    }

    private static SnapshotFullRefreshDecoder decodeSnapshot(final byte[] message) {
        return new SnapshotFullRefreshDecoder().wrapAndApplyHeader(new UnsafeBuffer(message), 0, new MessageHeaderDecoder());
    }

    private static void assertSameBytes(final List<byte[]> expected, final List<byte[]> actual) {
        assertThat(actual).hasSameSizeAs(expected);
        final List<Integer> differing = new ArrayList<>();
        for (int i = 0; i < expected.size(); i++) {
            if (!java.util.Arrays.equals(expected.get(i), actual.get(i))) {
                differing.add(i);
            }
        }
        assertThat(differing).isEmpty();
    }
}
