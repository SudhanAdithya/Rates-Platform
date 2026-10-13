package io.github.sudhanadithya.rates.venue.feed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.sudhanadithya.rates.common.clock.ManualClock;
import io.github.sudhanadithya.rates.venue.CapturingChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ChaosChannelTest {

    private final ManualClock clock = new ManualClock(1_000);
    private final CapturingChannel wire = new CapturingChannel();
    private final ChaosChannel chaos = new ChaosChannel(wire, clock, new SplittableRandom(1), 16, 4);
    private final UnsafeBuffer buffer = new UnsafeBuffer(new byte[16]);

    @Test
    @DisplayName("FR.MD.01 with every switch off messages pass through unchanged")
    void passThrough() {
        send(1, 2, 3);
        assertThat(received()).containsExactly(1, 2, 3);
        assertThat(chaos.passed()).isEqualTo(3);
    }

    @Test
    @DisplayName("FR.MD.01 an outage loses everything until it is switched off")
    void outage() {
        send(1);
        chaos.outage(true);
        send(2, 3);
        chaos.outage(false);
        send(4);

        assertThat(received()).containsExactly(1, 4);
        assertThat(chaos.dropped()).isEqualTo(2);
    }

    @Test
    @DisplayName("FR.MD.02 the drop rate loses about that share of messages, the same ones for the same seed")
    void dropRate() {
        chaos.dropRatePpm(100_000);
        for (int i = 0; i < 100_000; i++) {
            send(i & 0x7F);
        }
        assertThat(chaos.dropped()).isBetween(9_000L, 11_000L);
        assertThat(chaos.passed() + chaos.dropped()).isEqualTo(100_000);

        final ChaosChannel again = new ChaosChannel(new CapturingChannel(), clock, new SplittableRandom(1), 16, 4);
        again.dropRatePpm(100_000);
        for (int i = 0; i < 100_000; i++) {
            buffer.putByte(0, (byte) (i & 0x7F));
            again.offer(buffer, 0, 1);
        }
        assertThat(again.dropped()).isEqualTo(chaos.dropped());
    }

    @Test
    @DisplayName("FR.MD.02 reordering swaps a held message with the next one")
    void reorder() {
        chaos.reorderRatePpm(ChaosChannel.PARTS_PER_MILLION);
        send(1, 2, 3, 4);
        assertThat(received()).containsExactly(2, 1, 4, 3);
        assertThat(chaos.reordered()).isEqualTo(2);
    }

    @Test
    @DisplayName("FR.MD.02 a held message is released once reordering is switched off")
    void reorderFlushedWhenOff() {
        chaos.reorderRatePpm(ChaosChannel.PARTS_PER_MILLION);
        send(1);
        assertThat(received()).isEmpty();

        chaos.reorderRatePpm(0);
        chaos.poll(clock.nanos());

        assertThat(received()).containsExactly(1);
    }

    @Test
    @DisplayName("FR.MD.02 delay holds messages until their release time and keeps their order")
    void delay() {
        chaos.delayNanos(500);
        send(1);
        clock.advance(100);
        send(2);

        assertThat(chaos.poll(clock.nanos())).isZero();
        clock.set(1_500);
        assertThat(chaos.poll(clock.nanos())).isEqualTo(1);
        assertThat(received()).containsExactly(1);

        chaos.delayNanos(0);
        send(3);
        clock.set(1_600);
        chaos.poll(clock.nanos());
        assertThat(received()).containsExactly(1, 2, 3);
    }

    @Test
    @DisplayName("FR.MD.02 a full delay queue loses further messages and counts them")
    void delayOverflow() {
        chaos.delayNanos(10);
        send(1, 2, 3, 4, 5, 6);
        assertThat(chaos.overflowed()).isEqualTo(2);

        clock.advance(10);
        chaos.poll(clock.nanos());
        assertThat(received()).containsExactly(1, 2, 3, 4);
    }

    @Test
    @DisplayName("An outage also loses held and delayed messages")
    void outageClearsQueues() {
        chaos.delayNanos(10);
        send(1, 2);
        chaos.outage(true);
        clock.advance(10);
        chaos.poll(clock.nanos());

        assertThat(received()).isEmpty();
        assertThat(chaos.dropped()).isEqualTo(2);
        assertThat(chaos.queued()).isZero();
    }

    @Test
    @DisplayName("Refusals from the wire and oversized messages are counted, not hidden")
    void refusals() {
        wire.accept = false;
        send(1);
        assertThat(chaos.offer(new UnsafeBuffer(new byte[17]), 0, 17)).isFalse();

        assertThat(chaos.refused()).isEqualTo(2);
        assertThat(chaos.passed()).isZero();
    }

    @Test
    @DisplayName("Bad switch values are rejected")
    void badSwitches() {
        assertThatThrownBy(() -> chaos.dropRatePpm(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> chaos.reorderRatePpm(1_000_001)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> chaos.delayNanos(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    private void send(final int... values) {
        for (final int value : values) {
            buffer.putByte(0, (byte) value);
            chaos.offer(buffer, 0, 1);
        }
    }

    private List<Integer> received() {
        final List<Integer> values = new ArrayList<>();
        for (final byte[] message : wire.messages) {
            values.add((int) message[0]);
        }
        return values;
    }
}
