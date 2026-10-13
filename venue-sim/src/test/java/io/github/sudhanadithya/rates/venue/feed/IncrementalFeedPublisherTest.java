package io.github.sudhanadithya.rates.venue.feed;

import static io.github.sudhanadithya.rates.venue.VenueTestSupport.ZF;
import static io.github.sudhanadithya.rates.venue.VenueTestSupport.ZF_SECURITY_ID;
import static io.github.sudhanadithya.rates.venue.VenueTestSupport.ZN;
import static io.github.sudhanadithya.rates.venue.VenueTestSupport.ZN_SECURITY_ID;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.sudhanadithya.rates.schemas.md.AggressorSide;
import io.github.sudhanadithya.rates.schemas.md.BookUpdateDecoder;
import io.github.sudhanadithya.rates.schemas.md.MessageHeaderDecoder;
import io.github.sudhanadithya.rates.schemas.md.Side;
import io.github.sudhanadithya.rates.schemas.md.TradeDecoder;
import io.github.sudhanadithya.rates.schemas.md.UpdateAction;
import io.github.sudhanadithya.rates.venue.CapturingChannel;
import io.github.sudhanadithya.rates.venue.VenueTestSupport;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class IncrementalFeedPublisherTest {

    private final CapturingChannel a = new CapturingChannel();
    private final CapturingChannel b = new CapturingChannel();
    private final IncrementalFeedPublisher publisher = new IncrementalFeedPublisher(VenueTestSupport.registry(), a, b);

    @Test
    @DisplayName("FR.MD.01 every message goes to A and B byte for byte with one sequence number")
    void sameBytesOnBothChannels() {
        publisher.onOrderAdded(10, ZN, 1, Side.BUY, 7_040, 5);
        publisher.onTrade(11, ZF, 1, 13_696, 2, Side.SELL, 3, 2);
        publisher.onOrderDeleted(12, ZN, 1, Side.BUY, 7_040);

        assertThat(a.messages).hasSize(3);
        for (int i = 0; i < 3; i++) {
            assertThat(b.messages.get(i)).isEqualTo(a.messages.get(i));
            assertThat(VenueTestSupport.seqNum(a.messages.get(i))).isEqualTo(i + 1);
        }
        assertThat(publisher.lastSeqNum()).isEqualTo(3);
    }

    @Test
    @DisplayName("FR.MD.04 book updates carry the MBO action, order, price, quantity and security id")
    void bookUpdateFields() {
        publisher.onOrderAdded(10, ZN, 7, Side.BUY, 7_040, 5);
        publisher.onOrderChanged(11, ZN, 7, Side.BUY, 7_040, 3);
        publisher.onOrderDeleted(12, ZF, 8, Side.SELL, 13_700);

        assertBookUpdate(a.messages.get(0), 1, UpdateAction.NEW, 7, Side.BUY, 7_040, 5, 10, ZN_SECURITY_ID);
        assertBookUpdate(a.messages.get(1), 2, UpdateAction.CHANGE, 7, Side.BUY, 7_040, 3, 11, ZN_SECURITY_ID);
        assertBookUpdate(a.messages.get(2), 3, UpdateAction.DELETE, 8, Side.SELL, 13_700, 0, 12, ZF_SECURITY_ID);
    }

    @Test
    @DisplayName("Trades carry the trade id, resting price, quantity and aggressor side")
    void tradeFields() {
        publisher.onTrade(20, ZN, 9, 7_041, 4, Side.SELL, 5, 2);

        final UnsafeBuffer buffer = new UnsafeBuffer(a.messages.get(0));
        final TradeDecoder trade = new TradeDecoder().wrapAndApplyHeader(buffer, 0, new MessageHeaderDecoder());
        assertThat(trade.seqNum()).isEqualTo(1);
        assertThat(trade.tradeId()).isEqualTo(9);
        assertThat(trade.priceTicks()).isEqualTo(7_041);
        assertThat(trade.quantity()).isEqualTo(4);
        assertThat(trade.exchangeTimeNanos()).isEqualTo(20);
        assertThat(trade.receiveTimeNanos()).isZero();
        assertThat(trade.securityId()).isEqualTo(ZN_SECURITY_ID);
        assertThat(trade.aggressorSide()).isEqualTo(AggressorSide.SELL);
    }

    @Test
    @DisplayName("A refusal on one channel is counted and doesn't stop the other")
    void refusalCounted() {
        a.accept = false;
        publisher.onOrderAdded(10, ZN, 1, Side.BUY, 7_040, 5);

        assertThat(publisher.refusedA()).isEqualTo(1);
        assertThat(publisher.refusedB()).isZero();
        assertThat(b.messages).hasSize(1);
    }

    private static void assertBookUpdate(
        final byte[] message, final long seqNum, final UpdateAction action, final long orderId, final Side side,
        final long priceTicks, final long quantity, final long time, final int securityId) {
        final BookUpdateDecoder update = new BookUpdateDecoder()
            .wrapAndApplyHeader(new UnsafeBuffer(message), 0, new MessageHeaderDecoder());
        assertThat(update.seqNum()).isEqualTo(seqNum);
        assertThat(update.action()).isEqualTo(action);
        assertThat(update.orderId()).isEqualTo(orderId);
        assertThat(update.side()).isEqualTo(side);
        assertThat(update.priceTicks()).isEqualTo(priceTicks);
        assertThat(update.quantity()).isEqualTo(quantity);
        assertThat(update.exchangeTimeNanos()).isEqualTo(time);
        assertThat(update.securityId()).isEqualTo(securityId);
    }
}
