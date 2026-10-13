package io.github.sudhanadithya.rates.venue.feed;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.sudhanadithya.rates.schemas.md.InstrumentDefinitionDecoder;
import io.github.sudhanadithya.rates.schemas.md.MessageHeaderDecoder;
import io.github.sudhanadithya.rates.venue.CapturingChannel;
import io.github.sudhanadithya.rates.venue.VenueTestSupport;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DefinitionsPublisherTest {

    @Test
    @DisplayName("FR.MD.09 every listed instrument's definition goes out with symbol, tick and multiplier")
    void definitions() {
        final CapturingChannel channel = new CapturingChannel();
        final DefinitionsPublisher publisher = new DefinitionsPublisher(VenueTestSupport.registry(), channel);

        assertThat(publisher.publish()).isEqualTo(2);

        final InstrumentDefinitionDecoder zn = decode(channel.messages.get(0));
        assertThat(zn.symbol()).isEqualTo("ZNZ6");
        assertThat(zn.securityId()).isEqualTo(VenueTestSupport.ZN_SECURITY_ID);
        assertThat(zn.tickSize256ths()).isEqualTo(4);
        assertThat(zn.contractMultiplier()).isEqualTo(1_000);
        final InstrumentDefinitionDecoder zf = decode(channel.messages.get(1));
        assertThat(zf.symbol()).isEqualTo("ZFZ6");
        assertThat(zf.tickSize256ths()).isEqualTo(2);

        publisher.publish();
        assertThat(channel.messages.get(2)).isEqualTo(channel.messages.get(0));
    }

    private static InstrumentDefinitionDecoder decode(final byte[] message) {
        return new InstrumentDefinitionDecoder().wrapAndApplyHeader(new UnsafeBuffer(message), 0, new MessageHeaderDecoder());
    }
}
