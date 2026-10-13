package io.github.sudhanadithya.rates.venue.feed;

import io.aeron.Publication;
import org.agrona.DirectBuffer;

/**
 * Sends feed messages on an Aeron publication (UDP for the A, B and snapshot channels).
 *
 * <p>An exchange never waits for its consumers. With no subscriber connected the message is simply
 * not sent. Under back pressure or an admin action (a log rotation inside Aeron) it retries a few
 * times with a spin wait, then gives up and counts the refusal; the feed handler sees a gap and
 * recovers from a snapshot, exactly as it would after loss on the wire.
 */
public final class AeronFeedChannel implements FeedChannel {

    private final Publication publication;
    private final int maxRetries;

    private long notConnected;
    private long backPressured;
    private long closed;

    /** @param maxRetries extra attempts after a back pressured or admin action result */
    public AeronFeedChannel(final Publication publication, final int maxRetries) {
        if (maxRetries < 0) {
            throw new IllegalArgumentException("max retries must be 0 or more: " + maxRetries);
        }
        this.publication = publication;
        this.maxRetries = maxRetries;
    }

    public long notConnected() {
        return notConnected;
    }

    public long backPressured() {
        return backPressured;
    }

    public long closed() {
        return closed;
    }

    @Override
    public boolean offer(final DirectBuffer buffer, final int offset, final int length) {
        for (int attempt = 0; ; attempt++) {
            final long result = publication.offer(buffer, offset, length);
            if (result > 0) {
                return true;
            }
            if (result == Publication.NOT_CONNECTED) {
                notConnected++;
                return false;
            }
            final boolean transientFailure = result == Publication.BACK_PRESSURED || result == Publication.ADMIN_ACTION;
            if (transientFailure && attempt < maxRetries) {
                Thread.onSpinWait();
                continue;
            }
            if (transientFailure) {
                backPressured++;
            } else {
                closed++;
            }
            return false;
        }
    }
}
