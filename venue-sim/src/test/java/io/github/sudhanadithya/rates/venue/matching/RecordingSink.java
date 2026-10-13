package io.github.sudhanadithya.rates.venue.matching;

import io.github.sudhanadithya.rates.schemas.md.Side;
import java.util.ArrayList;
import java.util.List;

/** Test sink that records each event as one readable line. Allocates freely; tests only. */
final class RecordingSink implements MatchingEventSink {

    final List<String> events = new ArrayList<>();
    final List<Long> times = new ArrayList<>();

    @Override
    public void onOrderAdded(
        final long transactTimeNanos, final int instrumentId, final long orderId, final Side side,
        final long priceTicks, final long quantity) {
        record(transactTimeNanos, "NEW i" + instrumentId + " o" + orderId + " " + side + " " + priceTicks + "x" + quantity);
    }

    @Override
    public void onOrderChanged(
        final long transactTimeNanos, final int instrumentId, final long orderId, final Side side,
        final long priceTicks, final long quantity) {
        record(transactTimeNanos, "CHANGE i" + instrumentId + " o" + orderId + " " + side + " " + priceTicks + "x" + quantity);
    }

    @Override
    public void onOrderDeleted(
        final long transactTimeNanos, final int instrumentId, final long orderId, final Side side,
        final long priceTicks) {
        record(transactTimeNanos, "DELETE i" + instrumentId + " o" + orderId + " " + side + " " + priceTicks);
    }

    @Override
    public void onTrade(
        final long transactTimeNanos, final int instrumentId, final long tradeId, final long priceTicks,
        final long quantity, final Side aggressorSide, final long aggressorOrderId, final long restingOrderId) {
        record(transactTimeNanos, "TRADE i" + instrumentId + " t" + tradeId + " " + priceTicks + "x" + quantity
            + " " + aggressorSide + " o" + aggressorOrderId + " hits o" + restingOrderId);
    }

    /** Returns the events since the last call and clears them. */
    List<String> drain() {
        final List<String> copy = List.copyOf(events);
        events.clear();
        times.clear();
        return copy;
    }

    private void record(final long time, final String line) {
        events.add(line);
        times.add(time);
    }
}
