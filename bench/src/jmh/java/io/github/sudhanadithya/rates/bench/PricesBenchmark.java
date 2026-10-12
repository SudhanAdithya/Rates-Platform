package io.github.sudhanadithya.rates.bench;

import io.github.sudhanadithya.rates.common.instrument.Instrument;
import io.github.sudhanadithya.rates.common.price.Prices;
import io.github.sudhanadithya.rates.common.price.TickSize;
import io.github.sudhanadithya.rates.common.price.TreasuryPriceFormat;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;

/**
 * Hot path price math next to the edge only formatter, to show why formatting stays off the
 * hot path. Run with ./gradlew :bench:jmh.
 */
@State(Scope.Thread)
public class PricesBenchmark {

    private final Instrument zn = new Instrument(0, 1, "ZNZ6", TickSize.HALF_32ND, 1_000L);

    /** Varies per call so the JIT can't fold the inputs into constants. */
    private long ticks = 7_073L;

    @Benchmark
    public long ticksToAndFrom256ths() {
        final long price256ths = Prices.ticksTo256ths(++ticks, TickSize.HALF_32ND);
        return Prices.isOnTick(price256ths, TickSize.HALF_32ND)
            ? Prices.ticksFrom256ths(price256ths, TickSize.HALF_32ND)
            : -1;
    }

    @Benchmark
    public long valueMicros() {
        return zn.valueMicros(++ticks & 31, 10);
    }

    /** Edge only: builds a String on every call. */
    @Benchmark
    public String format32nds() {
        return TreasuryPriceFormat.format(Prices.ticksTo256ths(++ticks, TickSize.HALF_32ND));
    }
}
