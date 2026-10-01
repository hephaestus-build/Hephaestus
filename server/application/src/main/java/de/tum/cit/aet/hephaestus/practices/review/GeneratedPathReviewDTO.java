package de.tum.cit.aet.hephaestus.practices.review;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.NonNull;

@Schema(description = "Generated-path policy used by this review, not the repository's current settings")
public record GeneratedPathReviewDTO(
        @NonNull List<String> patterns,

        @NonNull @Schema(description = "Changed paths marked generated, including both names of a rename")
        List<String> paths) {
    public static final String INPUT_PATH = "inputs/generated-paths.json";

    public GeneratedPathReviewDTO {
        patterns = List.copyOf(patterns);
        paths = List.copyOf(paths);
    }

    public static GeneratedPathReviewDTO of(List<String> patterns, Set<String> changedPaths) {
        return new GeneratedPathReviewDTO(
                patterns,
                changedPaths.stream()
                        .filter(path -> GeneratedPaths.matches(patterns, path))
                        .sorted()
                        .toList());
    }
}
