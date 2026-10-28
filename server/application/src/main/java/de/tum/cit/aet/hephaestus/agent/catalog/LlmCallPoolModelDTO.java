package de.tum.cit.aet.hephaestus.agent.catalog;

import de.tum.cit.aet.hephaestus.agent.usage.UsageFundingSource;
import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * A model that an instance admin can put into a pool: an instance model, or a model on a workspace's own connection.
 * The host lets the admin see models that likely reach the same provider; it never puts a model into a pool.
 */
@Schema(description = "A model and the pool its calls share")
public record LlmCallPoolModelDTO(
        @NonNull @Schema(description = "INSTANCE for the instance catalog, WORKSPACE for a workspace's own model")
        UsageFundingSource scope,

        @NonNull @Schema(description = "Model id within its scope")
        Long modelId,

        @NonNull @Schema(description = "Human-readable name")
        String displayName,

        @NonNull @Schema(description = "Connection's display name")
        String connectionDisplayName,

        @Nullable @Schema(description = "The connection's host; null when its URL has none")
        String host,

        @Nullable @Schema(description = "Slug of the workspace that owns the model; null for an instance model")
        String workspaceSlug,

        @Nullable @Schema(description = "The pool the model is in; null for none")
        Long callPoolId) {}
