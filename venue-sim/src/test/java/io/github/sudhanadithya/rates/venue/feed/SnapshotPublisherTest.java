package io.github.sudhanadithya.rates.venue.feed;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sudhanadithya.rates.common.clock.ManualClock;
import io.github.sudhanadithya.rates.schemas.md.MessageHeaderDecoder;
import io.github.sudhanadithya.rates.schemas.md.Side;
import io.github.sudhanadithya.rates.schemas.md.SnapshotFullRefreshDecoder;
import io.github.sudhanadithya.rates.venue.CapturingChannel;
import io.github.sudhanadithya.rates.venue.FeedBook;
import io.github.sudhanadithya.rates.venue.matching.MatchingEngine;
import io.github.sudhanadithya.rates.venue.matching.MatchingEventSink;
import io.github.sudhanadithya.rates.venue.matching.OrderBook;
import io.github.sudhanadithya.rates.venue.matching.TimeInForce;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SnapshotPublisherTest {

    private static final int SECURITY_ID = 42001;

    private final CapturingChannel channel = new CapturingChannel();
    private final MatchingEngine engine = new MatchingEngine(new ManualClock(0), new NoOpSink(), 64, 1);
    private final OrderBook book = engine.addBook(0, 1_000, 100);

    @Test
    @DisplayName("FR.MD.02 a snapshot lists every order in priority order with the last sequence number")
    void fullRefresh() {
        engine.submit(0, Side.BUY, 1_010, 1, TimeInForce.DAY);
        engine.submit(0, Side.BUY, 1_011, 2, TimeInForce.DAY);
        engine.submit(0, Side.SELL, 1_013, 3, TimeInForce.DAY);

        final int chunks = new SnapshotPublisher(channel, 32).publish(book, SECURITY_ID, 77, 5_000);

        assertThat(chunks).isEqualTo(1);
        final SnapshotFullRefreshDecoder snapshot = decode(channel.messages.get(0));
        assertThat(snapshot.lastSeqNum()).isEqualTo(77);
        assertThat(snapshot.transactTimeNanos()).isEqualTo(5_000);
        assertThat(snapshot.securityId()).isEqualTo(SECURITY_ID);
        assertThat(snapshot.chunkIndex()).isZero();
        assertThat(snapshot.chunkCount()).isEqualTo(1);
        final SnapshotFullRefreshDecoder.OrdersDecoder orders = snapshot.orders();
        assertThat(orders.count()).isEqualTo(3);
        assertThat(orders.next().orderId()).isEqualTo(2);
        assertThat(orders.next().orderId()).isEqualTo(1);
        final SnapshotFullRefreshDecoder.OrdersDecoder ask = orders.next();
        assertThat(ask.orderId()).isEqualTo(3);
        assertThat(ask.side()).isEqualTo(Side.SELL);
        assertThat(ask.priceTicks()).isEqualTo(1_013);
        assertThat(ask.quantity()).isEqualTo(3);
    }

    @Test
    @DisplayName("FR.MD.02 a big book is split into numbered chunks that rebuild it exactly")
    void chunks() {
        for (int i = 0; i < 5; i++) {
            engine.submit(0, i % 2 == 0 ? Side.BUY : Side.SELL, i % 2 == 0 ? 1_010 - i : 1_020 + i, 1 + i, TimeInForce.DAY);
        }

        final int chunks = new SnapshotPublisher(channel, 2).publish(book, SECURITY_ID, 9, 0);

        assertThat(chunks).isEqualTo(3);
        assertThat(channel.messages).hasSize(3);
        final FeedBook rebuilt = new FeedBook();
        for (int i = 0; i < 3; i++) {
            final SnapshotFullRefreshDecoder snapshot = decode(channel.messages.get(i));
            assertThat(snapshot.chunkIndex()).isEqualTo(i);
            assertThat(snapshot.chunkCount()).isEqualTo(3);
            assertThat(snapshot.orders().count()).isEqualTo(i < 2 ? 2 : 1);
            assertThat(rebuilt.applySnapshot(channel.messages.get(i))).isEqualTo(9);
        }
        assertThat(rebuilt.describe(SECURITY_ID)).isEqualTo(FeedBook.describe(book));
    }

    @Test
    @DisplayName("FR.MD.02 an empty book still sends one chunk with no orders")
    void emptyBook() {
        final SnapshotPublisher publisher = new SnapshotPublisher(channel, 4);
        assertThat(publisher.publish(book, SECURITY_ID, 3, 0)).isEqualTo(1);

        final SnapshotFullRefreshDecoder snapshot = decode(channel.messages.get(0));
        assertThat(snapshot.chunkCount()).isEqualTo(1);
        assertThat(snapshot.orders().count()).isZero();

        // The writer's state resets between books: a later full snapshot is still correct.
        engine.submit(0, Side.BUY, 1_010, 1, TimeInForce.DAY);
        publisher.publish(book, SECURITY_ID, 4, 0);
        assertThat(decode(channel.messages.get(1)).orders().count()).isEqualTo(1);
    }

    private static SnapshotFullRefreshDecoder decode(final byte[] message) {
        return new SnapshotFullRefreshDecoder().wrapAndApplyHeader(new UnsafeBuffer(message), 0, new MessageHeaderDecoder());
    }

    private static final class NoOpSink implements MatchingEventSink {
        @Override
        public void onOrderAdded(final long t, final int i, final long id, final Side s, final long p, final long q) {
        }

        @Override
        public void onOrderChanged(final long t, final int i, final long id, final Side s, final long p, final long q) {
        }

        @Override
        public void onOrderDeleted(final long t, final int i, final long id, final Side s, final long p) {
        }

        @Override
        public void onTrade(
            final long t, final int i, final long tradeId, final long p, final long q, final Side s,
            final long aggressor, final long resting) {
        }
    }
}
