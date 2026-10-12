package io.github.sudhanadithya.rates.common.instrument;

/**
 * All instruments, indexed by their dense internal id so a hot path lookup is one array load.
 *
 * <p>Filled at startup by one thread and then only read. Hand the registry to other threads
 * before starting them: {@link Thread#start()} gives the happens-before edge that makes the
 * array contents visible without locks.
 */
public final class InstrumentRegistry {

    private final Instrument[] byId;
    private int count;

    public InstrumentRegistry(final int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive: " + capacity);
        }
        this.byId = new Instrument[capacity];
    }

    /** Adds an instrument at startup. Rejects ids out of range and duplicate ids or symbols. */
    public void register(final Instrument instrument) {
        final int id = instrument.id();
        if (id >= byId.length) {
            throw new IllegalArgumentException("id " + id + " is outside capacity " + byId.length);
        }
        if (byId[id] != null) {
            throw new IllegalArgumentException("id " + id + " already registered as " + byId[id].symbol());
        }
        if (findBySymbol(instrument.symbol()) != null) {
            throw new IllegalArgumentException("symbol already registered: " + instrument.symbol());
        }
        byId[id] = instrument;
        count++;
    }

    /** Hot path lookup. Returns null when no instrument has this id; never throws for valid ids. */
    public Instrument get(final int id) {
        return byId[id];
    }

    public int capacity() {
        return byId.length;
    }

    public int count() {
        return count;
    }

    /** Linear scan by symbol, for startup and tooling only. Returns null when absent. */
    public Instrument findBySymbol(final String symbol) {
        for (final Instrument instrument : byId) {
            if (instrument != null && instrument.symbol().equals(symbol)) {
                return instrument;
            }
        }
        return null;
    }
}
