package io.github.sudhanadithya.rates.common.price;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PricesTest {

    @Test
    @DisplayName("FR.MD.06 prices are fixed point ticks per instrument")
    void ticksConvertTo256ths() {
        // ZN 110-165 is 28292 256ths, which is 7073 ZN ticks of 4/256.
        assertThat(Prices.ticksTo256ths(7_073L, TickSize.HALF_32ND)).isEqualTo(28_292L);
        assertThat(Prices.ticksFrom256ths(28_292L, TickSize.HALF_32ND)).isEqualTo(7_073L);

        // The same price is 14146 ZF ticks and 28292 ZT ticks.
        assertThat(Prices.ticksFrom256ths(28_292L, TickSize.QUARTER_32ND)).isEqualTo(14_146L);
        assertThat(Prices.ticksFrom256ths(28_292L, TickSize.EIGHTH_32ND)).isEqualTo(28_292L);
    }

    @Test
    @DisplayName("isOnTick accepts whole ticks and rejects prices between ticks")
    void onTick() {
        assertThat(Prices.isOnTick(28_292L, TickSize.HALF_32ND)).isTrue();
        assertThat(Prices.isOnTick(28_293L, TickSize.HALF_32ND)).isFalse();
        assertThat(Prices.isOnTick(28_288L, TickSize.ONE_32ND)).isTrue();
        assertThat(Prices.isOnTick(28_292L, TickSize.ONE_32ND)).isFalse();
    }

    @Test
    @DisplayName("ticks and 256ths round trip for every tick size")
    void roundTrip() {
        final int[] tickSizes = {TickSize.ONE_32ND, TickSize.HALF_32ND, TickSize.QUARTER_32ND, TickSize.EIGHTH_32ND};
        for (final int tickSize : tickSizes) {
            for (long ticks = 0; ticks < 50_000; ticks++) {
                assertThat(Prices.ticksFrom256ths(Prices.ticksTo256ths(ticks, tickSize), tickSize)).isEqualTo(ticks);
            }
        }
    }
}
