package de.tum.cit.aet.hephaestus.workspace.dto;

import jakarta.validation.constraints.NotNull;
import org.jspecify.annotations.NonNull;

public record UpdateWorkspacePublicActivityRequestDTO(
        @NonNull @NotNull Boolean publicActivityEnabled,
        @NonNull @NotNull Boolean allowSearchEngines) {}
