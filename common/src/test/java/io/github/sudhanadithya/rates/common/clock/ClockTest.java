package io.github.sudhanadithya.rates.common.clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ClockTest {

    private static final long START = 1_760_000_000_000_000_000L;

    @Test
    @DisplayName("FR.STR.03 ManualClock drives time only when told to")
    void manualClockMovesOnlyWhenDriven() {
        final ManualClock clock = new ManualClock(START);
        assertThat(clock.nanos()).isEqualTo(START);
        assertThat(clock.nanos()).isEqualTo(START);

        clock.advance(1_500);
        assertThat(clock.nanos()).isEqualTo(START + 1_500);

        clock.set(START + 10_000);
        assertThat(clock.nanos()).isEqualTo(START + 10_000);
    }

    @Test
    @DisplayName("FR.STR.03 ManualClock rejects time going backwards")
    void manualClockNeverGoesBackwards() {
        final ManualClock clock = new ManualClock(START);
        clock.set(START + 100);

        assertThatThrownBy(() -> clock.set(START + 99)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> clock.advance(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThat(clock.nanos()).isEqualTo(START + 100);
    }

    @Test
    @DisplayName("ManualClock allows setting the same time twice")
    void manualClockAllowsSameTime() {
        final ManualClock clock = new ManualClock(START);
        clock.set(START);
        clock.advance(0);
        assertThat(clock.nanos()).isEqualTo(START);
    }

    @Test
    @DisplayName("SystemClock is close to wall clock time")
    void systemClockTracksWallClock() {
        final Instant wall = Instant.now();
        final long wallNanos = wall.getEpochSecond() * 1_000_000_000L + wall.getNano();

        assertThat(new SystemClock().nanos())
            .isBetween(wallNanos - TimeUnit.SECONDS.toNanos(1), wallNanos + TimeUnit.SECONDS.toNanos(1));
    }

    @Test
    @DisplayName("SystemClock never goes backwards")
    void systemClockIsMonotonic() {
        final Clock clock = new SystemClock();
        long previous = clock.nanos();
        for (int i = 0; i < 100_000; i++) {
            final long now = clock.nanos();
            assertThat(now).isGreaterThanOrEqualTo(previous);
            previous = now;
        }
    }
}
