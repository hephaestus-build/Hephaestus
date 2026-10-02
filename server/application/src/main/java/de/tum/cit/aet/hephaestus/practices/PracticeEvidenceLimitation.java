package de.tum.cit.aet.hephaestus.practices;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Objects;
import org.jspecify.annotations.NonNull;

@Schema(
        description = "Known claim that the selected evidence cannot support even when every requirement passes",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
public record PracticeEvidenceLimitation(
        @NonNull
        @Pattern(regexp = "[A-Z][A-Z0-9_]{2,63}", message = "Code must use uppercase snake case")
        @Schema(description = "Stable machine-readable identifier", example = "RUNTIME_BEHAVIOR_NOT_OBSERVED")
        String code,

        @NonNull
        @NotBlank
        @Size(min = 1, max = 500)
        @Schema(description = "Plain-language explanation of the claim the evidence cannot support")
        String description)
        implements ClosedPracticeInput {
    public PracticeEvidenceLimitation {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(description, "description");
        if (!code.matches("[A-Z][A-Z0-9_]{2,63}")) {
            throw new IllegalArgumentException(
                    "Limitation code must use 3–64 uppercase letters, numbers, and underscores");
        }
        if (description.isBlank() || description.length() > 500) {
            throw new IllegalArgumentException("Limitation description must contain 1–500 characters");
        }
    }
}
