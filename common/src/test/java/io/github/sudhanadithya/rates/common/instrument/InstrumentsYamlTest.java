package io.github.sudhanadithya.rates.common.instrument;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.sudhanadithya.rates.common.price.TickSize;
import java.io.StringReader;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class InstrumentsYamlTest {

    /** Tests run with the module directory as working directory. */
    private static final Path REPO_CONFIG = Path.of("..", "config", "instruments.yaml");

    @Test
    @DisplayName("the repository's config/instruments.yaml loads ZN, ZF, ZT and ZB with CME tick values")
    void repositoryConfigLoads() {
        final InstrumentRegistry registry = InstrumentsYaml.load(REPO_CONFIG);

        assertThat(registry.count()).isEqualTo(4);
        assertThat(registry.get(0).symbol()).isEqualTo("ZNZ6");
        assertThat(registry.get(0).tickSize256ths()).isEqualTo(TickSize.HALF_32ND);
        assertThat(registry.findBySymbol("ZNZ6").tickValueMicros()).isEqualTo(15_625_000L);
        assertThat(registry.findBySymbol("ZFZ6").tickValueMicros()).isEqualTo(7_812_500L);
        assertThat(registry.findBySymbol("ZTZ6").tickValueMicros()).isEqualTo(7_812_500L);
        assertThat(registry.findBySymbol("ZBZ6").tickValueMicros()).isEqualTo(31_250_000L);
    }

    @Test
    @DisplayName("parses every field and sizes the registry to the highest id")
    void parsesFields() {
        final InstrumentRegistry registry = parse("""
            instruments:
              - id: 2
                securityId: 7
                symbol: ZNZ6
                tickSize256ths: 4
                contractMultiplier: 1000
            """);

        final Instrument zn = registry.get(2);
        assertThat(zn.id()).isEqualTo(2);
        assertThat(zn.securityId()).isEqualTo(7);
        assertThat(zn.symbol()).isEqualTo("ZNZ6");
        assertThat(zn.tickSize256ths()).isEqualTo(4);
        assertThat(zn.contractMultiplier()).isEqualTo(1_000L);
        assertThat(registry.capacity()).isEqualTo(3);
        assertThat(registry.get(0)).isNull();
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
        "unknown key        | {id: 0, securityId: 1, symbol: ZNZ6, tickSize: 4, contractMultiplier: 1000}           | unknown key 'tickSize'",
        "missing field      | {id: 0, securityId: 1, symbol: ZNZ6, tickSize256ths: 4}                               | missing 'contractMultiplier'",
        "string where int   | {id: 0, securityId: 1, symbol: ZNZ6, tickSize256ths: 4, contractMultiplier: lots}     | must be an integer",
        "int out of range   | {id: 0, securityId: 9999999999, symbol: ZNZ6, tickSize256ths: 4, contractMultiplier: 1000} | out of int range",
        "inexact tick value | {id: 0, securityId: 1, symbol: BAD, tickSize256ths: 1, contractMultiplier: 1}         | micro dollars",
    })
    @DisplayName("rejects bad instrument entries with a message naming the entry")
    void rejectsBadEntries(final String name, final String entry, final String message) {
        assertThatThrownBy(() -> parse("instruments:\n  - " + entry + "\n"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("instruments[0]")
            .hasMessageContaining(message);
    }

    @Test
    @DisplayName("rejects duplicate ids and duplicate symbols")
    void rejectsDuplicates() {
        assertThatThrownBy(() -> parse("""
            instruments:
              - {id: 0, securityId: 1, symbol: ZNZ6, tickSize256ths: 4, contractMultiplier: 1000}
              - {id: 0, securityId: 2, symbol: ZFZ6, tickSize256ths: 2, contractMultiplier: 1000}
            """)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("already registered");

        assertThatThrownBy(() -> parse("""
            instruments:
              - {id: 0, securityId: 1, symbol: ZNZ6, tickSize256ths: 4, contractMultiplier: 1000}
              - {id: 1, securityId: 2, symbol: ZNZ6, tickSize256ths: 4, contractMultiplier: 1000}
            """)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("symbol already registered");
    }

    @Test
    @DisplayName("rejects an empty or misshapen document")
    void rejectsBadDocuments() {
        assertThatThrownBy(() -> parse("instruments: []"))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("non empty list");
        assertThatThrownBy(() -> parse("- just a list"))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("must be a mapping");
        assertThatThrownBy(() -> parse("instrument: []"))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("unknown key 'instrument'");
    }

    @Test
    @DisplayName("refuses YAML tags that would construct arbitrary classes")
    void rejectsUnsafeTags() {
        assertThatThrownBy(() -> parse("instruments: !!java.io.File [\"/etc/passwd\"]"))
            .isInstanceOf(RuntimeException.class);
    }

    private static InstrumentRegistry parse(final String yaml) {
        return InstrumentsYaml.parse(new StringReader(yaml), "test.yaml");
    }
}
