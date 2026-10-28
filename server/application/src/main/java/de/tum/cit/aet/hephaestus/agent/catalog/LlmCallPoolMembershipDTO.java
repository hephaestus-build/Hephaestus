package de.tum.cit.aet.hephaestus.agent.catalog;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/** Tenant-visible membership, without shared activity or cooldown information. */
public record LlmCallPoolMembershipDTO(
        @NonNull Long id,
        @NonNull String slug,
        @NonNull String displayName,
        @Nullable Integer maxConcurrentCalls,
        @Schema(requiredMode = RequiredMode.REQUIRED) int interactiveReserve) {
    public static LlmCallPoolMembershipDTO from(LlmCallPool pool) {
        return new LlmCallPoolMembershipDTO(
                pool.getId(),
                pool.getSlug(),
                pool.getDisplayName(),
                pool.getMaxConcurrentCalls(),
                pool.getInteractiveReserve());
    }
}
