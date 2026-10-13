package io.github.sudhanadithya.rates.venue.matching;

import static io.github.sudhanadithya.rates.schemas.md.Side.BUY;
import static io.github.sudhanadithya.rates.schemas.md.Side.SELL;
import static io.github.sudhanadithya.rates.venue.matching.TimeInForce.DAY;
import static io.github.sudhanadithya.rates.venue.matching.TimeInForce.IOC;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.sudhanadithya.rates.common.clock.ManualClock;
import io.github.sudhanadithya.rates.schemas.md.Side;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MatchingEngineTest {

    private static final int ZN = 0;
    private static final long MIN = 1_000;
    private static final int LEVELS = 100;

    private final ManualClock clock = new ManualClock(1_000_000L);
    private final RecordingSink sink = new RecordingSink();
    private MatchingEngine engine;
    private OrderBook book;

    @BeforeEach
    void setUp() {
        engine = new MatchingEngine(clock, sink, 8, 2);
        book = engine.addBook(ZN, MIN, LEVELS);
    }

    @Test
    @DisplayName("FR.SIM.02 a passive limit order rests and is published as NEW")
    void passiveOrderRests() {
        final long id = engine.submit(ZN, BUY, 1_010, 5, DAY);

        assertThat(id).isEqualTo(1);
        assertThat(sink.drain()).containsExactly("NEW i0 o1 BUY 1010x5");
        assertThat(book.bestBidPrice()).isEqualTo(1_010);
        assertThat(book.bestAskPrice()).isEqualTo(OrderBook.NULL_PRICE);
        assertThat(book.quantityAt(BUY, 1_010)).isEqualTo(5);
        assertThat(book.orderCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("FR.SIM.02 better price fills first and each fill is at the resting price")
    void pricePriority() {
        engine.submit(ZN, SELL, 1_012, 5, DAY);
        engine.submit(ZN, SELL, 1_011, 5, DAY);
        sink.drain();

        engine.submit(ZN, BUY, 1_015, 7, DAY);

        assertThat(sink.drain()).containsExactly(
            "TRADE i0 t1 1011x5 BUY o3 hits o2",
            "DELETE i0 o2 SELL 1011",
            "TRADE i0 t2 1012x2 BUY o3 hits o1",
            "CHANGE i0 o1 SELL 1012x3");
        assertThat(book.bestAskPrice()).isEqualTo(1_012);
        assertThat(book.quantityAt(SELL, 1_012)).isEqualTo(3);
        assertThat(book.bestBidPrice()).isEqualTo(OrderBook.NULL_PRICE);
    }

    @Test
    @DisplayName("FR.SIM.02 at the same price the earlier order fills first")
    void timePriority() {
        engine.submit(ZN, BUY, 1_010, 3, DAY);
        engine.submit(ZN, BUY, 1_010, 3, DAY);
        engine.submit(ZN, BUY, 1_010, 3, DAY);
        sink.drain();

        engine.submit(ZN, SELL, 1_010, 4, IOC);

        assertThat(sink.drain()).containsExactly(
            "TRADE i0 t1 1010x3 SELL o4 hits o1",
            "DELETE i0 o1 BUY 1010",
            "TRADE i0 t2 1010x1 SELL o4 hits o2",
            "CHANGE i0 o2 BUY 1010x2");
        assertThat(restingIds()).containsExactly(2L, 3L);
    }

    @Test
    @DisplayName("FR.SIM.02 an aggressor's remainder rests after it sweeps the levels it crosses")
    void aggressorRemainderRests() {
        engine.submit(ZN, SELL, 1_011, 2, DAY);
        sink.drain();

        engine.submit(ZN, BUY, 1_012, 5, DAY);

        assertThat(sink.drain()).containsExactly(
            "TRADE i0 t1 1011x2 BUY o2 hits o1",
            "DELETE i0 o1 SELL 1011",
            "NEW i0 o2 BUY 1012x3");
        assertThat(book.bestBidPrice()).isEqualTo(1_012);
        assertThat(book.bestAskPrice()).isEqualTo(OrderBook.NULL_PRICE);
    }

    @Test
    @DisplayName("FR.SIM.02 an IOC remainder is discarded and never published")
    void iocRemainderDiscarded() {
        engine.submit(ZN, SELL, 1_011, 2, DAY);
        sink.drain();

        final long id = engine.submit(ZN, BUY, 1_011, 5, IOC);

        assertThat(id).isEqualTo(2);
        assertThat(sink.drain()).containsExactly(
            "TRADE i0 t1 1011x2 BUY o2 hits o1",
            "DELETE i0 o1 SELL 1011");
        assertThat(book.orderCount()).isZero();
        assertThat(engine.order(id)).isNull();
    }

    @Test
    @DisplayName("FR.SIM.02 an IOC that doesn't cross publishes nothing")
    void iocNoCross() {
        engine.submit(ZN, SELL, 1_011, 2, DAY);
        sink.drain();

        engine.submit(ZN, BUY, 1_010, 5, IOC);

        assertThat(sink.drain()).isEmpty();
        assertThat(book.quantityAt(SELL, 1_011)).isEqualTo(2);
    }

    @Test
    @DisplayName("FR.SIM.02 a market order sweeps every level and may price outside the band")
    void marketOrderSweeps() {
        engine.submit(ZN, BUY, MIN, 1, DAY);
        engine.submit(ZN, BUY, MIN + LEVELS - 1, 1, DAY);
        sink.drain();

        engine.submitMarket(ZN, SELL, 5);

        assertThat(sink.drain()).containsExactly(
            "TRADE i0 t1 1099x1 SELL o3 hits o2",
            "DELETE i0 o2 BUY 1099",
            "TRADE i0 t2 1000x1 SELL o3 hits o1",
            "DELETE i0 o1 BUY 1000");
        assertThat(book.orderCount()).isZero();
        assertThat(book.bestBidPrice()).isEqualTo(OrderBook.NULL_PRICE);
    }

    @Test
    @DisplayName("FR.SIM.02 cancel publishes DELETE and the next best level takes over")
    void cancelMovesBest() {
        final long first = engine.submit(ZN, SELL, 1_011, 2, DAY);
        engine.submit(ZN, SELL, 1_014, 2, DAY);
        sink.drain();

        assertThat(engine.cancel(ZN, first)).isEqualTo(MatchStatus.OK);

        assertThat(sink.drain()).containsExactly("DELETE i0 o1 SELL 1011");
        assertThat(book.bestAskPrice()).isEqualTo(1_014);
        assertThat(engine.cancel(ZN, first)).isEqualTo(MatchStatus.UNKNOWN_ORDER);
    }

    @Test
    @DisplayName("FR.SIM.02 cancelling from the middle of a queue keeps the others in order")
    void cancelMiddleOfQueue() {
        engine.submit(ZN, BUY, 1_010, 1, DAY);
        final long middle = engine.submit(ZN, BUY, 1_010, 1, DAY);
        engine.submit(ZN, BUY, 1_010, 1, DAY);

        engine.cancel(ZN, middle);

        assertThat(restingIds()).containsExactly(1L, 3L);
        assertThat(book.orderCountAt(BUY, 1_010)).isEqualTo(2);
        assertThat(book.quantityAt(BUY, 1_010)).isEqualTo(2);
    }

    @Test
    @DisplayName("FR.SIM.02 reducing quantity at the same price keeps queue priority")
    void reduceKeepsPriority() {
        final long first = engine.submit(ZN, BUY, 1_010, 5, DAY);
        engine.submit(ZN, BUY, 1_010, 5, DAY);
        sink.drain();

        assertThat(engine.replace(ZN, first, 1_010, 2)).isEqualTo(MatchStatus.OK);

        assertThat(sink.drain()).containsExactly("CHANGE i0 o1 BUY 1010x2");
        assertThat(restingIds()).containsExactly(1L, 2L);
        assertThat(book.quantityAt(BUY, 1_010)).isEqualTo(7);
    }

    @Test
    @DisplayName("FR.SIM.02 increasing quantity loses priority and is published as DELETE then NEW")
    void increaseLosesPriority() {
        final long first = engine.submit(ZN, BUY, 1_010, 5, DAY);
        engine.submit(ZN, BUY, 1_010, 5, DAY);
        sink.drain();

        engine.replace(ZN, first, 1_010, 6);

        assertThat(sink.drain()).containsExactly("DELETE i0 o1 BUY 1010", "NEW i0 o1 BUY 1010x6");
        assertThat(restingIds()).containsExactly(2L, 1L);
    }

    @Test
    @DisplayName("FR.SIM.02 replacing to a crossing price trades as an aggressor")
    void replaceThatCrosses() {
        engine.submit(ZN, SELL, 1_012, 3, DAY);
        final long bid = engine.submit(ZN, BUY, 1_010, 5, DAY);
        sink.drain();

        engine.replace(ZN, bid, 1_012, 5);

        assertThat(sink.drain()).containsExactly(
            "DELETE i0 o2 BUY 1010",
            "TRADE i0 t1 1012x3 BUY o2 hits o1",
            "DELETE i0 o1 SELL 1012",
            "NEW i0 o2 BUY 1012x2");
    }

    @Test
    @DisplayName("FR.SIM.02 a replace that fully fills frees the order")
    void replaceThatFullyFills() {
        engine.submit(ZN, SELL, 1_012, 5, DAY);
        final long bid = engine.submit(ZN, BUY, 1_010, 5, DAY);
        sink.drain();

        engine.replace(ZN, bid, 1_012, 5);

        assertThat(engine.order(bid)).isNull();
        assertThat(book.orderCount()).isZero();
        assertThat(engine.availableOrders()).isEqualTo(8);
    }

    @Test
    @DisplayName("FR.SIM.02 replacing with the same price and quantity publishes nothing")
    void replaceNoChange() {
        final long id = engine.submit(ZN, BUY, 1_010, 5, DAY);
        sink.drain();

        assertThat(engine.replace(ZN, id, 1_010, 5)).isEqualTo(MatchStatus.OK);

        assertThat(sink.drain()).isEmpty();
    }

    @Test
    @DisplayName("FR.SIM.02 invalid commands are rejected with a status and publish nothing")
    void rejects() {
        final long id = engine.submit(ZN, BUY, 1_010, 5, DAY);
        sink.drain();

        assertThat(engine.submit(1, BUY, 1_010, 5, DAY)).isEqualTo(MatchStatus.UNKNOWN_INSTRUMENT);
        assertThat(engine.submit(7, BUY, 1_010, 5, DAY)).isEqualTo(MatchStatus.UNKNOWN_INSTRUMENT);
        assertThat(engine.submit(-1, BUY, 1_010, 5, DAY)).isEqualTo(MatchStatus.UNKNOWN_INSTRUMENT);
        assertThat(engine.submit(ZN, Side.NULL_VAL, 1_010, 5, DAY)).isEqualTo(MatchStatus.INVALID_SIDE);
        assertThat(engine.submit(ZN, null, 1_010, 5, DAY)).isEqualTo(MatchStatus.INVALID_SIDE);
        assertThat(engine.submit(ZN, BUY, 1_010, 0, DAY)).isEqualTo(MatchStatus.INVALID_QUANTITY);
        assertThat(engine.submit(ZN, BUY, MIN - 1, 5, DAY)).isEqualTo(MatchStatus.PRICE_OUT_OF_BAND);
        assertThat(engine.submit(ZN, SELL, MIN + LEVELS, 5, DAY)).isEqualTo(MatchStatus.PRICE_OUT_OF_BAND);
        assertThat(engine.cancel(ZN, 999)).isEqualTo(MatchStatus.UNKNOWN_ORDER);
        assertThat(engine.cancel(1, id)).isEqualTo(MatchStatus.UNKNOWN_INSTRUMENT);
        assertThat(engine.replace(ZN, id, 1_010, -1)).isEqualTo(MatchStatus.INVALID_QUANTITY);
        assertThat(engine.replace(ZN, id, MIN + LEVELS, 5)).isEqualTo(MatchStatus.PRICE_OUT_OF_BAND);
        assertThat(engine.replace(ZN, 999, 1_010, 5)).isEqualTo(MatchStatus.UNKNOWN_ORDER);

        assertThat(sink.drain()).isEmpty();
        // Rejects don't consume order ids.
        assertThat(engine.submit(ZN, BUY, 1_010, 1, DAY)).isEqualTo(2);
    }

    @Test
    @DisplayName("FR.SIM.02 orders on another instrument can't be cancelled through this one")
    void instrumentMismatch() {
        engine.addBook(1, MIN, LEVELS);
        final long id = engine.submit(1, BUY, 1_010, 5, DAY);

        assertThat(engine.cancel(ZN, id)).isEqualTo(MatchStatus.UNKNOWN_ORDER);
        assertThat(engine.replace(ZN, id, 1_010, 1)).isEqualTo(MatchStatus.UNKNOWN_ORDER);
        assertThat(engine.cancel(1, id)).isEqualTo(MatchStatus.OK);
    }

    @Test
    @DisplayName("FR.SIM.02 a full pool rejects resting orders but still allows IOC, and frees on cancel")
    void bookFull() {
        final long[] ids = new long[8];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = engine.submit(ZN, BUY, 1_000 + i, 1, DAY);
        }
        sink.drain();

        assertThat(engine.submit(ZN, BUY, 1_020, 1, DAY)).isEqualTo(MatchStatus.BOOK_FULL);
        assertThat(engine.submit(ZN, SELL, 1_007, 1, IOC)).isPositive();
        assertThat(engine.submit(ZN, BUY, 1_020, 1, DAY)).isPositive();
        engine.cancel(ZN, ids[0]);
        assertThat(engine.submit(ZN, BUY, 1_021, 1, DAY)).isPositive();
        assertThat(engine.availableOrders()).isZero();
    }

    @Test
    @DisplayName("FR.SIM.02 every event of one command carries the clock time read for that command")
    void transactTime() {
        engine.submit(ZN, SELL, 1_011, 1, DAY);
        engine.submit(ZN, SELL, 1_012, 1, DAY);
        sink.drain();
        clock.set(2_000_000L);

        engine.submit(ZN, BUY, 1_013, 3, DAY);

        assertThat(sink.times).hasSize(5).containsOnly(2_000_000L);
    }

    @Test
    @DisplayName("FR.SIM.02 the snapshot walk lists bids then asks, best price first, in queue order")
    void visitOrdersOrder() {
        engine.submit(ZN, BUY, 1_008, 1, DAY);
        engine.submit(ZN, BUY, 1_009, 2, DAY);
        engine.submit(ZN, BUY, 1_009, 3, DAY);
        engine.submit(ZN, SELL, 1_012, 4, DAY);
        engine.submit(ZN, SELL, 1_011, 5, DAY);

        final List<String> seen = new ArrayList<>();
        book.visitOrders((orderId, side, priceTicks, quantity) ->
            seen.add(orderId + " " + side + " " + priceTicks + "x" + quantity));

        assertThat(seen).containsExactly(
            "2 BUY 1009x2", "3 BUY 1009x3", "1 BUY 1008x1", "5 SELL 1011x5", "4 SELL 1012x4");
    }

    @Test
    @DisplayName("Bad startup configuration fails fast")
    void badConfig() {
        assertThatThrownBy(() -> engine.addBook(ZN, MIN, LEVELS)).hasMessageContaining("already exists");
        assertThatThrownBy(() -> engine.addBook(2, MIN, LEVELS)).hasMessageContaining("outside capacity");
        assertThatThrownBy(() -> engine.addBook(1, MIN, 0)).hasMessageContaining("level count");
        assertThatThrownBy(() -> new MatchingEngine(clock, sink, 0, 1)).hasMessageContaining("order capacity");
        assertThatThrownBy(() -> new MatchingEngine(clock, sink, 1, 0)).hasMessageContaining("instrument capacity");
    }

    private List<Long> restingIds() {
        final List<Long> ids = new ArrayList<>();
        book.visitOrders((orderId, side, priceTicks, quantity) -> ids.add(orderId));
        return ids;
    }
}
