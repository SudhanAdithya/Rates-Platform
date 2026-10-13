package io.github.sudhanadithya.rates.venue;

import io.github.sudhanadithya.rates.venue.feed.FeedChannel;
import java.util.ArrayList;
import java.util.List;
import org.agrona.DirectBuffer;

/** Test channel that keeps a copy of every message. Allocates; tests only. */
public final class CapturingChannel implements FeedChannel {

    public final List<byte[]> messages = new ArrayList<>();
    public boolean accept = true;

    @Override
    public boolean offer(final DirectBuffer buffer, final int offset, final int length) {
        if (!accept) {
            return false;
        }
        final byte[] copy = new byte[length];
        buffer.getBytes(offset, copy);
        messages.add(copy);
        return true;
    }
}
