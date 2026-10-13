package io.github.sudhanadithya.rates.venue.feed;

import io.github.sudhanadithya.rates.common.instrument.Instrument;
import io.github.sudhanadithya.rates.common.instrument.InstrumentRegistry;
import io.github.sudhanadithya.rates.schemas.md.AggressorSide;
import io.github.sudhanadithya.rates.schemas.md.BookUpdateEncoder;
import io.github.sudhanadithya.rates.schemas.md.MessageHeaderEncoder;
import io.github.sudhanadithya.rates.schemas.md.Side;
import io.github.sudhanadithya.rates.schemas.md.TradeEncoder;
import io.github.sudhanadithya.rates.schemas.md.UpdateAction;
import io.github.sudhanadithya.rates.venue.matching.MatchingEventSink;
import org.agrona.concurrent.UnsafeBuffer;

/**
 * Turns matching engine events into the MDP style incremental feed and writes every message to
 * both the A and the B channel (FR.MD.01 depends on the two being identical).
 *
 * <p>Each message gets the next channel sequence number, one counter across all instruments, the
 * way a CME channel numbers its packets. A gap in that number is how a feed handler knows it lost
 * something (FR.MD.02), and a snapshot's {@code lastSeqNum} says which incremental it includes.
 *
 * <p>{@code receiveTimeNanos} is written as 0: the feed handler fills in its own receive time on
 * its output messages. One scratch buffer and one encoder per message type are reused, so
 * publishing allocates nothing.
 */
public final class IncrementalFeedPublisher implements MatchingEventSink {

    /** Largest incremental message plus header, rounded up. */
    private static final int BUFFER_LENGTH = 128;

    private final UnsafeBuffer buffer = new UnsafeBuffer(new byte[BUFFER_LENGTH]);
    private final MessageHeaderEncoder header = new MessageHeaderEncoder();
    private final BookUpdateEncoder bookUpdate = new BookUpdateEncoder();
    private final TradeEncoder trade = new TradeEncoder();

    /** Venue security id by internal instrument id, copied at startup so lookup is one array load. */
    private final int[] securityIds;
    private final FeedChannel channelA;
    private final FeedChannel channelB;

    private long seqNum;
    private long refusedA;
    private long refusedB;

    /** Startup only. */
    public IncrementalFeedPublisher(final InstrumentRegistry registry, final FeedChannel channelA, final FeedChannel channelB) {
        this.securityIds = new int[registry.capacity()];
        for (int id = 0; id < securityIds.length; id++) {
            final Instrument instrument = registry.get(id);
            securityIds[id] = instrument == null ? 0 : instrument.securityId();
        }
        this.channelA = channelA;
        this.channelB = channelB;
    }

    /** Sequence number of the last message sent; 0 before the first. */
    public long lastSeqNum() {
        return seqNum;
    }

    /** Messages the A channel's transport refused. */
    public long refusedA() {
        return refusedA;
    }

    /** Messages the B channel's transport refused. */
    public long refusedB() {
        return refusedB;
    }

    @Override
    public void onOrderAdded(
        final long transactTimeNanos, final int instrumentId, final long orderId, final Side side,
        final long priceTicks, final long quantity) {
        publishBookUpdate(transactTimeNanos, instrumentId, orderId, side, priceTicks, quantity, UpdateAction.NEW);
    }

    @Override
    public void onOrderChanged(
        final long transactTimeNanos, final int instrumentId, final long orderId, final Side side,
        final long priceTicks, final long quantity) {
        publishBookUpdate(transactTimeNanos, instrumentId, orderId, side, priceTicks, quantity, UpdateAction.CHANGE);
    }

    @Override
    public void onOrderDeleted(
        final long transactTimeNanos, final int instrumentId, final long orderId, final Side side,
        final long priceTicks) {
        publishBookUpdate(transactTimeNanos, instrumentId, orderId, side, priceTicks, 0, UpdateAction.DELETE);
    }

    @Override
    public void onTrade(
        final long transactTimeNanos, final int instrumentId, final long tradeId, final long priceTicks,
        final long quantity, final Side aggressorSide, final long aggressorOrderId, final long restingOrderId) {
        trade.wrapAndApplyHeader(buffer, 0, header)
            .seqNum(++seqNum)
            .tradeId(tradeId)
            .priceTicks(priceTicks)
            .quantity(quantity)
            .exchangeTimeNanos(transactTimeNanos)
            .receiveTimeNanos(0)
            .securityId(securityIds[instrumentId])
            .aggressorSide(aggressorSide == Side.BUY ? AggressorSide.BUY : AggressorSide.SELL);
        send(MessageHeaderEncoder.ENCODED_LENGTH + trade.encodedLength());
    }

    private void publishBookUpdate(
        final long transactTimeNanos, final int instrumentId, final long orderId, final Side side,
        final long priceTicks, final long quantity, final UpdateAction action) {
        bookUpdate.wrapAndApplyHeader(buffer, 0, header)
            .seqNum(++seqNum)
            .orderId(orderId)
            .priceTicks(priceTicks)
            .quantity(quantity)
            .exchangeTimeNanos(transactTimeNanos)
            .receiveTimeNanos(0)
            .securityId(securityIds[instrumentId])
            .side(side)
            .action(action);
        send(MessageHeaderEncoder.ENCODED_LENGTH + bookUpdate.encodedLength());
    }

    private void send(final int length) {
        if (!channelA.offer(buffer, 0, length)) {
            refusedA++;
        }
        if (!channelB.offer(buffer, 0, length)) {
            refusedB++;
        }
    }
}
