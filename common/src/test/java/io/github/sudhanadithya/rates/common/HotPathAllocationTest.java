package io.github.sudhanadithya.rates.common;

import static io.github.sudhanadithya.rates.common.test.AllocationAssertions.assertNoAllocation;

import io.github.sudhanadithya.rates.common.clock.ManualClock;
import io.github.sudhanadithya.rates.common.clock.SystemClock;
import io.github.sudhanadithya.rates.common.instrument.Instrument;
import io.github.sudhanadithya.rates.common.instrument.InstrumentRegistry;
import io.github.sudhanadithya.rates.common.price.Prices;
import io.github.sudhanadithya.rates.common.price.TickSize;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Runs with ./gradlew allocationTest. */
@Tag("allocation")
class HotPathAllocationTest {

    /** Results land here so the JIT can't treat the measured work as dead code. */
    private long sink;

    @Test
    @DisplayName("Prices hot path helpers allocate nothing")
    void prices() {
        assertNoAllocation("Prices", i -> {
            final long price256ths = Prices.ticksTo256ths(7_000L + i, TickSize.HALF_32ND);
            if (Prices.isOnTick(price256ths, TickSize.HALF_32ND)) {
                sink += Prices.ticksFrom256ths(price256ths, TickSize.HALF_32ND);
            }
        });
    }

    @Test
    @DisplayName("FR.STR.03 reading SystemClock and driving ManualClock allocate nothing")
    void clocks() {
        final SystemClock systemClock = new SystemClock();
        final ManualClock manualClock = new ManualClock(0);
        assertNoAllocation("Clock", i -> {
            manualClock.advance(1);
            sink += systemClock.nanos() + manualClock.nanos();
        });
    }

    @Test
    @DisplayName("Instrument registry lookup and value math allocate nothing")
    void instruments() {
        final InstrumentRegistry registry = new InstrumentRegistry(2);
        registry.register(new Instrument(0, 1, "ZNZ6", TickSize.HALF_32ND, 1_000L));
        registry.register(new Instrument(1, 2, "ZFZ6", TickSize.QUARTER_32ND, 1_000L));
        assertNoAllocation("Instrument", i -> {
            final Instrument instrument = registry.get(i & 1);
            sink += instrument.valueMicros(i & 7, 10);
        });
    }
}
