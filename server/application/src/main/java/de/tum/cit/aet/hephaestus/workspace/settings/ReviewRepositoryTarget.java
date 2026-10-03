package de.tum.cit.aet.hephaestus.workspace.settings;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.NonNull;

public record ReviewRepositoryTarget(
        @Schema(description = "Exact provider repository name, including its owner") @NonNull @NotBlank @Size(max = 255)
        String nameWithOwner,

        @Schema(description = "Exact pull-request base branches to cover; empty covers every base branch")
        @NonNull
        @NotNull
        List<String> baseBranches) {
    public ReviewRepositoryTarget {
        nameWithOwner = validValue(nameWithOwner, "repository name");
        baseBranches = normalize(baseBranches);
    }

    private static List<String> normalize(List<String> values) {
        return Objects.requireNonNull(values, "baseBranches").stream()
                .map(value -> validValue(value, "base branch"))
                .distinct()
                .sorted()
                .toList();
    }

    private static String validValue(String value, String label) {
        String normalized = Objects.requireNonNull(value, label).trim();
        requireValid(normalized, label);
        return normalized;
    }

    private static void requireValid(String value, String label) {
        if (value.isBlank() || value.length() > 255) {
            throw new IllegalArgumentException(label + " must contain 1 to 255 characters");
        }
    }
}
