package de.tum.cit.aet.hephaestus.integration.core.events;

import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import java.time.Instant;

/** An observed connection problem or its recovery, never a generic connect/disconnect notification. */
public record IntegrationAttentionChangedEvent(
        long connectionId,
        long workspaceId,
        IntegrationKind kind,
        Problem problem,
        boolean recovered,
        long revision,
        Instant occurredAt) {
    public enum Problem {
        CREDENTIAL_REVOKED,
        PROVIDER_SUSPENDED
    }
}
