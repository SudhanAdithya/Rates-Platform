package io.github.sudhanadithya.rates.schemas.md;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.ByteBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class RatesMdCodecTest {

    private final UnsafeBuffer buffer = new UnsafeBuffer(ByteBuffer.allocateDirect(4096));
    private final MessageHeaderEncoder headerEncoder = new MessageHeaderEncoder();
    private final MessageHeaderDecoder headerDecoder = new MessageHeaderDecoder();

    @BeforeEach
    void clearBuffer() {
        buffer.setMemory(0, buffer.capacity(), (byte) 0);
    }

    @Test
    @DisplayName("FR.MD.05 BookUpdate round trips through flyweights over an UnsafeBuffer")
    void bookUpdateRoundTrip() {
        final BookUpdateEncoder encoder = new BookUpdateEncoder();
        encoder.wrapAndApplyHeader(buffer, 0, headerEncoder)
            .seqNum(1_001L)
            .orderId(77_000_123L)
            .priceTicks(7_080L)
            .quantity(25L)
            .exchangeTimeNanos(1_760_000_000_000_000_000L)
            .receiveTimeNanos(1_760_000_000_000_004_200L)
            .securityId(42)
            .side(Side.SELL)
            .action(UpdateAction.CHANGE);

        assertThat(MessageHeaderEncoder.ENCODED_LENGTH + encoder.encodedLength())
            .isEqualTo(MessageHeaderEncoder.ENCODED_LENGTH + BookUpdateEncoder.BLOCK_LENGTH);

        final BookUpdateDecoder decoder = new BookUpdateDecoder().wrapAndApplyHeader(buffer, 0, headerDecoder);

        assertThat(headerDecoder.templateId()).isEqualTo(BookUpdateDecoder.TEMPLATE_ID);
        assertThat(headerDecoder.schemaId()).isEqualTo(BookUpdateDecoder.SCHEMA_ID);
        assertThat(headerDecoder.version()).isEqualTo(BookUpdateDecoder.SCHEMA_VERSION);
        assertThat(decoder.seqNum()).isEqualTo(1_001L);
        assertThat(decoder.orderId()).isEqualTo(77_000_123L);
        assertThat(decoder.priceTicks()).isEqualTo(7_080L);
        assertThat(decoder.quantity()).isEqualTo(25L);
        assertThat(decoder.exchangeTimeNanos()).isEqualTo(1_760_000_000_000_000_000L);
        assertThat(decoder.receiveTimeNanos()).isEqualTo(1_760_000_000_000_004_200L);
        assertThat(decoder.securityId()).isEqualTo(42);
        assertThat(decoder.side()).isEqualTo(Side.SELL);
        assertThat(decoder.action()).isEqualTo(UpdateAction.CHANGE);
    }

    @Test
    @DisplayName("FR.MD.05 one decoder flyweight is rewrapped over consecutive messages without copying")
    void decoderIsReusedAcrossMessages() {
        final BookUpdateEncoder encoder = new BookUpdateEncoder();
        final int messageLength = MessageHeaderEncoder.ENCODED_LENGTH + BookUpdateEncoder.BLOCK_LENGTH;
        for (int i = 0; i < 3; i++) {
            encoder.wrapAndApplyHeader(buffer, i * messageLength, headerEncoder)
                .seqNum(i + 1)
                .priceTicks(7_000L + i);
        }

        final BookUpdateDecoder decoder = new BookUpdateDecoder();
        for (int i = 0; i < 3; i++) {
            decoder.wrapAndApplyHeader(buffer, i * messageLength, headerDecoder);
            assertThat(decoder.seqNum()).isEqualTo(i + 1);
            assertThat(decoder.priceTicks()).isEqualTo(7_000L + i);
        }
    }

    @Test
    @DisplayName("Trade round trips with aggressor side")
    void tradeRoundTrip() {
        new TradeEncoder().wrapAndApplyHeader(buffer, 0, headerEncoder)
            .seqNum(2_002L)
            .tradeId(9_001L)
            .priceTicks(7_081L)
            .quantity(10L)
            .exchangeTimeNanos(1_760_000_000_000_000_000L)
            .receiveTimeNanos(1_760_000_000_000_003_100L)
            .securityId(42)
            .aggressorSide(AggressorSide.BUY);

        final TradeDecoder decoder = new TradeDecoder().wrapAndApplyHeader(buffer, 0, headerDecoder);

        assertThat(headerDecoder.templateId()).isEqualTo(TradeDecoder.TEMPLATE_ID);
        assertThat(decoder.seqNum()).isEqualTo(2_002L);
        assertThat(decoder.tradeId()).isEqualTo(9_001L);
        assertThat(decoder.priceTicks()).isEqualTo(7_081L);
        assertThat(decoder.quantity()).isEqualTo(10L);
        assertThat(decoder.exchangeTimeNanos()).isEqualTo(1_760_000_000_000_000_000L);
        assertThat(decoder.receiveTimeNanos()).isEqualTo(1_760_000_000_000_003_100L);
        assertThat(decoder.securityId()).isEqualTo(42);
        assertThat(decoder.aggressorSide()).isEqualTo(AggressorSide.BUY);
    }

    @Test
    @DisplayName("FR.MD.09 InstrumentDefinition round trips with symbol and tick size")
    void instrumentDefinitionRoundTrip() {
        new InstrumentDefinitionEncoder().wrapAndApplyHeader(buffer, 0, headerEncoder)
            .contractMultiplier(1_000L)
            .securityId(42)
            .tickSize256ths(4)
            .symbol("ZNZ6");

        final InstrumentDefinitionDecoder decoder =
            new InstrumentDefinitionDecoder().wrapAndApplyHeader(buffer, 0, headerDecoder);

        assertThat(headerDecoder.templateId()).isEqualTo(InstrumentDefinitionDecoder.TEMPLATE_ID);
        assertThat(decoder.contractMultiplier()).isEqualTo(1_000L);
        assertThat(decoder.securityId()).isEqualTo(42);
        assertThat(decoder.tickSize256ths()).isEqualTo(4);
        assertThat(decoder.symbol()).isEqualTo("ZNZ6");
    }

    @ParameterizedTest
    @EnumSource(value = SourceTag.class, names = "NULL_VAL", mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("Every recording header carries its source tag (CLAUDE.md rule 7)")
    void recordingHeaderCarriesSourceTag(final SourceTag sourceTag) {
        new RecordingHeaderEncoder().wrapAndApplyHeader(buffer, 0, headerEncoder)
            .startTimeNanos(1_760_000_000_000_000_000L)
            .seed(12_345L)
            .instrumentCount(3)
            .sourceTag(sourceTag);

        final RecordingHeaderDecoder decoder =
            new RecordingHeaderDecoder().wrapAndApplyHeader(buffer, 0, headerDecoder);

        assertThat(headerDecoder.templateId()).isEqualTo(RecordingHeaderDecoder.TEMPLATE_ID);
        assertThat(decoder.startTimeNanos()).isEqualTo(1_760_000_000_000_000_000L);
        assertThat(decoder.seed()).isEqualTo(12_345L);
        assertThat(decoder.instrumentCount()).isEqualTo(3);
        assertThat(decoder.sourceTag()).isEqualTo(sourceTag);
    }
}
