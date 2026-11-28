package de.tum.cit.aet.hephaestus.practices.review.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

/**
 * A repository to review on its monitored default branch. Without a commit, the server pins the head at creation; with
 * one, it refuses a commit that is not that head.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record RepositoryCommitRequestItemDTO(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = "REPOSITORY_COMMIT") @NotNull
        String kind,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, type = "string", pattern = "^[1-9][0-9]*$")
        @NotNull
        @Pattern(regexp = "[1-9][0-9]*")
        @DecimalMax("9223372036854775807")
        String repositoryItemId,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @Nullable @Size(min = 1, max = 1024)
        String ref,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @Nullable @Size(min = 1, max = 1024)
        String commit)
        implements ReviewRequestItemDTO {
    public RepositoryCommitRequestItemDTO {
        if (!"REPOSITORY_COMMIT".equals(kind)) {
            throw new IllegalArgumentException("Invalid kind");
        }
    }
}
