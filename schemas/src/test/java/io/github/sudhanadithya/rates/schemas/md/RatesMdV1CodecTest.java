package io.github.sudhanadithya.rates.schemas.md;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.ByteBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Round trips for the messages added in schema version 1. */
class RatesMdV1CodecTest {

    private static final long T0 = 1_760_000_000_000_000_000L;

    private final UnsafeBuffer buffer = new UnsafeBuffer(ByteBuffer.allocateDirect(4096));
    private final MessageHeaderEncoder headerEncoder = new MessageHeaderEncoder();
    private final MessageHeaderDecoder headerDecoder = new MessageHeaderDecoder();

    @Test
    @DisplayName("schema is version 1")
    void schemaVersion() {
        assertThat(MessageHeaderEncoder.SCHEMA_VERSION).isEqualTo(1);
        assertThat(SnapshotFullRefreshEncoder.SCHEMA_ID).isEqualTo(1);
    }

    @Test
    @DisplayName("FR.MD.02 SnapshotFullRefresh round trips resting orders in queue priority order")
    void snapshotFullRefresh() {
        final SnapshotFullRefreshEncoder encoder = new SnapshotFullRefreshEncoder()
            .wrapAndApplyHeader(buffer, 0, headerEncoder)
            .lastSeqNum(5_000L)
            .transactTimeNanos(T0)
            .securityId(42_001)
            .chunkIndex(0)
            .chunkCount(1);
        final SnapshotFullRefreshEncoder.OrdersEncoder orders = encoder.ordersCount(3);
        orders.next().orderId(11L).priceTicks(7_073L).quantity(5L).side(Side.BUY);
        orders.next().orderId(12L).priceTicks(7_073L).quantity(7L).side(Side.BUY);
        orders.next().orderId(13L).priceTicks(7_074L).quantity(2L).side(Side.SELL);

        final SnapshotFullRefreshDecoder decoder =
            new SnapshotFullRefreshDecoder().wrapAndApplyHeader(buffer, 0, headerDecoder);
        assertThat(headerDecoder.templateId()).isEqualTo(SnapshotFullRefreshDecoder.TEMPLATE_ID);
        assertThat(decoder.lastSeqNum()).isEqualTo(5_000L);
        assertThat(decoder.transactTimeNanos()).isEqualTo(T0);
        assertThat(decoder.securityId()).isEqualTo(42_001);
        assertThat(decoder.chunkIndex()).isZero();
        assertThat(decoder.chunkCount()).isEqualTo(1);

        final SnapshotFullRefreshDecoder.OrdersDecoder decodedOrders = decoder.orders();
        assertThat(decodedOrders.count()).isEqualTo(3);
        final long[][] expected = {{11L, 7_073L, 5L}, {12L, 7_073L, 7L}, {13L, 7_074L, 2L}};
        final Side[] expectedSides = {Side.BUY, Side.BUY, Side.SELL};
        for (int i = 0; i < 3; i++) {
            decodedOrders.next();
            assertThat(decodedOrders.orderId()).isEqualTo(expected[i][0]);
            assertThat(decodedOrders.priceTicks()).isEqualTo(expected[i][1]);
            assertThat(decodedOrders.quantity()).isEqualTo(expected[i][2]);
            assertThat(decodedOrders.side()).isEqualTo(expectedSides[i]);
        }
        assertThat(decodedOrders.hasNext()).isFalse();
    }

    @Test
    @DisplayName("FR.MD.07 TopOfBook carries receive and publish timestamps")
    void topOfBook() {
        new TopOfBookEncoder().wrapAndApplyHeader(buffer, 0, headerEncoder)
            .sourceSeqNum(5_001L)
            .bidPriceTicks(7_073L).bidQuantity(12L)
            .askPriceTicks(7_074L).askQuantity(2L)
            .exchangeTimeNanos(T0).receiveTimeNanos(T0 + 4_000).publishTimeNanos(T0 + 4_300)
            .securityId(42_001).bidOrderCount(2).askOrderCount(1)
            .bookState(BookState.TRUSTED);

        final TopOfBookDecoder decoder = new TopOfBookDecoder().wrapAndApplyHeader(buffer, 0, headerDecoder);
        assertThat(decoder.sourceSeqNum()).isEqualTo(5_001L);
        assertThat(decoder.bidPriceTicks()).isEqualTo(7_073L);
        assertThat(decoder.bidQuantity()).isEqualTo(12L);
        assertThat(decoder.askPriceTicks()).isEqualTo(7_074L);
        assertThat(decoder.askQuantity()).isEqualTo(2L);
        assertThat(decoder.exchangeTimeNanos()).isEqualTo(T0);
        assertThat(decoder.receiveTimeNanos()).isEqualTo(T0 + 4_000);
        assertThat(decoder.publishTimeNanos()).isEqualTo(T0 + 4_300);
        assertThat(decoder.securityId()).isEqualTo(42_001);
        assertThat(decoder.bidOrderCount()).isEqualTo(2);
        assertThat(decoder.askOrderCount()).isEqualTo(1);
        assertThat(decoder.bookState()).isEqualTo(BookState.TRUSTED);
    }

    @Test
    @DisplayName("TopOfBook marks an empty side with the int64 null price")
    void topOfBookEmptySide() {
        new TopOfBookEncoder().wrapAndApplyHeader(buffer, 0, headerEncoder)
            .bidPriceTicks(TopOfBookEncoder.bidPriceTicksNullValue()).bidQuantity(0L)
            .askPriceTicks(7_074L).askQuantity(2L)
            .bookState(BookState.TRUSTED);

        final TopOfBookDecoder decoder = new TopOfBookDecoder().wrapAndApplyHeader(buffer, 0, headerDecoder);
        assertThat(decoder.bidPriceTicks()).isEqualTo(Long.MIN_VALUE).isEqualTo(TopOfBookDecoder.bidPriceTicksNullValue());
        assertThat(decoder.bidQuantity()).isZero();
    }

    @Test
    @DisplayName("FR.MD.08 BookDepth round trips bid and ask levels best first")
    void bookDepth() {
        final BookDepthEncoder encoder = new BookDepthEncoder().wrapAndApplyHeader(buffer, 0, headerEncoder)
            .sourceSeqNum(5_002L)
            .exchangeTimeNanos(T0).receiveTimeNanos(T0 + 1).publishTimeNanos(T0 + 2)
            .securityId(42_001)
            .bookState(BookState.TRUSTED);
        final BookDepthEncoder.BidsEncoder bids = encoder.bidsCount(2);
        bids.next().priceTicks(7_073L).quantity(12L).orderCount(2);
        bids.next().priceTicks(7_072L).quantity(30L).orderCount(4);
        encoder.asksCount(1).next().priceTicks(7_074L).quantity(2L).orderCount(1);

        final BookDepthDecoder decoder = new BookDepthDecoder().wrapAndApplyHeader(buffer, 0, headerDecoder);
        assertThat(decoder.sourceSeqNum()).isEqualTo(5_002L);
        assertThat(decoder.securityId()).isEqualTo(42_001);
        assertThat(decoder.bookState()).isEqualTo(BookState.TRUSTED);

        final BookDepthDecoder.BidsDecoder decodedBids = decoder.bids();
        assertThat(decodedBids.count()).isEqualTo(2);
        decodedBids.next();
        assertThat(decodedBids.priceTicks()).isEqualTo(7_073L);
        assertThat(decodedBids.quantity()).isEqualTo(12L);
        assertThat(decodedBids.orderCount()).isEqualTo(2);
        decodedBids.next();
        assertThat(decodedBids.priceTicks()).isEqualTo(7_072L);
        assertThat(decodedBids.quantity()).isEqualTo(30L);
        assertThat(decodedBids.orderCount()).isEqualTo(4);

        final BookDepthDecoder.AsksDecoder decodedAsks = decoder.asks();
        assertThat(decodedAsks.count()).isEqualTo(1);
        decodedAsks.next();
        assertThat(decodedAsks.priceTicks()).isEqualTo(7_074L);
        assertThat(decodedAsks.quantity()).isEqualTo(2L);
        assertThat(decodedAsks.orderCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("FR.MD.03 BookStatus carries state, reason and last applied sequence")
    void bookStatus() {
        new BookStatusEncoder().wrapAndApplyHeader(buffer, 0, headerEncoder)
            .lastSeqNum(4_999L)
            .timeNanos(T0)
            .securityId(42_001)
            .bookState(BookState.STALE)
            .reason(BookStateReason.SEQUENCE_GAP);

        final BookStatusDecoder decoder = new BookStatusDecoder().wrapAndApplyHeader(buffer, 0, headerDecoder);
        assertThat(headerDecoder.templateId()).isEqualTo(BookStatusDecoder.TEMPLATE_ID);
        assertThat(decoder.lastSeqNum()).isEqualTo(4_999L);
        assertThat(decoder.timeNanos()).isEqualTo(T0);
        assertThat(decoder.securityId()).isEqualTo(42_001);
        assertThat(decoder.bookState()).isEqualTo(BookState.STALE);
        assertThat(decoder.reason()).isEqualTo(BookStateReason.SEQUENCE_GAP);
    }

    @Test
    @DisplayName("FR.MD.07 FeedHandlerTrade carries exchange, receive and publish time")
    void feedHandlerTrade() {
        new FeedHandlerTradeEncoder().wrapAndApplyHeader(buffer, 0, headerEncoder)
            .sourceSeqNum(5_003L).tradeId(9_002L).priceTicks(7_074L).quantity(3L)
            .exchangeTimeNanos(T0).receiveTimeNanos(T0 + 10).publishTimeNanos(T0 + 20)
            .securityId(42_001).aggressorSide(AggressorSide.SELL);

        final FeedHandlerTradeDecoder decoder =
            new FeedHandlerTradeDecoder().wrapAndApplyHeader(buffer, 0, headerDecoder);
        assertThat(decoder.sourceSeqNum()).isEqualTo(5_003L);
        assertThat(decoder.tradeId()).isEqualTo(9_002L);
        assertThat(decoder.priceTicks()).isEqualTo(7_074L);
        assertThat(decoder.quantity()).isEqualTo(3L);
        assertThat(decoder.exchangeTimeNanos()).isEqualTo(T0);
        assertThat(decoder.receiveTimeNanos()).isEqualTo(T0 + 10);
        assertThat(decoder.publishTimeNanos()).isEqualTo(T0 + 20);
        assertThat(decoder.securityId()).isEqualTo(42_001);
        assertThat(decoder.aggressorSide()).isEqualTo(AggressorSide.SELL);
    }
}
