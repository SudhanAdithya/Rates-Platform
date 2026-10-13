package io.github.sudhanadithya.rates.common.instrument;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;

/**
 * Loads instrument definitions from YAML into an {@link InstrumentRegistry}. Startup only: it
 * allocates freely and throws on any problem, so a bad file stops the process before it trades.
 *
 * <p>Expected shape (see {@code config/instruments.yaml}):
 * <pre>
 * instruments:
 *   - id: 0
 *     securityId: 42001
 *     symbol: ZNZ6
 *     tickSize256ths: 4
 *     contractMultiplier: 1000
 * </pre>
 * Unknown keys are rejected, so a typo such as {@code tickSize} fails loudly instead of being ignored.
 */
public final class InstrumentsYaml {

    private static final Set<String> TOP_LEVEL_KEYS = Set.of("instruments");
    private static final Set<String> INSTRUMENT_KEYS =
        Set.of("id", "securityId", "symbol", "tickSize256ths", "contractMultiplier");

    private InstrumentsYaml() {
    }

    /** Reads and validates a YAML file. */
    public static InstrumentRegistry load(final Path file) {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return parse(reader, file.toString());
        } catch (final IOException e) {
            throw new IllegalArgumentException("cannot read instruments file " + file, e);
        }
    }

    /**
     * Parses YAML from a reader. {@code source} names the input in error messages.
     * The registry's capacity is the highest id plus one.
     */
    public static InstrumentRegistry parse(final Reader reader, final String source) {
        // Default settings: no custom tags or arbitrary class construction, so loading is safe.
        final Object document = new Load(LoadSettings.builder().setLabel(source).build()).loadFromReader(reader);
        final Map<?, ?> root = asMap(document, source, "document");
        rejectUnknownKeys(root, TOP_LEVEL_KEYS, source, "document");

        final Object list = root.get("instruments");
        if (!(list instanceof List<?> entries) || entries.isEmpty()) {
            throw new IllegalArgumentException(source + ": 'instruments' must be a non empty list");
        }

        final List<Instrument> instruments = new ArrayList<>(entries.size());
        int maxId = -1;
        for (int i = 0; i < entries.size(); i++) {
            final String where = "instruments[" + i + "]";
            final Map<?, ?> entry = asMap(entries.get(i), source, where);
            rejectUnknownKeys(entry, INSTRUMENT_KEYS, source, where);
            try {
                final Instrument instrument = new Instrument(
                    intValue(entry, "id", source, where),
                    intValue(entry, "securityId", source, where),
                    stringValue(entry, "symbol", source, where),
                    intValue(entry, "tickSize256ths", source, where),
                    longValue(entry, "contractMultiplier", source, where));
                instruments.add(instrument);
                maxId = Math.max(maxId, instrument.id());
            } catch (final IllegalArgumentException e) {
                throw new IllegalArgumentException(source + ": " + where + ": " + e.getMessage(), e);
            }
        }

        final InstrumentRegistry registry = new InstrumentRegistry(maxId + 1);
        for (final Instrument instrument : instruments) {
            try {
                registry.register(instrument);
            } catch (final IllegalArgumentException e) {
                throw new IllegalArgumentException(source + ": " + instrument.symbol() + ": " + e.getMessage(), e);
            }
        }
        return registry;
    }

    private static Map<?, ?> asMap(final Object value, final String source, final String where) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException(source + ": " + where + " must be a mapping");
        }
        return map;
    }

    private static void rejectUnknownKeys(
        final Map<?, ?> map, final Set<String> allowed, final String source, final String where) {
        for (final Object key : map.keySet()) {
            if (!allowed.contains(String.valueOf(key))) {
                throw new IllegalArgumentException(
                    source + ": " + where + ": unknown key '" + key + "', expected one of " + allowed.stream().sorted().toList());
            }
        }
    }

    private static Object required(final Map<?, ?> map, final String key, final String source, final String where) {
        final Object value = map.get(key);
        if (value == null) {
            throw new IllegalArgumentException(source + ": " + where + ": missing '" + key + "'");
        }
        return value;
    }

    private static long longValue(final Map<?, ?> map, final String key, final String source, final String where) {
        final Object value = required(map, key, source, where);
        // SnakeYAML returns Integer, Long or BigInteger for YAML integers depending on size.
        if (value instanceof Integer || value instanceof Long) {
            return ((Number) value).longValue();
        }
        throw new IllegalArgumentException(source + ": " + where + ": '" + key + "' must be an integer, got " + value);
    }

    private static int intValue(final Map<?, ?> map, final String key, final String source, final String where) {
        final long value = longValue(map, key, source, where);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(source + ": " + where + ": '" + key + "' is out of int range: " + value);
        }
        return (int) value;
    }

    private static String stringValue(final Map<?, ?> map, final String key, final String source, final String where) {
        final Object value = required(map, key, source, where);
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException(source + ": " + where + ": '" + key + "' must be a string, got " + value);
        }
        return text;
    }
}
