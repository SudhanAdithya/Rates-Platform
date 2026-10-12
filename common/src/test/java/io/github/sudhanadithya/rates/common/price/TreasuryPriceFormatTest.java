package io.github.sudhanadithya.rates.common.price;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class TreasuryPriceFormatTest {

    @ParameterizedTest(name = "{0} is {1} 256ths")
    @CsvSource({
        "110-16,  28288",   // whole 32nd: 110*256 + 16*8
        "110-161, 28289",   // ZT: plus 1/8 of a 32nd
        "110-162, 28290",   // ZF: plus 1/4
        "110-163, 28291",   // plus 3/8
        "110-165, 28292",   // ZN: plus 1/2
        "110-166, 28293",   // plus 5/8
        "110-167, 28294",   // plus 3/4
        "110-168, 28295",   // plus 7/8
        "110-00,  28160",
        "110-31,  28408",
        "0-00,    0",
    })
    @DisplayName("parses and formats CME 32nds notation")
    void knownPrices(final String text, final long price256ths) {
        assertThat(TreasuryPriceFormat.parse(text)).isEqualTo(price256ths);
        assertThat(TreasuryPriceFormat.format(price256ths)).isEqualTo(text);
    }

    @Test
    @DisplayName("parses the dealer shorthand + as half a 32nd")
    void plusMeansHalf() {
        assertThat(TreasuryPriceFormat.parse("110-16+")).isEqualTo(TreasuryPriceFormat.parse("110-165"));
    }

    @Test
    @DisplayName("FR.MD.06 32nds notation round trips on every 256th from 100 to 130 points")
    void roundTripsEveryTick() {
        for (long price = 100L * TickSize.POINT; price < 130L * TickSize.POINT; price++) {
            assertThat(TreasuryPriceFormat.parse(TreasuryPriceFormat.format(price))).isEqualTo(price);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"110", "-16", "110-", "110-1", "110-32", "110-164", "110-169", "110-1655", "a10-16", "110-1x"})
    @DisplayName("rejects malformed prices")
    void rejectsMalformed(final String text) {
        assertThatThrownBy(() -> TreasuryPriceFormat.parse(text)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("rejects negative prices when formatting")
    void rejectsNegative() {
        assertThatThrownBy(() -> TreasuryPriceFormat.format(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("converts to exact decimal points for reports")
    void toPoints() {
        assertThat(TreasuryPriceFormat.toPoints(28_292L)).isEqualByComparingTo(new BigDecimal("110.515625"));
        assertThat(TreasuryPriceFormat.toPoints(28_289L)).isEqualByComparingTo(new BigDecimal("110.50390625"));
    }
}
