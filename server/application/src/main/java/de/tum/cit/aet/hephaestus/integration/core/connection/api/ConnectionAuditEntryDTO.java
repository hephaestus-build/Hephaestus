package de.tum.cit.aet.hephaestus.integration.core.connection.api;

import de.tum.cit.aet.hephaestus.integration.core.connection.ConnectionAudit;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * Audit-log entry returned by {@code GET /workspaces/{workspaceSlug}/connections/{id}/audit}.
 *
 * <p>Lean projection of {@link ConnectionAudit} — the entity carries a back-reference to
 * {@code Connection} that this DTO omits.
 */
public record ConnectionAuditEntryDTO(
        String eventType,
        @Nullable IntegrationState fromState,
        @Nullable IntegrationState toState,
        String actorKind,
        @Nullable String actorRef,
        @Nullable Long actorAccountId,
        @Nullable String correlationId,
        Instant occurredAt) {
    public static ConnectionAuditEntryDTO from(ConnectionAudit audit) {
        return new ConnectionAuditEntryDTO(
                audit.getEventType(),
                audit.getFromState(),
                audit.getToState(),
                audit.getActorKind(),
                audit.getActorRef(),
                audit.getActorAccountId(),
                audit.getCorrelationId(),
                audit.getOccurredAt());
    }
}
