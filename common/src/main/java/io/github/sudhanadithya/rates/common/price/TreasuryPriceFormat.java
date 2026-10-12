package io.github.sudhanadithya.rates.common.price;

import java.math.BigDecimal;

/**
 * Converts prices in 256ths of a point to and from Treasury 32nds notation. Edge only: these
 * methods allocate and throw, so use them at ingest, in reports and in tests, never per message.
 *
 * <p>Notation follows CME: {@code handle-TT[F]}, where TT is 32nds (00 to 31) and the optional
 * digit F is the fraction of a 32nd in eighths, written as its truncated decimal:
 * 1 = 1/8, 2 = 1/4, 3 = 3/8, 5 = 1/2, 6 = 5/8, 7 = 3/4, 8 = 7/8. For example ZN
 * {@code 110-165} is 110 and 16.5/32. The dealer shorthand {@code 110-16+} for a half is also
 * accepted when parsing. Formatting leaves F off when the price is a whole 32nd.
 */
public final class TreasuryPriceFormat {

    /** Fraction digit for each eighth of a 32nd, indexed by eighths (0 to 7). */
    private static final char[] EIGHTHS_DIGIT = {'0', '1', '2', '3', '5', '6', '7', '8'};

    private static final BigDecimal POINT = BigDecimal.valueOf(TickSize.POINT);

    private TreasuryPriceFormat() {
    }

    /** Formats a non negative price in 256ths of a point, for example 28292 as {@code 110-165}. */
    public static String format(final long price256ths) {
        if (price256ths < 0) {
            throw new IllegalArgumentException("negative price: " + price256ths);
        }
        final long handle = price256ths / TickSize.POINT;
        final int remainder = (int) (price256ths % TickSize.POINT);
        final int thirtySeconds = remainder / TickSize.ONE_32ND;
        final int eighths = remainder % TickSize.ONE_32ND;

        final StringBuilder sb = new StringBuilder(10)
            .append(handle)
            .append('-')
            .append((char) ('0' + thirtySeconds / 10))
            .append((char) ('0' + thirtySeconds % 10));
        if (eighths != 0) {
            sb.append(EIGHTHS_DIGIT[eighths]);
        }
        return sb.toString();
    }

    /** Parses 32nds notation such as {@code 110-16}, {@code 110-165} or {@code 110-16+}. */
    public static long parse(final String text) {
        final int dash = text.indexOf('-');
        if (dash <= 0) {
            throw invalid(text);
        }
        final String fraction = text.substring(dash + 1);
        if (fraction.length() < 2 || fraction.length() > 3) {
            throw invalid(text);
        }

        final long handle = parseDigits(text, text.substring(0, dash));
        final int thirtySeconds = (int) parseDigits(text, fraction.substring(0, 2));
        if (thirtySeconds > 31) {
            throw invalid(text);
        }

        int eighths = 0;
        if (fraction.length() == 3) {
            final char digit = fraction.charAt(2);
            eighths = digit == '+' ? 4 : indexOfEighthsDigit(digit);
            if (eighths < 0) {
                throw invalid(text);
            }
        }
        return handle * TickSize.POINT + (long) thirtySeconds * TickSize.ONE_32ND + eighths;
    }

    /** Exact decimal points for reports, for example 28292 as 110.515625. */
    public static BigDecimal toPoints(final long price256ths) {
        // 256 is a power of two, so the quotient always has a finite decimal expansion.
        return BigDecimal.valueOf(price256ths).divide(POINT);
    }

    private static int indexOfEighthsDigit(final char digit) {
        for (int i = 0; i < EIGHTHS_DIGIT.length; i++) {
            if (EIGHTHS_DIGIT[i] == digit) {
                return i;
            }
        }
        return -1;
    }

    private static long parseDigits(final String text, final String digits) {
        if (digits.isEmpty()) {
            throw invalid(text);
        }
        for (int i = 0; i < digits.length(); i++) {
            if (digits.charAt(i) < '0' || digits.charAt(i) > '9') {
                throw invalid(text);
            }
        }
        return Long.parseLong(digits);
    }

    private static IllegalArgumentException invalid(final String text) {
        return new IllegalArgumentException("not a Treasury 32nds price: '" + text + "'");
    }
}
