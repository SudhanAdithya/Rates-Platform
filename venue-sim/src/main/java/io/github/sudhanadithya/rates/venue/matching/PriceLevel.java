package io.github.sudhanadithya.rates.venue.matching;

/**
 * All resting orders at one price on one side, as a doubly linked FIFO queue: the head has time
 * priority. Append, remove and reduce are all constant time.
 *
 * <p>Levels are created once per tick of the price band when the book is built and are never
 * freed, so an empty level is just one with a null head.
 */
final class PriceLevel {

    final long priceTicks;

    Order head;
    Order tail;
    long totalQuantity;
    int orderCount;

    PriceLevel(final long priceTicks) {
        this.priceTicks = priceTicks;
    }

    boolean isEmpty() {
        return head == null;
    }

    /** Adds the order at the back of the queue, behind every order already here. */
    void append(final Order order) {
        order.prev = tail;
        order.next = null;
        if (tail == null) {
            head = order;
        } else {
            tail.next = order;
        }
        tail = order;
        totalQuantity += order.quantity;
        orderCount++;
    }

    /** Unlinks the order from wherever it sits in the queue. */
    void remove(final Order order) {
        if (order.prev == null) {
            head = order.next;
        } else {
            order.prev.next = order.next;
        }
        if (order.next == null) {
            tail = order.prev;
        } else {
            order.next.prev = order.prev;
        }
        order.prev = null;
        order.next = null;
        totalQuantity -= order.quantity;
        orderCount--;
    }

    /** Lowers the order's open quantity in place; it keeps its place in the queue. */
    void reduce(final Order order, final long newQuantity) {
        totalQuantity -= order.quantity - newQuantity;
        order.quantity = newQuantity;
    }
}
