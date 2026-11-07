package de.tum.cit.aet.hephaestus.agent.proxy.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.NonNull;

public record PracticeStartResponseDTO(
        @NonNull String practiceSlug,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean current) {}
