package io.github.sudhanadithya.rates.common.instrument;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.sudhanadithya.rates.common.price.TickSize;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class InstrumentTest {

    @ParameterizedTest(name = "{0} tick is {3} micro dollars")
    @CsvSource({
        "ZBZ6, 8, 1000, 31250000",   // $31.25
        "ZNZ6, 4, 1000, 15625000",   // $15.625
        "ZFZ6, 2, 1000,  7812500",   // $7.8125
        "ZTZ6, 1, 2000,  7812500",   // $7.8125 (ZT is $200,000 face, $2000 per point)
    })
    @DisplayName("tick value is exact in micro dollars for CME Treasury futures")
    void tickValues(final String symbol, final int tickSize256ths, final long multiplier, final long tickValueMicros) {
        final Instrument instrument = new Instrument(0, 1, symbol, tickSize256ths, multiplier);
        assertThat(instrument.tickValueMicros()).isEqualTo(tickValueMicros);
    }

    @Test
    @DisplayName("value of a price move scales with ticks and quantity")
    void valueMicros() {
        final Instrument zn = new Instrument(0, 1, "ZNZ6", TickSize.HALF_32ND, 1_000L);
        // 3 ticks on 10 contracts: 30 x $15.625 = $468.75
        assertThat(zn.valueMicros(3, 10)).isEqualTo(468_750_000L);
        assertThat(zn.valueMicros(-3, 10)).isEqualTo(-468_750_000L);
    }

    @Test
    @DisplayName("rejects a contract whose tick value is not a whole number of micro dollars")
    void rejectsInexactTickValue() {
        // $1 per point / 256 = $0.00390625, which is 3906.25 micro dollars.
        assertThatThrownBy(() -> new Instrument(0, 1, "BAD", 1, 1L))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("micro dollars");
    }

    @Test
    @DisplayName("rejects invalid definitions")
    void rejectsInvalid() {
        assertThatThrownBy(() -> new Instrument(-1, 1, "ZNZ6", 4, 1_000L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Instrument(0, 1, " ", 4, 1_000L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Instrument(0, 1, "ZNZ6", 3, 1_000L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Instrument(0, 1, "ZNZ6", 0, 1_000L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Instrument(0, 1, "ZNZ6", 4, 0L)).isInstanceOf(IllegalArgumentException.class);
    }
}
