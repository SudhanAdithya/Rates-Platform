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

    private final UnsafeBuffer buffer = new UnsafeBuffer(ByteBuffer.allocateDirect(256));
    private final MessageHeaderEncoder headerEncoder = new MessageHeaderEncoder();
    private final MessageHeaderDecoder headerDecoder = new MessageHeaderDecoder();
    private final BookUpdateEncoder bookEncoder = new BookUpdateEncoder();
    private final BookUpdateDecoder bookDecoder = new BookUpdateDecoder();
    private final TradeEncoder tradeEncoder = new TradeEncoder();
    private final TradeDecoder tradeDecoder = new TradeDecoder();

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
}
