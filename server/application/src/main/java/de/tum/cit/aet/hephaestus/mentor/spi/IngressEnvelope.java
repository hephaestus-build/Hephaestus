package de.tum.cit.aet.hephaestus.mentor.spi;

import java.time.Instant;

/** One verified provider event, as the transport hands it over. {@code sourceRef} names the retained provider record; the
 * envelope carries no message text. */
public record IngressEnvelope(
        IngressKind kind,
        ProviderEventKey eventKey,
        VerifiedActor actor,
        Binding binding,
        Audience audience,
        MessageRef message,
        String sourceRef,
        Instant occurredAt,
        Instant receivedAt) {}
