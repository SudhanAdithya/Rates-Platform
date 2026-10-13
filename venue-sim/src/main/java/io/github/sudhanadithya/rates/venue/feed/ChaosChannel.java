package io.github.sudhanadithya.rates.venue.feed;

import io.github.sudhanadithya.rates.common.clock.Clock;
import java.util.SplittableRandom;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;

/**
 * Wraps one feed channel and damages it on purpose, so the feed handler's arbitration and
 * recovery can be tested against the failures a real network has (FR.MD.01, FR.MD.02).
 *
 * <p>Switches, all off by default and changeable at any time by the owning thread:
 * <ul>
 *   <li><b>outage</b>: every message is lost, as if the line were cut.</li>
 *   <li><b>drop rate</b>: each message is lost with this probability.</li>
 *   <li><b>reorder rate</b>: a message is held back with this probability and sent just after the
 *       next one, so the two arrive swapped.</li>
 *   <li><b>delay</b>: every message is queued and sent once the clock passes its release time.
 *       {@link #poll} sends what is due. Order is kept, so delay alone never reorders.</li>
 * </ul>
 *
 * <p>Probabilities are integer parts per million, and the random source is seeded, so the same
 * seed and the same switch settings damage exactly the same messages every run. Lost messages are
 * reported to the caller as accepted, because on a real wire the sender never finds out.
 *
 * <p>Held and delayed messages are copied into buffers allocated at startup; when the delay queue
 * is full, further messages are lost and counted as overflow.
 */
public final class ChaosChannel implements FeedChannel {

    public static final int PARTS_PER_MILLION = 1_000_000;

    private final FeedChannel delegate;
    private final Clock clock;
    private final SplittableRandom random;
    private final int maxMessageLength;

    private boolean outage;
    private int dropPpm;
    private int reorderPpm;
    private long delayNanos;

    private final UnsafeBuffer held;
    private int heldLength;

    private final UnsafeBuffer queue;
    private final long[] releaseNanos;
    private final int[] lengths;
    private int queueHead;
    private int queueSize;

    private long passed;
    private long dropped;
    private long reordered;
    private long delayed;
    private long overflowed;
    private long refused;

    /**
     * Startup only.
     *
     * @param maxMessageLength largest message this channel will carry
     * @param delayCapacity    most messages the delay queue can hold at once
     */
    public ChaosChannel(
        final FeedChannel delegate,
        final Clock clock,
        final SplittableRandom random,
        final int maxMessageLength,
        final int delayCapacity) {
        if (maxMessageLength <= 0 || delayCapacity <= 0) {
            throw new IllegalArgumentException("message length and delay capacity must be positive");
        }
        this.delegate = delegate;
        this.clock = clock;
        this.random = random;
        this.maxMessageLength = maxMessageLength;
        this.held = new UnsafeBuffer(new byte[maxMessageLength]);
        this.queue = new UnsafeBuffer(new byte[Math.multiplyExact(maxMessageLength, delayCapacity)]);
        this.releaseNanos = new long[delayCapacity];
        this.lengths = new int[delayCapacity];
    }

    /** Turns a full outage on or off. Turning it on also loses anything held or queued. */
    public void outage(final boolean on) {
        if (on) {
            dropped += queueSize + (heldLength > 0 ? 1 : 0);
            queueSize = 0;
            heldLength = 0;
        }
        outage = on;
    }

    public void dropRatePpm(final int ppm) {
        dropPpm = checkPpm(ppm);
    }

    public void reorderRatePpm(final int ppm) {
        reorderPpm = checkPpm(ppm);
    }

    public void delayNanos(final long nanos) {
        if (nanos < 0) {
            throw new IllegalArgumentException("delay must be 0 or more: " + nanos);
        }
        delayNanos = nanos;
    }

    public boolean outage() {
        return outage;
    }

    /** Messages handed to the wrapped channel. */
    public long passed() {
        return passed;
    }

    /** Messages lost to the outage or the drop rate. */
    public long dropped() {
        return dropped;
    }

    public long reordered() {
        return reordered;
    }

    public long delayed() {
        return delayed;
    }

    /** Messages lost because the delay queue was full. */
    public long overflowed() {
        return overflowed;
    }

    /** Messages the wrapped channel refused. */
    public long refused() {
        return refused;
    }

    @Override
    public boolean offer(final DirectBuffer buffer, final int offset, final int length) {
        if (length > maxMessageLength) {
            refused++;
            return false;
        }
        if (outage || roll(dropPpm)) {
            dropped++;
            return true;
        }
        if (heldLength == 0 && roll(reorderPpm)) {
            held.putBytes(0, buffer, offset, length);
            heldLength = length;
            reordered++;
            return true;
        }
        forward(buffer, offset, length);
        if (heldLength > 0) {
            forward(held, 0, heldLength);
            heldLength = 0;
        }
        return true;
    }

    /**
     * Sends delayed messages whose release time has passed, and a held message once reordering is
     * switched off. Call from the owner's duty cycle. @return messages sent
     */
    public int poll(final long nowNanos) {
        int sent = 0;
        if (heldLength > 0 && reorderPpm == 0) {
            forward(held, 0, heldLength);
            heldLength = 0;
        }
        while (queueSize > 0 && releaseNanos[queueHead] <= nowNanos) {
            send(queue, queueHead * maxMessageLength, lengths[queueHead]);
            queueHead = (queueHead + 1) % lengths.length;
            queueSize--;
            sent++;
        }
        return sent;
    }

    /** Messages waiting in the delay queue. */
    public int queued() {
        return queueSize;
    }

    private void forward(final DirectBuffer buffer, final int offset, final int length) {
        // Once anything is queued, later messages queue behind it even if the delay was switched
        // off, so turning delay off never reorders.
        if (delayNanos == 0 && queueSize == 0) {
            send(buffer, offset, length);
            return;
        }
        if (queueSize == lengths.length) {
            overflowed++;
            return;
        }
        final int slot = (queueHead + queueSize) % lengths.length;
        queue.putBytes(slot * maxMessageLength, buffer, offset, length);
        lengths[slot] = length;
        releaseNanos[slot] = clock.nanos() + delayNanos;
        queueSize++;
        delayed++;
    }

    private void send(final DirectBuffer buffer, final int offset, final int length) {
        if (delegate.offer(buffer, offset, length)) {
            passed++;
        } else {
            refused++;
        }
    }

    /** Rolls only when the switch is on, so an off switch never consumes random numbers. */
    private boolean roll(final int ppm) {
        return ppm > 0 && random.nextInt(PARTS_PER_MILLION) < ppm;
    }

    private static int checkPpm(final int ppm) {
        if (ppm < 0 || ppm > PARTS_PER_MILLION) {
            throw new IllegalArgumentException("rate must be 0 to 1000000 parts per million: " + ppm);
        }
        return ppm;
    }
}
