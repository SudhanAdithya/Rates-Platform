package io.github.sudhanadithya.rates.schemas.md;

import static io.github.sudhanadithya.rates.common.test.AllocationAssertions.assertNoAllocation;

import java.nio.ByteBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Runs with ./gradlew allocationTest. */
@Tag("allocation")
class CodecAllocationTest {

    // Large enough for the biggest message here: a 10 order snapshot is 8 + 28 + 4 + 10 x 32 = 360 bytes.
    private final UnsafeBuffer buffer = new UnsafeBuffer(ByteBuffer.allocateDirect(1024));
    private final MessageHeaderEncoder headerEncoder = new MessageHeaderEncoder();
    private final MessageHeaderDecoder headerDecoder = new MessageHeaderDecoder();
    private final BookUpdateEncoder bookEncoder = new BookUpdateEncoder();
    private final BookUpdateDecoder bookDecoder = new BookUpdateDecoder();
    private final TradeEncoder tradeEncoder = new TradeEncoder();
    private final TradeDecoder tradeDecoder = new TradeDecoder();
    private final TopOfBookEncoder topEncoder = new TopOfBookEncoder();
    private final TopOfBookDecoder topDecoder = new TopOfBookDecoder();
    private final BookDepthEncoder depthEncoder = new BookDepthEncoder();
    private final BookDepthDecoder depthDecoder = new BookDepthDecoder();
    private final SnapshotFullRefreshEncoder snapshotEncoder = new SnapshotFullRefreshEncoder();
    private final SnapshotFullRefreshDecoder snapshotDecoder = new SnapshotFullRefreshDecoder();

    /** Results land here so the JIT can't treat the measured work as dead code. */
    private long sink;

    @Test
    @DisplayName("FR.MD.05 BookUpdate encode and decode allocate nothing")
    void bookUpdate() {
        assertNoAllocation("BookUpdate codec", i -> {
            bookEncoder.wrapAndApplyHeader(buffer, 0, headerEncoder)
                .seqNum(i)
                .orderId(1_000_000L + i)
                .priceTicks(7_000L + (i & 63))
                .quantity(1 + (i & 15))
                .exchangeTimeNanos(i)
                .receiveTimeNanos(i + 1)
                .securityId(42)
                .side((i & 1) == 0 ? Side.BUY : Side.SELL)
                .action(UpdateAction.NEW);

            bookDecoder.wrapAndApplyHeader(buffer, 0, headerDecoder);
            sink += bookDecoder.seqNum() + bookDecoder.priceTicks() + bookDecoder.quantity()
                + bookDecoder.side().value() + bookDecoder.action().value();
        });
    }

    @Test
    @DisplayName("FR.MD.05 Trade encode and decode allocate nothing")
    void trade() {
        assertNoAllocation("Trade codec", i -> {
            tradeEncoder.wrapAndApplyHeader(buffer, 0, headerEncoder)
                .seqNum(i)
                .tradeId(i)
                .priceTicks(7_000L + (i & 63))
                .quantity(1)
                .exchangeTimeNanos(i)
                .receiveTimeNanos(i + 1)
                .securityId(42)
                .aggressorSide(AggressorSide.BUY);

            tradeDecoder.wrapAndApplyHeader(buffer, 0, headerDecoder);
            sink += tradeDecoder.priceTicks() + tradeDecoder.aggressorSide().value();
        });
    }

    @Test
    @DisplayName("FR.MD.07 TopOfBook encode and decode allocate nothing")
    void topOfBook() {
        assertNoAllocation("TopOfBook codec", i -> {
            topEncoder.wrapAndApplyHeader(buffer, 0, headerEncoder)
                .sourceSeqNum(i)
                .bidPriceTicks(7_000L + (i & 63)).bidQuantity(10)
                .askPriceTicks(7_001L + (i & 63)).askQuantity(5)
                .exchangeTimeNanos(i).receiveTimeNanos(i + 1).publishTimeNanos(i + 2)
                .securityId(42).bidOrderCount(3).askOrderCount(2)
                .bookState(BookState.TRUSTED);

            topDecoder.wrapAndApplyHeader(buffer, 0, headerDecoder);
            sink += topDecoder.bidPriceTicks() + topDecoder.askPriceTicks() + topDecoder.bookState().value();
        });
    }

    @Test
    @DisplayName("FR.MD.08 BookDepth with five levels a side encodes and decodes without allocating")
    void bookDepth() {
        assertNoAllocation("BookDepth codec", i -> {
            depthEncoder.wrapAndApplyHeader(buffer, 0, headerEncoder)
                .sourceSeqNum(i).exchangeTimeNanos(i).receiveTimeNanos(i + 1).publishTimeNanos(i + 2)
                .securityId(42).bookState(BookState.TRUSTED);
            final BookDepthEncoder.BidsEncoder bids = depthEncoder.bidsCount(5);
            for (int level = 0; level < 5; level++) {
                bids.next().priceTicks(7_000L - level).quantity(10 + level).orderCount(1 + level);
            }
            final BookDepthEncoder.AsksEncoder asks = depthEncoder.asksCount(5);
            for (int level = 0; level < 5; level++) {
                asks.next().priceTicks(7_001L + level).quantity(10 + level).orderCount(1 + level);
            }

            depthDecoder.wrapAndApplyHeader(buffer, 0, headerDecoder);
            final BookDepthDecoder.BidsDecoder decodedBids = depthDecoder.bids();
            while (decodedBids.hasNext()) {
                sink += decodedBids.next().priceTicks();
            }
            final BookDepthDecoder.AsksDecoder decodedAsks = depthDecoder.asks();
            while (decodedAsks.hasNext()) {
                sink += decodedAsks.next().quantity();
            }
        });
    }

    @Test
    @DisplayName("FR.MD.02 SnapshotFullRefresh with ten orders encodes and decodes without allocating")
    void snapshot() {
        assertNoAllocation("SnapshotFullRefresh codec", i -> {
            snapshotEncoder.wrapAndApplyHeader(buffer, 0, headerEncoder)
                .lastSeqNum(i).transactTimeNanos(i).securityId(42).chunkIndex(0).chunkCount(1);
            final SnapshotFullRefreshEncoder.OrdersEncoder orders = snapshotEncoder.ordersCount(10);
            for (int order = 0; order < 10; order++) {
                orders.next().orderId(i + order).priceTicks(7_000L + order).quantity(1).side(Side.BUY);
            }

            snapshotDecoder.wrapAndApplyHeader(buffer, 0, headerDecoder);
            final SnapshotFullRefreshDecoder.OrdersDecoder decodedOrders = snapshotDecoder.orders();
            while (decodedOrders.hasNext()) {
                sink += decodedOrders.next().orderId();
            }
        });
    }
}
