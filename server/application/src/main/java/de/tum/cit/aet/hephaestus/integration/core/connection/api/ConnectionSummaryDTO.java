package de.tum.cit.aet.hephaestus.integration.core.connection.api;

import de.tum.cit.aet.hephaestus.integration.core.connection.Connection;
import de.tum.cit.aet.hephaestus.integration.core.events.IntegrationAttentionChangedEvent.Problem;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationFamily;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * Wire shape returned by the {@code GET /workspaces/{workspaceSlug}/connections}
 * list + by lifecycle endpoints (suspend, reactivate). Lightweight — no config, no
 * credentials, no audit.
 */
public record ConnectionSummaryDTO(
        Long id,
        IntegrationKind kind,
        IntegrationFamily family,
        IntegrationState state,
        @Nullable String displayName,
        Instant createdAt,
        Instant updatedAt,
        @Nullable Instant credentialsUnreadableSince,
        @Nullable Problem attentionProblem) {
    public static ConnectionSummaryDTO from(Connection c) {
        return new ConnectionSummaryDTO(
                c.getId(),
                c.getKind(),
                c.getKind().family(),
                c.getState(),
                c.getDisplayName(),
                c.getCreatedAt(),
                c.getUpdatedAt(),
                c.credentialsUnreadableSince(),
                c.getAttentionProblem());
    }
}
