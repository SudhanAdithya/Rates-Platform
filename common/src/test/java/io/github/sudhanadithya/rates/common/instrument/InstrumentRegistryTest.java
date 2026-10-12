package io.github.sudhanadithya.rates.common.instrument;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.sudhanadithya.rates.common.price.TickSize;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class InstrumentRegistryTest {

    private static final Instrument ZN = new Instrument(0, 42_000_123, "ZNZ6", TickSize.HALF_32ND, 1_000L);
    private static final Instrument ZF = new Instrument(1, 42_000_456, "ZFZ6", TickSize.QUARTER_32ND, 1_000L);

    @Test
    @DisplayName("looks up instruments by dense internal id")
    void lookupById() {
        final InstrumentRegistry registry = new InstrumentRegistry(4);
        registry.register(ZN);
        registry.register(ZF);

        assertThat(registry.get(0)).isSameAs(ZN);
        assertThat(registry.get(1)).isSameAs(ZF);
        assertThat(registry.get(2)).isNull();
        assertThat(registry.count()).isEqualTo(2);
        assertThat(registry.capacity()).isEqualTo(4);
    }

    @Test
    @DisplayName("finds instruments by symbol for startup and tooling")
    void lookupBySymbol() {
        final InstrumentRegistry registry = new InstrumentRegistry(4);
        registry.register(ZN);

        assertThat(registry.findBySymbol("ZNZ6")).isSameAs(ZN);
        assertThat(registry.findBySymbol("ZBZ6")).isNull();
    }

    @Test
    @DisplayName("rejects duplicate ids, duplicate symbols and ids beyond capacity")
    void rejectsBadRegistrations() {
        final InstrumentRegistry registry = new InstrumentRegistry(2);
        registry.register(ZN);

        assertThatThrownBy(() -> registry.register(new Instrument(0, 7, "ZBZ6", TickSize.ONE_32ND, 1_000L)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> registry.register(new Instrument(1, 7, "ZNZ6", TickSize.HALF_32ND, 1_000L)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> registry.register(new Instrument(2, 7, "ZTZ6", TickSize.EIGHTH_32ND, 2_000L)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(registry.count()).isEqualTo(1);
    }
}
