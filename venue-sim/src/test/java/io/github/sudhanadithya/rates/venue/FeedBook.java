package io.github.sudhanadithya.rates.venue;

import io.github.sudhanadithya.rates.schemas.md.BookUpdateDecoder;
import io.github.sudhanadithya.rates.schemas.md.MessageHeaderDecoder;
import io.github.sudhanadithya.rates.schemas.md.Side;
import io.github.sudhanadithya.rates.schemas.md.SnapshotFullRefreshDecoder;
import io.github.sudhanadithya.rates.schemas.md.UpdateAction;
import io.github.sudhanadithya.rates.venue.matching.OrderBook;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.agrona.concurrent.UnsafeBuffer;

/**
 * A plain, allocating book built only from feed messages, used to prove the feed carries enough to
 * rebuild the venue's book exactly, queue order included. Not the real feed handler (task 2.4).
 */
public final class FeedBook {

    private record Entry(long orderId, Side side, long priceTicks, long quantity) {
    }

    /** Orders per security id, in arrival order; a CHANGE replaces in place, keeping position. */
    private final Map<Integer, LinkedHashMap<Long, Entry>> books = new HashMap<>();
    private final MessageHeaderDecoder header = new MessageHeaderDecoder();
    private final BookUpdateDecoder update = new BookUpdateDecoder();
    private final SnapshotFullRefreshDecoder snapshot = new SnapshotFullRefreshDecoder();

    /** Applies a BookUpdate. Other incremental messages are ignored. */
    public void applyIncremental(final byte[] message) {
        final UnsafeBuffer buffer = new UnsafeBuffer(message);
        header.wrap(buffer, 0);
        if (header.templateId() != BookUpdateDecoder.TEMPLATE_ID) {
            return;
        }
        update.wrapAndApplyHeader(buffer, 0, header);
        final LinkedHashMap<Long, Entry> book = books.computeIfAbsent(update.securityId(), k -> new LinkedHashMap<>());
        final long orderId = update.orderId();
        final UpdateAction action = update.action();
        if (action == UpdateAction.NEW) {
            if (book.putIfAbsent(orderId, new Entry(orderId, update.side(), update.priceTicks(), update.quantity())) != null) {
                throw new IllegalStateException("NEW for an order already on the book: " + orderId);
            }
        } else if (action == UpdateAction.CHANGE) {
            if (book.replace(orderId, new Entry(orderId, update.side(), update.priceTicks(), update.quantity())) == null) {
                throw new IllegalStateException("CHANGE for an unknown order: " + orderId);
            }
        } else if (book.remove(orderId) == null) {
            throw new IllegalStateException("DELETE for an unknown order: " + orderId);
        }
    }

    /** Replaces a book with a snapshot chunk's orders (chunk 0 clears it first). @return lastSeqNum */
    public long applySnapshot(final byte[] message) {
        final UnsafeBuffer buffer = new UnsafeBuffer(message);
        header.wrap(buffer, 0);
        snapshot.wrapAndApplyHeader(buffer, 0, header);
        final long lastSeqNum = snapshot.lastSeqNum();
        final int securityId = snapshot.securityId();
        if (snapshot.chunkIndex() == 0) {
            books.put(securityId, new LinkedHashMap<>());
        }
        final LinkedHashMap<Long, Entry> book = books.get(securityId);
        for (final SnapshotFullRefreshDecoder.OrdersDecoder order : snapshot.orders()) {
            book.put(order.orderId(), new Entry(order.orderId(), order.side(), order.priceTicks(), order.quantity()));
        }
        return lastSeqNum;
    }

    /** Orders in priority order, formatted like {@link #describe(OrderBook)}. */
    public List<String> describe(final int securityId) {
        final List<Entry> entries = new ArrayList<>(books.getOrDefault(securityId, new LinkedHashMap<>()).values());
        // Stable sort: bids first by price descending, then asks by price ascending; arrival order within a price.
        entries.sort(Comparator.<Entry>comparingInt(e -> e.side() == Side.BUY ? 0 : 1)
            .thenComparingLong(e -> e.side() == Side.BUY ? -e.priceTicks() : e.priceTicks()));
        final List<String> lines = new ArrayList<>();
        for (final Entry e : entries) {
            lines.add(e.orderId() + " " + e.side() + " " + e.priceTicks() + "x" + e.quantity());
        }
        return lines;
    }

    /** The venue's book in the same format. */
    public static List<String> describe(final OrderBook book) {
        final List<String> lines = new ArrayList<>();
        book.visitOrders((orderId, side, priceTicks, quantity) -> lines.add(orderId + " " + side + " " + priceTicks + "x" + quantity));
        return lines;
    }
}
