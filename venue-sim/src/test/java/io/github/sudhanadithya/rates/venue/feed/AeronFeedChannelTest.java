package io.github.sudhanadithya.rates.venue.feed;

import static org.assertj.core.api.Assertions.assertThat;

import io.aeron.Aeron;
import io.aeron.ExclusivePublication;
import io.aeron.Subscription;
import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;
import io.aeron.logbuffer.FragmentHandler;
import io.github.sudhanadithya.rates.common.clock.ManualClock;
import io.github.sudhanadithya.rates.venue.CapturingChannel;
import io.github.sudhanadithya.rates.venue.Venue;
import io.github.sudhanadithya.rates.venue.VenueTestSupport;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** The venue's A and B incremental channels over real Aeron UDP, with an embedded media driver. */
class AeronFeedChannelTest {

    private static final String CHANNEL_A = "aeron:udp?endpoint=localhost:24456";
    private static final String CHANNEL_B = "aeron:udp?endpoint=localhost:24457";
    private static final int STREAM_ID = 1001;

    @TempDir
    Path dir;

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("FR.MD.01 A and B over Aeron UDP deliver the same messages with contiguous sequence numbers")
    void abOverUdp() {
        final MediaDriver.Context driverContext = new MediaDriver.Context()
            .aeronDirectoryName(dir.resolve("aeron").toString())
            .threadingMode(ThreadingMode.SHARED)
            .dirDeleteOnStart(true)
            .dirDeleteOnShutdown(true);
        try (MediaDriver driver = MediaDriver.launch(driverContext);
             Aeron aeron = Aeron.connect(new Aeron.Context().aeronDirectoryName(driver.aeronDirectoryName()));
             Subscription subA = aeron.addSubscription(CHANNEL_A, STREAM_ID);
             Subscription subB = aeron.addSubscription(CHANNEL_B, STREAM_ID);
             ExclusivePublication pubA = aeron.addExclusivePublication(CHANNEL_A, STREAM_ID);
             ExclusivePublication pubB = aeron.addExclusivePublication(CHANNEL_B, STREAM_ID)) {

            final AeronFeedChannel channelA = new AeronFeedChannel(pubA, 100);
            final AeronFeedChannel channelB = new AeronFeedChannel(pubB, 100);
            awaitConnected(pubA, pubB, subA, subB);

            final List<byte[]> receivedA = new ArrayList<>();
            final List<byte[]> receivedB = new ArrayList<>();
            final FragmentHandler handlerA = (buffer, offset, length, header) -> receivedA.add(copy(buffer, offset, length));
            final FragmentHandler handlerB = (buffer, offset, length, header) -> receivedB.add(copy(buffer, offset, length));

            final ManualClock clock = new ManualClock(1_000_000_000L);
            final Venue venue = new Venue(VenueTestSupport.config(61), VenueTestSupport.registry(), clock,
                channelA, channelB, new CapturingChannel(), new CapturingChannel());
            for (int step = 0; step < 2_000; step++) {
                clock.advance(100_000);
                venue.doWork();
                subA.poll(handlerA, 256);
                subB.poll(handlerB, 256);
            }
            final long last = venue.incremental().lastSeqNum();
            while (receivedA.size() < last || receivedB.size() < last) {
                subA.poll(handlerA, 256);
                subB.poll(handlerB, 256);
                Thread.onSpinWait();
            }

            assertThat(last).isGreaterThan(5_000);
            assertThat(venue.incremental().refusedA()).isZero();
            assertThat(venue.incremental().refusedB()).isZero();
            for (int i = 0; i < last; i++) {
                assertThat(VenueTestSupport.seqNum(receivedA.get(i))).isEqualTo(i + 1);
                assertThat(Arrays.equals(receivedA.get(i), receivedB.get(i))).isTrue();
            }
        }
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("With no subscriber the venue doesn't wait; it counts the message as not connected")
    void noSubscriber() {
        final MediaDriver.Context driverContext = new MediaDriver.Context()
            .aeronDirectoryName(dir.resolve("aeron2").toString())
            .threadingMode(ThreadingMode.SHARED)
            .dirDeleteOnStart(true)
            .dirDeleteOnShutdown(true);
        try (MediaDriver driver = MediaDriver.launch(driverContext);
             Aeron aeron = Aeron.connect(new Aeron.Context().aeronDirectoryName(driver.aeronDirectoryName()));
             ExclusivePublication publication = aeron.addExclusivePublication(CHANNEL_A, STREAM_ID)) {
            final AeronFeedChannel channel = new AeronFeedChannel(publication, 3);

            assertThat(channel.offer(new UnsafeBuffer(new byte[8]), 0, 8)).isFalse();
            assertThat(channel.notConnected()).isEqualTo(1);
        }
    }

    private static void awaitConnected(
        final ExclusivePublication pubA, final ExclusivePublication pubB, final Subscription subA, final Subscription subB) {
        while (!pubA.isConnected() || !pubB.isConnected() || !subA.isConnected() || !subB.isConnected()) {
            Thread.onSpinWait();
        }
    }

    private static byte[] copy(final org.agrona.DirectBuffer buffer, final int offset, final int length) {
        final byte[] bytes = new byte[length];
        buffer.getBytes(offset, bytes);
        return bytes;
    }
}
