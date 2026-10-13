package io.github.sudhanadithya.rates.common.test;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;

/**
 * Asserts that a code path allocates nothing on the heap once warmed up.
 *
 * <p>Uses the HotSpot per thread allocation counter, which counts every byte the current thread
 * allocates, including inside TLABs. The body is warmed up first so the JIT has compiled it,
 * then run {@link #WINDOW_ITERATIONS} times between two counter reads.
 *
 * <p>If that window shows any bytes, a second window of the same size runs straight after, and
 * the assertion fails only if the second window allocates too. A per call allocation shows up
 * in both windows (at least 16 bytes x 10M calls = 160 MB each), so detection stays strict. A
 * one off blip does not repeat and is reported on stderr instead of failing the build.
 *
 * <p>Known source of such blips (found in M2 task 2.3 with JFR and a heap dump under Epsilon):
 * the first time a newly installed C2 method runs, HotSpot can turn a String literal from that
 * class's constant pool into a heap object on the calling thread, for example the message of an
 * argument check in a constructor that never ran. It happens once per class and compile, never
 * per call, and the interpreter and C1 show nothing. Code that only runs on a timer (snapshots,
 * definitions) reaches C2 late, so tests of such code should pass a longer warmup that runs every
 * path many thousands of times before the windows start.
 *
 * <p>Run these tests with {@code ./gradlew allocationTest}, which also uses Epsilon GC.
 */
public final class AllocationAssertions {

    public static final int WARMUP_ITERATIONS = 200_000;
    public static final int WINDOW_ITERATIONS = 10_000_000;

    private static final com.sun.management.ThreadMXBean THREADS =
        (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();

    private AllocationAssertions() {
    }

    /** A body that takes the iteration number, so callers can vary inputs without allocating. */
    @FunctionalInterface
    public interface Body {
        void run(int iteration);
    }

    private static boolean epsilonGcActive() {
        for (final GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            if (gc.getName().startsWith("Epsilon")) {
                return true;
            }
        }
        return false;
    }

    /** Fails with the byte counts when {@code body} allocates in two consecutive windows. */
    public static void assertNoAllocation(final String name, final Body body) {
        assertNoAllocation(name, WARMUP_ITERATIONS, body);
    }

    /**
     * As {@link #assertNoAllocation(String, Body)}, with a longer warmup for bodies whose rarer
     * paths need more calls before the JIT has compiled them with C2.
     */
    public static void assertNoAllocation(final String name, final int warmupIterations, final Body body) {
        if (warmupIterations < WARMUP_ITERATIONS) {
            throw new IllegalArgumentException("warmup must be at least " + WARMUP_ITERATIONS + " iterations");
        }
        if (!THREADS.isThreadAllocatedMemorySupported() || !THREADS.isThreadAllocatedMemoryEnabled()) {
            throw new IllegalStateException("thread allocation counter not available on this JVM");
        }
        if (Boolean.getBoolean("rates.allocationTest") && !epsilonGcActive()) {
            throw new IllegalStateException("allocationTest must run under Epsilon GC; check the JVM flags");
        }

        for (int i = 0; i < warmupIterations; i++) {
            body.run(i);
        }

        final long firstWindow = measureWindow(body, warmupIterations);
        if (firstWindow == 0) {
            return;
        }

        final long secondWindow = measureWindow(body, warmupIterations + WINDOW_ITERATIONS);
        if (secondWindow > 0) {
            throw new AssertionError(name + " allocated " + firstWindow + " then " + secondWindow
                + " bytes in two windows of " + WINDOW_ITERATIONS + " calls (about "
                + (secondWindow / WINDOW_ITERATIONS) + " bytes per call); hot path code must allocate nothing");
        }
        System.err.println(name + ": one off allocation of " + firstWindow
            + " bytes in the first window did not repeat in the second; treated as JIT or TLAB noise");
    }

    /** Runs one window of calls and returns the bytes this thread allocated during it. */
    private static long measureWindow(final Body body, final int firstIteration) {
        // Subtract whatever reading the counter itself allocates (zero on current HotSpot).
        final long calibrate = THREADS.getCurrentThreadAllocatedBytes();
        final long overhead = THREADS.getCurrentThreadAllocatedBytes() - calibrate;

        final long before = THREADS.getCurrentThreadAllocatedBytes();
        final int end = firstIteration + WINDOW_ITERATIONS;
        for (int i = firstIteration; i < end; i++) {
            body.run(i);
        }
        return THREADS.getCurrentThreadAllocatedBytes() - before - overhead;
    }
}
