package de.tum.cit.aet.hephaestus.agent.catalog;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import org.jspecify.annotations.NonNull;

/** The whole membership: a listed model joins the pool, also from another pool; an unlisted member leaves it. */
@Schema(description = "The models whose calls share a pool")
public record UpdateLlmCallPoolModelsRequestDTO(
        @NonNull @NotNull @Schema(description = "Ids of instance models")
        List<@NonNull Long> instanceModelIds,

        @NonNull @NotNull @Schema(description = "Ids of models on workspaces' own connections")
        List<@NonNull Long> workspaceModelIds) {}
