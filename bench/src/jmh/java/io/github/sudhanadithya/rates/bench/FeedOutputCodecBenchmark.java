package io.github.sudhanadithya.rates.bench;

import io.github.sudhanadithya.rates.schemas.md.BookDepthDecoder;
import io.github.sudhanadithya.rates.schemas.md.BookDepthEncoder;
import io.github.sudhanadithya.rates.schemas.md.BookState;
import io.github.sudhanadithya.rates.schemas.md.MessageHeaderDecoder;
import io.github.sudhanadithya.rates.schemas.md.MessageHeaderEncoder;
import io.github.sudhanadithya.rates.schemas.md.TopOfBookDecoder;
import io.github.sudhanadithya.rates.schemas.md.TopOfBookEncoder;
import java.nio.ByteBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;

/**
 * Cost of encoding and decoding the feed handler's per update outputs (FR.MD.07, FR.MD.08).
 * Run with ./gradlew :bench:jmh.
 */
@State(Scope.Thread)
public class FeedOutputCodecBenchmark {

    private static final int DEPTH_LEVELS = 5;

    private final UnsafeBuffer buffer = new UnsafeBuffer(ByteBuffer.allocateDirect(1024));
    private final MessageHeaderEncoder headerEncoder = new MessageHeaderEncoder();
    private final MessageHeaderDecoder headerDecoder = new MessageHeaderDecoder();
    private final TopOfBookEncoder topEncoder = new TopOfBookEncoder();
    private final TopOfBookDecoder topDecoder = new TopOfBookDecoder();
    private final BookDepthEncoder depthEncoder = new BookDepthEncoder();
    private final BookDepthDecoder depthDecoder = new BookDepthDecoder();

    private long seq;

    @Benchmark
    public long topOfBookEncodeThenDecode() {
        final long s = ++seq;
        topEncoder.wrapAndApplyHeader(buffer, 0, headerEncoder)
            .sourceSeqNum(s)
            .bidPriceTicks(7_000L + (s & 63)).bidQuantity(10)
            .askPriceTicks(7_001L + (s & 63)).askQuantity(5)
            .exchangeTimeNanos(s).receiveTimeNanos(s + 1).publishTimeNanos(s + 2)
            .securityId(42_001).bidOrderCount(3).askOrderCount(2)
            .bookState(BookState.TRUSTED);

        topDecoder.wrapAndApplyHeader(buffer, 0, headerDecoder);
        return topDecoder.bidPriceTicks() + topDecoder.askPriceTicks() + topDecoder.bidQuantity()
            + topDecoder.askQuantity() + topDecoder.publishTimeNanos();
    }

    /** Five levels a side, every field read back. */
    @Benchmark
    public long bookDepth5EncodeThenDecode() {
        final long s = ++seq;
        depthEncoder.wrapAndApplyHeader(buffer, 0, headerEncoder)
            .sourceSeqNum(s).exchangeTimeNanos(s).receiveTimeNanos(s + 1).publishTimeNanos(s + 2)
            .securityId(42_001).bookState(BookState.TRUSTED);
        final BookDepthEncoder.BidsEncoder bids = depthEncoder.bidsCount(DEPTH_LEVELS);
        for (int level = 0; level < DEPTH_LEVELS; level++) {
            bids.next().priceTicks(7_000L - level).quantity(10 + level).orderCount(1 + level);
        }
        final BookDepthEncoder.AsksEncoder asks = depthEncoder.asksCount(DEPTH_LEVELS);
        for (int level = 0; level < DEPTH_LEVELS; level++) {
            asks.next().priceTicks(7_001L + level).quantity(10 + level).orderCount(1 + level);
        }

        depthDecoder.wrapAndApplyHeader(buffer, 0, headerDecoder);
        long sum = depthDecoder.sourceSeqNum();
        final BookDepthDecoder.BidsDecoder decodedBids = depthDecoder.bids();
        while (decodedBids.hasNext()) {
            decodedBids.next();
            sum += decodedBids.priceTicks() + decodedBids.quantity() + decodedBids.orderCount();
        }
        final BookDepthDecoder.AsksDecoder decodedAsks = depthDecoder.asks();
        while (decodedAsks.hasNext()) {
            decodedAsks.next();
            sum += decodedAsks.priceTicks() + decodedAsks.quantity() + decodedAsks.orderCount();
        }
        return sum;
    }
}
