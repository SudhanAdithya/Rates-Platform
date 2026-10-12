package io.github.sudhanadithya.rates.common.price;

/**
 * Tick sizes of CME Treasury futures, as a count of 1/256 of a point.
 *
 * <p>1/256 of a point is the smallest increment any Treasury future trades in (an eighth of a
 * 32nd), so every instrument's tick is a whole number of 256ths and all price math stays in
 * {@code long} integers.
 */
public final class TickSize {

    /** One full point of price, in 256ths. */
    public static final int POINT = 256;

    /** One 32nd of a point: ZB (Treasury bond). */
    public static final int ONE_32ND = 8;

    /** Half of a 32nd: ZN (10 year note). */
    public static final int HALF_32ND = 4;

    /** Quarter of a 32nd: ZF (5 year note). */
    public static final int QUARTER_32ND = 2;

    /** Eighth of a 32nd: ZT (2 year note). */
    public static final int EIGHTH_32ND = 1;

    private TickSize() {
    }
}
