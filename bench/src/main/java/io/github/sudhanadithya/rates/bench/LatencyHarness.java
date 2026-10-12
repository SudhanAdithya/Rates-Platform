package io.github.sudhanadithya.rates.bench;

import io.github.sudhanadithya.rates.common.clock.Clock;
import io.github.sudhanadithya.rates.common.clock.SystemClock;
import io.github.sudhanadithya.rates.schemas.md.BookUpdateDecoder;
import io.github.sudhanadithya.rates.schemas.md.BookUpdateEncoder;
import io.github.sudhanadithya.rates.schemas.md.MessageHeaderDecoder;
import io.github.sudhanadithya.rates.schemas.md.MessageHeaderEncoder;
import io.github.sudhanadithya.rates.schemas.md.Side;
import io.github.sudhanadithya.rates.schemas.md.UpdateAction;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.HdrHistogram.Histogram;
import org.agrona.concurrent.UnsafeBuffer;

/**
 * End to end latency harness: times a stage with clock reads around it, records each sample in
 * an HdrHistogram and prints p50, p99, p99.9 and max with the machine spec beside them.
 *
 * <p>M1 stage: SBE encode then decode of one BookUpdate. M2 replaces it with the real pipeline
 * (venue-sim to feed handler) behind the same measurement code.
 *
 * <p>Each sample includes the cost of one clock read, so the harness also times an empty stage
 * and prints that as the clock overhead, plus the clock's step size. On Apple Silicon
 * {@code System.nanoTime} advances in steps of about 42 ns (a 24 MHz counter), so a stage that
 * takes a few nanoseconds reads as 0 or 42; JMH is the right tool at that scale. On Linux x86
 * the step is about 1 ns.
 *
 * <p>Usage: {@code ./gradlew :bench:run --args="[measuredIterations] [warmupIterations]"}
 */
public final class LatencyHarness {

    private static final long HIGHEST_TRACKABLE_NANOS = TimeUnit.SECONDS.toNanos(10);
    private static final int SIGNIFICANT_DIGITS = 3;

    private final Clock clock;
    private final UnsafeBuffer buffer = new UnsafeBuffer(ByteBuffer.allocateDirect(256));
    private final MessageHeaderEncoder headerEncoder = new MessageHeaderEncoder();
    private final MessageHeaderDecoder headerDecoder = new MessageHeaderDecoder();
    private final BookUpdateEncoder encoder = new BookUpdateEncoder();
    private final BookUpdateDecoder decoder = new BookUpdateDecoder();

    /** Decoded values land here so the JIT can't remove the stage as dead code. */
    private long sink;

    LatencyHarness(final Clock clock) {
        this.clock = clock;
    }

    public static void main(final String[] args) {
        final int measured = args.length > 0 ? Integer.parseInt(args[0]) : 10_000_000;
        final int warmup = args.length > 1 ? Integer.parseInt(args[1]) : 1_000_000;

        final LatencyHarness harness = new LatencyHarness(new SystemClock());
        final Histogram overhead = new Histogram(HIGHEST_TRACKABLE_NANOS, SIGNIFICANT_DIGITS);
        final Histogram stage = new Histogram(HIGHEST_TRACKABLE_NANOS, SIGNIFICANT_DIGITS);

        harness.runEmpty(overhead, warmup);
        harness.runStage(stage, warmup);
        overhead.reset();
        stage.reset();

        harness.runEmpty(overhead, measured);
        harness.runStage(stage, measured);

        printMachineSpec();
        System.out.printf("Samples: %,d measured after %,d warmup, units ns%n%n", measured, warmup);
        printRow("stage", "p50", "p99", "p99.9", "max");
        printRow("clock overhead (empty stage)", overhead);
        printRow("SBE BookUpdate encode+decode", stage);
        System.out.println();
        final long clockStep = overhead.getMinNonZeroValue();
        System.out.println("Clock step: about " + clockStep + " ns (smallest nonzero delta between two reads).");
        System.out.println("Stages shorter than a few clock steps can't be resolved here; use ./gradlew :bench:jmh for those.");
        System.out.println("sink=" + harness.sink + " (printed so the JIT keeps the stage)");
    }

    /** Measures two back to back clock reads with nothing between them. */
    private void runEmpty(final Histogram histogram, final int iterations) {
        for (int i = 0; i < iterations; i++) {
            final long start = clock.nanos();
            histogram.recordValue(clock.nanos() - start);
        }
    }

    private void runStage(final Histogram histogram, final int iterations) {
        for (int i = 0; i < iterations; i++) {
            final long start = clock.nanos();
            encodeAndDecode(i);
            histogram.recordValue(clock.nanos() - start);
        }
    }

    private void encodeAndDecode(final int i) {
        encoder.wrapAndApplyHeader(buffer, 0, headerEncoder)
            .seqNum(i)
            .orderId(1_000_000L + i)
            .priceTicks(7_000L + (i & 63))
            .quantity(1 + (i & 15))
            .exchangeTimeNanos(i)
            .receiveTimeNanos(i + 1)
            .securityId(42)
            .side((i & 1) == 0 ? Side.BUY : Side.SELL)
            .action(UpdateAction.NEW);

        decoder.wrapAndApplyHeader(buffer, 0, headerDecoder);
        sink += decoder.priceTicks() + decoder.quantity() + decoder.side().value();
    }

    private static void printRow(final String name, final Histogram h) {
        printRow(name,
            Long.toString(h.getValueAtPercentile(50.0)),
            Long.toString(h.getValueAtPercentile(99.0)),
            Long.toString(h.getValueAtPercentile(99.9)),
            Long.toString(h.getMaxValue()));
    }

    private static void printRow(final String name, final String p50, final String p99, final String p999, final String max) {
        System.out.printf("%-32s %8s %8s %8s %10s%n", name, p50, p99, p999, max);
    }

    /** Prints the machine and JVM settings that every latency number must be read alongside. */
    private static void printMachineSpec() {
        final Runtime runtime = Runtime.getRuntime();
        final List<String> gcs = new ArrayList<>();
        for (final GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            gcs.add(gc.getName());
        }
        System.out.println("Machine");
        System.out.println("  CPU:   " + cpuModel() + ", " + runtime.availableProcessors() + " logical cores");
        System.out.println("  OS:    " + System.getProperty("os.name") + " " + System.getProperty("os.version")
            + " (" + System.getProperty("os.arch") + ")");
        System.out.println("  JVM:   " + System.getProperty("java.vm.name") + " " + System.getProperty("java.vm.version"));
        System.out.println("  Heap:  " + runtime.maxMemory() / (1024 * 1024) + " MB max, GC " + String.join(", ", gcs));
        System.out.println("  Flags: " + String.join(" ", ManagementFactory.getRuntimeMXBean().getInputArguments()));
        System.out.println("  Note:  no core pinning or CPU isolation yet; numbers include OS scheduling noise");
        System.out.println();
    }

    private static String cpuModel() {
        try {
            final Path cpuinfo = Path.of("/proc/cpuinfo");
            if (Files.isReadable(cpuinfo)) {
                for (final String line : Files.readAllLines(cpuinfo)) {
                    if (line.startsWith("model name")) {
                        return line.substring(line.indexOf(':') + 1).trim();
                    }
                }
            }
            final Process process = new ProcessBuilder("sysctl", "-n", "machdep.cpu.brand_string").start();
            try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                final String line = reader.readLine();
                if (line != null && !line.isBlank()) {
                    return line.trim();
                }
            }
        } catch (final Exception e) {
            // Fall through: the CPU model is informational only.
        }
        return "unknown CPU";
    }
}
