package io.github.sudhanadithya.rates.venue.feed;

import io.github.sudhanadithya.rates.schemas.md.MessageHeaderEncoder;
import io.github.sudhanadithya.rates.schemas.md.Side;
import io.github.sudhanadithya.rates.schemas.md.SnapshotFullRefreshEncoder;
import io.github.sudhanadithya.rates.venue.matching.OrderBook;
import io.github.sudhanadithya.rates.venue.matching.OrderVisitor;
import org.agrona.concurrent.UnsafeBuffer;

/**
 * Writes a full refresh of one book to the snapshot channel: every resting order in priority order
 * (bids best first, then asks best first, queue order within a level), stamped with the last
 * incremental sequence number it includes. A feed handler that lost messages rebuilds from this
 * and replays only incrementals above {@code lastSeqNum} (FR.MD.02).
 *
 * <p>Books bigger than {@code maxOrdersPerChunk} are split across several messages that share the
 * same {@code lastSeqNum}, numbered by {@code chunkIndex} out of {@code chunkCount}. An empty book
 * still sends one chunk with no orders, so a handler can tell "empty" from "missing".
 */
public final class SnapshotPublisher {

    private static final int ORDER_ENTRY_LENGTH = SnapshotFullRefreshEncoder.OrdersEncoder.sbeBlockLength();
    private static final int MAX_CHUNKS = 0xFFFF;

    private final FeedChannel channel;
    private final int maxOrdersPerChunk;
    private final UnsafeBuffer buffer;
    private final MessageHeaderEncoder header = new MessageHeaderEncoder();
    private final SnapshotFullRefreshEncoder snapshot = new SnapshotFullRefreshEncoder();
    private final ChunkWriter writer = new ChunkWriter();

    private long refused;

    /** Startup only: sizes the scratch buffer for the largest chunk. */
    public SnapshotPublisher(final FeedChannel channel, final int maxOrdersPerChunk) {
        if (maxOrdersPerChunk <= 0 || maxOrdersPerChunk > 0xFFFF) {
            throw new IllegalArgumentException("max orders per chunk must be 1 to 65535: " + maxOrdersPerChunk);
        }
        this.channel = channel;
        this.maxOrdersPerChunk = maxOrdersPerChunk;
        this.buffer = new UnsafeBuffer(new byte[MessageHeaderEncoder.ENCODED_LENGTH
            + SnapshotFullRefreshEncoder.BLOCK_LENGTH
            + SnapshotFullRefreshEncoder.OrdersEncoder.sbeHeaderSize()
            + maxOrdersPerChunk * ORDER_ENTRY_LENGTH]);
    }

    /** Snapshot chunks the transport refused. */
    public long refused() {
        return refused;
    }

    /**
     * Publishes every chunk for one book.
     *
     * @return the number of chunks sent, or 0 if the book needs more than 65535 chunks
     */
    public int publish(final OrderBook book, final int securityId, final long lastSeqNum, final long transactTimeNanos) {
        final int total = book.orderCount();
        final int chunkCount = total == 0 ? 1 : (total + maxOrdersPerChunk - 1) / maxOrdersPerChunk;
        if (chunkCount > MAX_CHUNKS) {
            return 0;
        }
        writer.begin(securityId, lastSeqNum, transactTimeNanos, total, chunkCount);
        if (total == 0) {
            writer.startChunk();
            writer.finishChunk();
        } else {
            book.visitOrders(writer);
        }
        return chunkCount;
    }

    /** Fills chunks as the book is walked. A named field instance, so the walk captures nothing. */
    private final class ChunkWriter implements OrderVisitor {

        private int securityId;
        private long lastSeqNum;
        private long transactTimeNanos;
        private int total;
        private int chunkCount;
        private int chunkIndex;
        private int written;
        private int inChunk;
        private int chunkSize;
        private SnapshotFullRefreshEncoder.OrdersEncoder orders;

        void begin(final int securityId, final long lastSeqNum, final long transactTimeNanos, final int total, final int chunkCount) {
            this.securityId = securityId;
            this.lastSeqNum = lastSeqNum;
            this.transactTimeNanos = transactTimeNanos;
            this.total = total;
            this.chunkCount = chunkCount;
            this.chunkIndex = 0;
            this.written = 0;
        }

        @Override
        public void visit(final long orderId, final Side side, final long priceTicks, final long quantity) {
            if (inChunk == 0) {
                startChunk();
            }
            orders.next().orderId(orderId).priceTicks(priceTicks).quantity(quantity).side(side);
            written++;
            if (++inChunk == chunkSize) {
                finishChunk();
            }
        }

        void startChunk() {
            chunkSize = Math.min(maxOrdersPerChunk, total - written);
            inChunk = 0;
            orders = snapshot.wrapAndApplyHeader(buffer, 0, header)
                .lastSeqNum(lastSeqNum)
                .transactTimeNanos(transactTimeNanos)
                .securityId(securityId)
                .chunkIndex(chunkIndex)
                .chunkCount(chunkCount)
                .ordersCount(chunkSize);
        }

        void finishChunk() {
            if (!channel.offer(buffer, 0, MessageHeaderEncoder.ENCODED_LENGTH + snapshot.encodedLength())) {
                refused++;
            }
            chunkIndex++;
            inChunk = 0;
        }
    }
}
