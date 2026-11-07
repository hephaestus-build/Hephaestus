package de.tum.cit.aet.hephaestus.integration.core.consumer;

import io.nats.client.Message;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/** Broker identity survives redelivery, dead-letter replay, and replacement of a stream's sequence. */
public record BrokerDelivery(String consumer, String stream, long sequence, Instant receivedAt) {
    static BrokerDelivery from(Message message) {
        var metadata = message.metaData();
        if (metadata == null) throw new IllegalArgumentException("JetStream metadata required");
        return new BrokerDelivery(
                metadata.getConsumer(),
                metadata.getStream(),
                metadata.streamSequence(),
                metadata.timestamp().toInstant().truncatedTo(ChronoUnit.MICROS));
    }
}
