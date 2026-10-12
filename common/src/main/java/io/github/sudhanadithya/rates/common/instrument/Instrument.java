package io.github.sudhanadithya.rates.common.instrument;

import io.github.sudhanadithya.rates.common.price.TickSize;

/**
 * Static definition of one tradable instrument. Built once at startup and never mutated, so it
 * can be read from any thread without synchronization.
 *
 * <p>{@code id} is the platform's dense internal id (0 to N-1) used to index arrays on the hot
 * path. {@code securityId} is the venue's id, which is sparse and only used at the venue edge.
 *
 * <p>Money is a {@code long} count of micro dollars. Cents are not enough: a ZN tick is worth
 * $15.625. The constructor rejects any contract whose tick value is not a whole number of
 * micro dollars, so money math never rounds.
 */
public final class Instrument {

    private static final long MICROS_PER_DOLLAR = 1_000_000L;

    private final int id;
    private final int securityId;
    private final String symbol;
    private final int tickSize256ths;
    private final long contractMultiplier;
    private final long tickValueMicros;

    /**
     * @param id                 dense internal id, 0 or more
     * @param securityId         venue security id
     * @param symbol             venue symbol, for example ZNZ6
     * @param tickSize256ths     tick size in 1/256 of a point, see {@link TickSize}
     * @param contractMultiplier dollars per one full point of price, for example 1000 for ZN
     */
    public Instrument(
        final int id,
        final int securityId,
        final String symbol,
        final int tickSize256ths,
        final long contractMultiplier) {
        if (id < 0) {
            throw new IllegalArgumentException("id must be 0 or more: " + id);
        }
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("symbol is required");
        }
        if (tickSize256ths <= 0 || TickSize.POINT % tickSize256ths != 0) {
            throw new IllegalArgumentException("tick size must divide 256: " + tickSize256ths);
        }
        if (contractMultiplier <= 0) {
            throw new IllegalArgumentException("contract multiplier must be positive: " + contractMultiplier);
        }
        final long tickValueNumerator = contractMultiplier * MICROS_PER_DOLLAR * tickSize256ths;
        if (tickValueNumerator % TickSize.POINT != 0) {
            throw new IllegalArgumentException(
                symbol + ": tick value is not a whole number of micro dollars");
        }

        this.id = id;
        this.securityId = securityId;
        this.symbol = symbol;
        this.tickSize256ths = tickSize256ths;
        this.contractMultiplier = contractMultiplier;
        this.tickValueMicros = tickValueNumerator / TickSize.POINT;
    }

    public int id() {
        return id;
    }

    public int securityId() {
        return securityId;
    }

    public String symbol() {
        return symbol;
    }

    public int tickSize256ths() {
        return tickSize256ths;
    }

    public long contractMultiplier() {
        return contractMultiplier;
    }

    /** Dollar value of one tick on one contract, in micro dollars. */
    public long tickValueMicros() {
        return tickValueMicros;
    }

    /**
     * Value of a price move of {@code ticks} on {@code quantity} contracts, in micro dollars.
     * Allocation free. No overflow check: the result fits in a {@code long} while
     * |ticks x quantity| stays below about 2.9 x 10^11 for ZB, far beyond any real position.
     */
    public long valueMicros(final long ticks, final long quantity) {
        return ticks * quantity * tickValueMicros;
    }

    @Override
    public String toString() {
        return "Instrument{id=" + id + ", securityId=" + securityId + ", symbol=" + symbol
            + ", tickSize256ths=" + tickSize256ths + ", contractMultiplier=" + contractMultiplier + '}';
    }
}
