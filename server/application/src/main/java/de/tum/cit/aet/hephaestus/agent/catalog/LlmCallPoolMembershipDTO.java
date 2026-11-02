package de.tum.cit.aet.hephaestus.agent.catalog;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/** Tenant-visible membership, without what the pool learned, its activity or its cooldown. */
public record LlmCallPoolMembershipDTO(
        @NonNull Long id,
        @NonNull String slug,
        @NonNull String displayName,
        @Nullable Integer maxConcurrentCalls) {
    public static LlmCallPoolMembershipDTO from(LlmCallPool pool) {
        return new LlmCallPoolMembershipDTO(
                pool.getId(), pool.getSlug(), pool.getDisplayName(), pool.getMaxConcurrentCalls());
    }
}
