package io.github.sudhanadithya.rates.venue.matching;

/**
 * Result codes from {@link MatchingEngine}. Rejects are negative, so a submit can return either
 * the new order id (positive) or a reject in one {@code long} without allocating a result object
 * or throwing.
 */
public final class MatchStatus {

    public static final long OK = 0;
    public static final long UNKNOWN_INSTRUMENT = -1;
    public static final long INVALID_SIDE = -2;
    public static final long INVALID_QUANTITY = -3;
    public static final long PRICE_OUT_OF_BAND = -4;
    public static final long BOOK_FULL = -5;
    public static final long UNKNOWN_ORDER = -6;

    private MatchStatus() {
    }

    public static boolean isReject(final long status) {
        return status < 0;
    }

    /** Returns a constant name for tests and logs. */
    public static String name(final long status) {
        if (status > 0) {
            return "ORDER_ID";
        }
        return switch ((int) status) {
            case 0 -> "OK";
            case -1 -> "UNKNOWN_INSTRUMENT";
            case -2 -> "INVALID_SIDE";
            case -3 -> "INVALID_QUANTITY";
            case -4 -> "PRICE_OUT_OF_BAND";
            case -5 -> "BOOK_FULL";
            case -6 -> "UNKNOWN_ORDER";
            default -> "UNKNOWN_STATUS";
        };
    }
}
