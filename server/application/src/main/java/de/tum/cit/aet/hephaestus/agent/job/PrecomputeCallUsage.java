package de.tum.cit.aet.hephaestus.agent.job;

import de.tum.cit.aet.hephaestus.agent.catalog.ModelKind;
import de.tum.cit.aet.hephaestus.workspace.spi.DataHandlingTier;
import org.jspecify.annotations.Nullable;

/**
 * One proxied precompute call, as {@link AgentJobRepository#accumulatePrecomputeUsage} adds it to its row.
 *
 * @param dataHandlingTier the tier of the model that served the call; {@code null} when the attempt froze no tier for that
 *     model
 */
public record PrecomputeCallUsage(
        ModelKind modelKind,
        String practiceSlug,
        @Nullable DataHandlingTier dataHandlingTier,
        long inputTokens,
        long outputTokens) {

    /** The stored form of {@link #modelKind()}, for the native query. */
    public String modelKindName() {
        return modelKind.name();
    }

    /** The stored form of {@link #dataHandlingTier()}, for the native query. */
    public @Nullable String dataHandlingTierName() {
        return dataHandlingTier == null ? null : dataHandlingTier.name();
    }
}
