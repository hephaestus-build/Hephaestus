package de.tum.cit.aet.hephaestus.testconfig;

import io.nats.client.Message;
import io.nats.client.impl.NatsJetStreamMetaData;
import io.nats.client.impl.NatsMessage;
import java.time.Instant;

/** Broker identity with no live acknowledgement channel. */
public final class InboxTestMessages {
    private InboxTestMessages() {}

    public static Message delivery(String consumer, long sequence, Instant at) {
        long nanos = Math.addExact(Math.multiplyExact(at.getEpochSecond(), 1_000_000_000L), at.getNano());
        return new NatsMessage(
                "outline.subscription.document",
                "$JS.ACK.outline." + consumer + ".1." + sequence + ".1." + nanos + ".0",
                null,
                new byte[0]) {
            @Override
            public boolean isJetStream() {
                return true;
            }

            @Override
            public NatsJetStreamMetaData metaData() {
                return new NatsJetStreamMetaData(this);
            }
        };
    }
}
