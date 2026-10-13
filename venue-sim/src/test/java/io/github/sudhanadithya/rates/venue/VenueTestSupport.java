package io.github.sudhanadithya.rates.venue;

import io.github.sudhanadithya.rates.common.instrument.Instrument;
import io.github.sudhanadithya.rates.common.instrument.InstrumentRegistry;
import io.github.sudhanadithya.rates.common.price.TickSize;
import io.github.sudhanadithya.rates.schemas.md.BookUpdateDecoder;
import io.github.sudhanadithya.rates.schemas.md.MessageHeaderDecoder;
import io.github.sudhanadithya.rates.schemas.md.TradeDecoder;
import io.github.sudhanadithya.rates.venue.flow.BookConfig;
import io.github.sudhanadithya.rates.venue.flow.FlowConfig;
import java.util.List;
import org.agrona.concurrent.UnsafeBuffer;

/** Shared fixtures for venue tests. */
public final class VenueTestSupport {

    public static final int ZN = 0;
    public static final int ZF = 1;
    public static final int ZN_SECURITY_ID = 42001;
    public static final int ZF_SECURITY_ID = 42002;
    /** 110-00 in 64ths and 107-00 in 128ths. */
    public static final long ZN_MID = 110 * 64;
    public static final long ZF_MID = 107 * 128;

    private VenueTestSupport() {
    }

    public static InstrumentRegistry registry() {
        final InstrumentRegistry registry = new InstrumentRegistry(2);
        registry.register(new Instrument(ZN, ZN_SECURITY_ID, "ZNZ6", TickSize.HALF_32ND, 1_000));
        registry.register(new Instrument(ZF, ZF_SECURITY_ID, "ZFZ6", TickSize.QUARTER_32ND, 1_000));
        return registry;
    }

    public static VenueConfig config(final long seed) {
        return new VenueConfig(
            seed,
            4_096,
            10_000_000L,
            100_000_000L,
            32,
            1_024,
            new FlowConfig(50_000, 50, 30, 10, 10, 10, 2),
            List.of(new BookConfig(ZN, ZN_MID, 256), new BookConfig(ZF, ZF_MID, 256)));
    }

    /** Template id of an encoded message. */
    public static int templateId(final byte[] message) {
        return new MessageHeaderDecoder().wrap(new UnsafeBuffer(message), 0).templateId();
    }

    /** Channel sequence number of an incremental (BookUpdate or Trade) message. */
    public static long seqNum(final byte[] message) {
        final UnsafeBuffer buffer = new UnsafeBuffer(message);
        final MessageHeaderDecoder header = new MessageHeaderDecoder().wrap(buffer, 0);
        if (header.templateId() == BookUpdateDecoder.TEMPLATE_ID) {
            return new BookUpdateDecoder().wrapAndApplyHeader(buffer, 0, header).seqNum();
        }
        if (header.templateId() == TradeDecoder.TEMPLATE_ID) {
            return new TradeDecoder().wrapAndApplyHeader(buffer, 0, header).seqNum();
        }
        throw new IllegalArgumentException("not an incremental message: template " + header.templateId());
    }
}
