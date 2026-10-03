package de.tum.cit.aet.hephaestus.integration.core.sync.api;

import de.tum.cit.aet.hephaestus.integration.core.spi.SyncResourceState;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

@Schema(description = "One synced resource (repository / channel / collection) — unified read-model row")
public record SyncResourceStateDTO(
        @NonNull @Schema(description = "The integration's own row id")
        Long id,

        @NonNull @Schema(description = "Vendor-side identifier")
        String externalId,

        @NonNull @Schema(description = "Display name") String name,
        @Schema(description = "Resource kind") SyncResourceState.@NonNull Type type,

        @NonNull @Schema(description = "Integration-defined status string")
        String state,

        @Schema(description = "Last successful sync timestamp across all entity classes") @Nullable
        Instant lastSyncedAt,

        @Schema(description = "Headline mirrored item count — the rollup of `counts`") @Nullable
        Long itemCount,

        @NonNull
        @Schema(
                description = "Per-entity-class breakdown behind itemCount. One entry per class the integration "
                        + "actually mirrors — 6 for an SCM repository, 1 for a Slack channel or Outline collection. "
                        + "Never null; empty when the resource has never synced.")
        List<SyncResourceCountDTO> counts,

        @Schema(description = "Vendor-reported upstream count, if cheaply available") @Nullable
        Long upstreamCount,

        @Schema(description = "Last sync error, if any") @Nullable
        String lastError,

        @Schema(description = "Per-resource backfill horizon") @Nullable
        Instant backfillCompletedThrough,

        @Schema(description = "Per-resource backfill percent") @Nullable
        Integer backfillPercent) {
    public static SyncResourceStateDTO from(SyncResourceState state) {
        return new SyncResourceStateDTO(
                state.id(),
                state.externalId(),
                state.name(),
                state.type(),
                state.state(),
                state.lastSyncedAt(),
                state.itemCount(),
                state.counts().stream().map(SyncResourceCountDTO::from).toList(),
                state.upstreamCount(),
                state.lastError(),
                state.backfillCompletedThrough(),
                state.backfillPercent());
    }
}
