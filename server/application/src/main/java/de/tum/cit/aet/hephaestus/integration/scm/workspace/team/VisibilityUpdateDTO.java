package de.tum.cit.aet.hephaestus.integration.scm.workspace.team;

import jakarta.validation.constraints.NotNull;

/** Whether a team or repository is hidden from workspace activity. */
public record VisibilityUpdateDTO(@NotNull Boolean hidden) {}
