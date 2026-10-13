package io.github.sudhanadithya.rates.venue;

import io.github.sudhanadithya.rates.venue.flow.BookConfig;
import io.github.sudhanadithya.rates.venue.flow.FlowConfig;
import java.util.List;

/**
 * Startup settings for one venue session. Task 2.6 loads these from YAML; tests build them directly.
 *
 * @param seed                     seeds the flow and both chaos channels; same seed, same session
 * @param orderCapacity            most orders resting at once across all books
 * @param snapshotIntervalNanos    how often every book is sent on the snapshot channel
 * @param definitionsIntervalNanos how often every instrument definition is sent
 * @param maxOrdersPerSnapshotChunk orders per snapshot message before it splits into chunks
 * @param chaosDelayCapacity       messages each chaos channel can hold while delaying
 * @param flow                     shape of the synthetic order flow
 * @param books                    one per listed instrument
 */
public record VenueConfig(
    long seed,
    int orderCapacity,
    long snapshotIntervalNanos,
    long definitionsIntervalNanos,
    int maxOrdersPerSnapshotChunk,
    int chaosDelayCapacity,
    FlowConfig flow,
    List<BookConfig> books) {

    public VenueConfig {
        if (orderCapacity <= 0 || maxOrdersPerSnapshotChunk <= 0 || chaosDelayCapacity <= 0) {
            throw new IllegalArgumentException("capacities must be positive");
        }
        if (snapshotIntervalNanos <= 0 || definitionsIntervalNanos <= 0) {
            throw new IllegalArgumentException("intervals must be positive");
        }
        books = List.copyOf(books);
        if (books.isEmpty()) {
            throw new IllegalArgumentException("at least one book is required");
        }
    }
}
