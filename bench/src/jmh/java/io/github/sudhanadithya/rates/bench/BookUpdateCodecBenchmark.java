package io.github.sudhanadithya.rates.bench;

import io.github.sudhanadithya.rates.schemas.md.BookUpdateDecoder;
import io.github.sudhanadithya.rates.schemas.md.BookUpdateEncoder;
import io.github.sudhanadithya.rates.schemas.md.MessageHeaderDecoder;
import io.github.sudhanadithya.rates.schemas.md.MessageHeaderEncoder;
import io.github.sudhanadithya.rates.schemas.md.Side;
import io.github.sudhanadithya.rates.schemas.md.UpdateAction;
import java.nio.ByteBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

/** Cost of SBE encode and decode for one BookUpdate (FR.MD.05). Run with ./gradlew :bench:jmh. */
@State(Scope.Thread)
public class BookUpdateCodecBenchmark {

    private final UnsafeBuffer buffer = new UnsafeBuffer(ByteBuffer.allocateDirect(256));
    private final MessageHeaderEncoder headerEncoder = new MessageHeaderEncoder();
    private final MessageHeaderDecoder headerDecoder = new MessageHeaderDecoder();
    private final BookUpdateEncoder encoder = new BookUpdateEncoder();
    private final BookUpdateDecoder decoder = new BookUpdateDecoder();

    private long seqNum;

    @Setup
    public void encodeOnce() {
        encode();
    }

    @Benchmark
    public int encode() {
        final long seq = ++seqNum;
        encoder.wrapAndApplyHeader(buffer, 0, headerEncoder)
            .seqNum(seq)
            .orderId(1_000_000L + seq)
            .priceTicks(7_000L + (seq & 63))
            .quantity(1 + (seq & 15))
            .exchangeTimeNanos(seq)
            .receiveTimeNanos(seq + 1)
            .securityId(42)
            .side((seq & 1) == 0 ? Side.BUY : Side.SELL)
            .action(UpdateAction.NEW);
        return encoder.encodedLength();
    }

    /** Reads every field, so the cost of each load is counted. Returned values go to JMH's blackhole. */
    @Benchmark
    public long decode() {
        decoder.wrapAndApplyHeader(buffer, 0, headerDecoder);
        return decoder.seqNum() + decoder.orderId() + decoder.priceTicks() + decoder.quantity()
            + decoder.exchangeTimeNanos() + decoder.receiveTimeNanos() + decoder.securityId()
            + decoder.side().value() + decoder.action().value();
    }

    @Benchmark
    public long encodeThenDecode() {
        encode();
        return decode();
    }
}
