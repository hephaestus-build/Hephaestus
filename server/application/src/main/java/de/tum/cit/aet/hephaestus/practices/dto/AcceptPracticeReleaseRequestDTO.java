package de.tum.cit.aet.hephaestus.practices.dto;

import de.tum.cit.aet.hephaestus.practices.PracticeDefinitionField;
import de.tum.cit.aet.hephaestus.practices.PracticeReleaseChoice;
import jakarta.validation.constraints.NotNull;
import java.util.Map;
import org.jspecify.annotations.NonNull;

public record AcceptPracticeReleaseRequestDTO(
        @NotNull @NonNull Map<PracticeDefinitionField, PracticeReleaseChoice> choices) {}
