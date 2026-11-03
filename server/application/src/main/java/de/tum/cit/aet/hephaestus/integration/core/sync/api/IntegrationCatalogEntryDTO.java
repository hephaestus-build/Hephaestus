package de.tum.cit.aet.hephaestus.integration.core.sync.api;

import de.tum.cit.aet.hephaestus.integration.core.events.IntegrationAttentionChangedEvent.Problem;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationState;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * One row of the integrations catalog — every kind the manifest registry knows about, joined against
 * whether (and which) Connection this workspace already has for it. Powers the overview page's
 * connect CTAs, which a bare {@code GET /connections} can't (it only returns existing rows).
 */
@Schema(description = "Integration kind availability + connection status for this workspace")
public record IntegrationCatalogEntryDTO(
        @NonNull @Schema(description = "Integration kind") IntegrationKind kind,

        @NonNull @Schema(description = "Human-readable display name")
        String displayName,

        @NonNull @Schema(description = "Whether this workspace has a (non-UNINSTALLED) connection for this kind")
        Boolean connected,

        @Schema(description = "Connection id, if connected") @Nullable
        Long connectionId,

        @Schema(description = "Connection state, if connected") @Nullable
        IntegrationState connectionState,

        @Schema(
                description =
                        "When the stored credential was first found unreadable with the server's keys; absent while it reads")
        @Nullable
        Instant credentialsUnreadableSince,

        @Schema(description = "A problem with the connection that an admin should act on; absent while there is none")
        @Nullable
        Problem attentionProblem) {}
