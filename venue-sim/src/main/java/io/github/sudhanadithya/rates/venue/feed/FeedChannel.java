package io.github.sudhanadithya.rates.venue.feed;

import org.agrona.DirectBuffer;

/**
 * Where the venue writes one encoded feed message. In production this is an Aeron publication
 * ({@link AeronFeedChannel}); {@link ChaosChannel} wraps one to lose, reorder or delay messages;
 * tests capture into memory. Implementations must copy the bytes before returning, because the
 * caller reuses its buffer for the next message.
 */
@FunctionalInterface
public interface FeedChannel {

    /**
     * @return true if the message was handed on, false if the transport refused it (no
     *     subscriber, back pressure). A venue doesn't wait for slow consumers, so callers only count
     *     refusals.
     */
    boolean offer(DirectBuffer buffer, int offset, int length);
}
