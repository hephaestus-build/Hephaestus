package de.tum.cit.aet.hephaestus.practices.review.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

/** A repository at the commit that the review pinned on its monitored default branch. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record RepositoryCommitVersionDTO(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = "REPOSITORY_COMMIT")
        String kind,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String ref,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String commit)
        implements ReviewedVersionDTO {
    public RepositoryCommitVersionDTO {
        if (!"REPOSITORY_COMMIT".equals(kind)) {
            throw new IllegalArgumentException("Invalid kind");
        }
    }
}
