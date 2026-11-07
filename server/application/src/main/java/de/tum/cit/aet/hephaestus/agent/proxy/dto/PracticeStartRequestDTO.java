package de.tum.cit.aet.hephaestus.agent.proxy.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.NonNull;

public record PracticeStartRequestDTO(
        @NonNull @NotBlank @Size(max = 100) String practiceSlug) {}
