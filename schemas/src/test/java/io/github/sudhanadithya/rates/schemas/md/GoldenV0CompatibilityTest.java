package io.github.sudhanadithya.rates.schemas.md;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.ByteBuffer;
import java.util.HexFormat;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Proves that recordings written with schema version 0 still decode with the current codecs
 * (CLAUDE.md rule 6). The hex strings were produced by the version 0 encoders at commit 1d1c099
 * and must never be edited: if this test fails, a v0 message's layout changed.
 */
class GoldenV0CompatibilityTest {

    private static final String RECORDING_HEADER_V0 =
        "15000100010000000000b0d4acc66c1839300000000000000400000001";
    private static final String INSTRUMENT_DEFINITION_V0 =
        "1c00020001000000e80300000000000011a40000040000005a4e5a360000000000000000";
    private static final String BOOK_UPDATE_V0 =
        "3600030001000000e903000000000000bbed960400000000a11b00000000000019000000000000000000b0d4acc66c18"
            + "6810b0d4acc66c1811a400000101";
    private static final String TRADE_V0 =
        "3500040001000000d2070000000000002923000000000000a21b0000000000000a000000000000000000b0d4acc66c18"
            + "1c0cb0d4acc66c1811a4000001";

    /** Byte offset of the version field inside the 8 byte message header. */
    private static final int HEADER_VERSION_OFFSET = 6;

    private final MessageHeaderDecoder headerDecoder = new MessageHeaderDecoder();
    private final MessageHeaderEncoder headerEncoder = new MessageHeaderEncoder();

    @Test
    @DisplayName("v0 RecordingHeader bytes decode with the current codecs")
    void recordingHeader() {
        final UnsafeBuffer v0 = bufferOf(RECORDING_HEADER_V0);
        final RecordingHeaderDecoder decoder = new RecordingHeaderDecoder().wrapAndApplyHeader(v0, 0, headerDecoder);

        assertThat(headerDecoder.version()).isZero();
        assertThat(decoder.startTimeNanos()).isEqualTo(1_760_000_000_000_000_000L);
        assertThat(decoder.seed()).isEqualTo(12_345L);
        assertThat(decoder.instrumentCount()).isEqualTo(4);
        assertThat(decoder.sourceTag()).isEqualTo(SourceTag.SYNTHETIC);

        final UnsafeBuffer current = newBuffer();
        new RecordingHeaderEncoder().wrapAndApplyHeader(current, 0, headerEncoder)
            .startTimeNanos(1_760_000_000_000_000_000L).seed(12_345L).instrumentCount(4).sourceTag(SourceTag.SYNTHETIC);
        assertSameLayout(v0, current, RECORDING_HEADER_V0);
    }

    @Test
    @DisplayName("FR.MD.09 v0 InstrumentDefinition bytes decode with the current codecs")
    void instrumentDefinition() {
        final UnsafeBuffer v0 = bufferOf(INSTRUMENT_DEFINITION_V0);
        final InstrumentDefinitionDecoder decoder =
            new InstrumentDefinitionDecoder().wrapAndApplyHeader(v0, 0, headerDecoder);

        assertThat(headerDecoder.version()).isZero();
        assertThat(decoder.contractMultiplier()).isEqualTo(1_000L);
        assertThat(decoder.securityId()).isEqualTo(42_001);
        assertThat(decoder.tickSize256ths()).isEqualTo(4);
        assertThat(decoder.symbol()).isEqualTo("ZNZ6");

        final UnsafeBuffer current = newBuffer();
        new InstrumentDefinitionEncoder().wrapAndApplyHeader(current, 0, headerEncoder)
            .contractMultiplier(1_000L).securityId(42_001).tickSize256ths(4).symbol("ZNZ6");
        assertSameLayout(v0, current, INSTRUMENT_DEFINITION_V0);
    }

    @Test
    @DisplayName("FR.MD.05 v0 BookUpdate bytes decode with the current codecs")
    void bookUpdate() {
        final UnsafeBuffer v0 = bufferOf(BOOK_UPDATE_V0);
        final BookUpdateDecoder decoder = new BookUpdateDecoder().wrapAndApplyHeader(v0, 0, headerDecoder);

        assertThat(headerDecoder.version()).isZero();
        assertThat(decoder.seqNum()).isEqualTo(1_001L);
        assertThat(decoder.orderId()).isEqualTo(77_000_123L);
        assertThat(decoder.priceTicks()).isEqualTo(7_073L);
        assertThat(decoder.quantity()).isEqualTo(25L);
        assertThat(decoder.exchangeTimeNanos()).isEqualTo(1_760_000_000_000_000_000L);
        assertThat(decoder.receiveTimeNanos()).isEqualTo(1_760_000_000_000_004_200L);
        assertThat(decoder.securityId()).isEqualTo(42_001);
        assertThat(decoder.side()).isEqualTo(Side.SELL);
        assertThat(decoder.action()).isEqualTo(UpdateAction.CHANGE);

        final UnsafeBuffer current = newBuffer();
        new BookUpdateEncoder().wrapAndApplyHeader(current, 0, headerEncoder)
            .seqNum(1_001L).orderId(77_000_123L).priceTicks(7_073L).quantity(25L)
            .exchangeTimeNanos(1_760_000_000_000_000_000L).receiveTimeNanos(1_760_000_000_000_004_200L)
            .securityId(42_001).side(Side.SELL).action(UpdateAction.CHANGE);
        assertSameLayout(v0, current, BOOK_UPDATE_V0);
    }

    @Test
    @DisplayName("v0 Trade bytes decode with the current codecs")
    void trade() {
        final UnsafeBuffer v0 = bufferOf(TRADE_V0);
        final TradeDecoder decoder = new TradeDecoder().wrapAndApplyHeader(v0, 0, headerDecoder);

        assertThat(headerDecoder.version()).isZero();
        assertThat(decoder.seqNum()).isEqualTo(2_002L);
        assertThat(decoder.tradeId()).isEqualTo(9_001L);
        assertThat(decoder.priceTicks()).isEqualTo(7_074L);
        assertThat(decoder.quantity()).isEqualTo(10L);
        assertThat(decoder.exchangeTimeNanos()).isEqualTo(1_760_000_000_000_000_000L);
        assertThat(decoder.receiveTimeNanos()).isEqualTo(1_760_000_000_000_003_100L);
        assertThat(decoder.securityId()).isEqualTo(42_001);
        assertThat(decoder.aggressorSide()).isEqualTo(AggressorSide.BUY);

        final UnsafeBuffer current = newBuffer();
        new TradeEncoder().wrapAndApplyHeader(current, 0, headerEncoder)
            .seqNum(2_002L).tradeId(9_001L).priceTicks(7_074L).quantity(10L)
            .exchangeTimeNanos(1_760_000_000_000_000_000L).receiveTimeNanos(1_760_000_000_000_003_100L)
            .securityId(42_001).aggressorSide(AggressorSide.BUY);
        assertSameLayout(v0, current, TRADE_V0);
    }

    /**
     * The current encoder must write byte for byte what v0 wrote, except the header's version
     * field, which now carries the current schema version.
     */
    private static void assertSameLayout(final UnsafeBuffer v0, final UnsafeBuffer current, final String v0Hex) {
        final int length = v0Hex.length() / 2;
        final byte[] expected = new byte[length];
        final byte[] actual = new byte[length];
        v0.getBytes(0, expected);
        current.getBytes(0, actual);

        assertThat(current.getShort(HEADER_VERSION_OFFSET, java.nio.ByteOrder.LITTLE_ENDIAN))
            .isEqualTo((short) MessageHeaderEncoder.SCHEMA_VERSION);
        actual[HEADER_VERSION_OFFSET] = expected[HEADER_VERSION_OFFSET];
        actual[HEADER_VERSION_OFFSET + 1] = expected[HEADER_VERSION_OFFSET + 1];
        assertThat(HexFormat.of().formatHex(actual)).isEqualTo(v0Hex);
    }

    private static UnsafeBuffer bufferOf(final String hex) {
        final UnsafeBuffer buffer = newBuffer();
        buffer.putBytes(0, HexFormat.of().parseHex(hex));
        return buffer;
    }

    private static UnsafeBuffer newBuffer() {
        return new UnsafeBuffer(ByteBuffer.allocateDirect(256));
    }
}
