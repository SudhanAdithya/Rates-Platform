package io.github.sudhanadithya.rates.venue.feed;

import io.github.sudhanadithya.rates.common.instrument.Instrument;
import io.github.sudhanadithya.rates.common.instrument.InstrumentRegistry;
import io.github.sudhanadithya.rates.schemas.md.InstrumentDefinitionEncoder;
import io.github.sudhanadithya.rates.schemas.md.MessageHeaderEncoder;
import java.nio.charset.StandardCharsets;
import org.agrona.concurrent.UnsafeBuffer;

/**
 * Sends an {@code InstrumentDefinition} for every listed instrument on the definitions channel.
 * The venue repeats this on a timer, like CME's security definition loop, so a feed handler that
 * starts late still learns the tick sizes and security ids before it builds books.
 */
public final class DefinitionsPublisher {

    private final FeedChannel channel;
    private final Instrument[] instruments;
    /** Symbols as fixed width ASCII, padded with zero bytes, encoded once at startup. */
    private final byte[][] symbols;
    private final UnsafeBuffer buffer = new UnsafeBuffer(new byte[64]);
    private final MessageHeaderEncoder header = new MessageHeaderEncoder();
    private final InstrumentDefinitionEncoder definition = new InstrumentDefinitionEncoder();

    private long refused;

    /** Startup only: copies the registry and encodes each symbol. */
    public DefinitionsPublisher(final InstrumentRegistry registry, final FeedChannel channel) {
        this.channel = channel;
        this.instruments = new Instrument[registry.count()];
        this.symbols = new byte[instruments.length][];
        final int symbolLength = InstrumentDefinitionEncoder.symbolLength();
        int n = 0;
        for (int id = 0; id < registry.capacity(); id++) {
            final Instrument instrument = registry.get(id);
            if (instrument == null) {
                continue;
            }
            final byte[] ascii = instrument.symbol().getBytes(StandardCharsets.US_ASCII);
            if (ascii.length > symbolLength) {
                throw new IllegalArgumentException("symbol longer than " + symbolLength + " bytes: " + instrument.symbol());
            }
            final byte[] padded = new byte[symbolLength];
            System.arraycopy(ascii, 0, padded, 0, ascii.length);
            instruments[n] = instrument;
            symbols[n] = padded;
            n++;
        }
    }

    /** Definitions the transport refused. */
    public long refused() {
        return refused;
    }

    /** Sends one definition per instrument, in instrument id order. @return messages sent */
    public int publish() {
        for (int i = 0; i < instruments.length; i++) {
            final Instrument instrument = instruments[i];
            definition.wrapAndApplyHeader(buffer, 0, header)
                .contractMultiplier(instrument.contractMultiplier())
                .securityId(instrument.securityId())
                .tickSize256ths(instrument.tickSize256ths())
                .putSymbol(symbols[i], 0);
            if (!channel.offer(buffer, 0, MessageHeaderEncoder.ENCODED_LENGTH + definition.encodedLength())) {
                refused++;
            }
        }
        return instruments.length;
    }
}
