package de.tum.cit.aet.hephaestus.integration.core.sync.push;

import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.NonNull;

/**
 * One {@code sync} event of the live stream: an invalidation hint, not data. A client refetches the REST resource of
 * the scope and connection instead of trusting a carried copy, so the hint names no vendor and no resource shape.
 * Every scope is emitted: {@code activity} on each processed webhook event, the others on job and resource state
 * changes.
 *
 * @param scope the scope that changed, as {@code SyncStateChangedEvent.Scope#wireValue()} names it
 * @param connectionId the connection the hint concerns
 */
@Schema(description = "One event of the sync live stream: which sync state of a connection to read again")
public record SyncEventHintDTO(
        @NonNull @Schema(
                        description = "The sync state that changed",
                        allowableValues = {"job", "resources", "connection", "activity"})
                String scope,
        @NonNull @Schema(description = "The connection whose sync state changed") Long connectionId) {}
