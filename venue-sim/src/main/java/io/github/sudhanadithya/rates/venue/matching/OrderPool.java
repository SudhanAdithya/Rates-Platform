package io.github.sudhanadithya.rates.venue.matching;

/**
 * Fixed size stack of preallocated {@link Order} objects. Acquire and release are constant time
 * and never allocate. Reuse is last in, first out, which is deterministic and keeps recently used
 * orders warm in cache.
 */
final class OrderPool {

    private final Order[] free;
    private int freeCount;

    /** Startup only: allocates every order up front. */
    OrderPool(final int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("order capacity must be positive: " + capacity);
        }
        free = new Order[capacity];
        for (int i = 0; i < capacity; i++) {
            free[i] = new Order();
        }
        freeCount = capacity;
    }

    int available() {
        return freeCount;
    }

    int capacity() {
        return free.length;
    }

    /** Returns a cleared order, or null when every order is in use. */
    Order acquire() {
        if (freeCount == 0) {
            return null;
        }
        final Order order = free[--freeCount];
        free[freeCount] = null;
        return order;
    }

    void release(final Order order) {
        order.reset();
        free[freeCount++] = order;
    }
}
