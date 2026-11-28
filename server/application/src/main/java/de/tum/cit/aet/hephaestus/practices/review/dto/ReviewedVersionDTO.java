package de.tum.cit.aet.hephaestus.practices.review.dto;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import io.swagger.v3.oas.annotations.media.DiscriminatorMapping;
import io.swagger.v3.oas.annotations.media.Schema;

/** What a review that a setup started pinned. A review of a pull request or an issue has none. */
@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.EXISTING_PROPERTY,
        property = "kind",
        visible = true)
@JsonSubTypes({
    @JsonSubTypes.Type(value = RepositoryCommitVersionDTO.class, name = "REPOSITORY_COMMIT"),
    @JsonSubTypes.Type(value = SubmittedVersionDTO.class, name = "SUBMITTED_VERSION")
})
@Schema(
        oneOf = {RepositoryCommitVersionDTO.class, SubmittedVersionDTO.class},
        discriminatorProperty = "kind",
        discriminatorMapping = {
            @DiscriminatorMapping(value = "REPOSITORY_COMMIT", schema = RepositoryCommitVersionDTO.class),
            @DiscriminatorMapping(value = "SUBMITTED_VERSION", schema = SubmittedVersionDTO.class)
        })
public sealed interface ReviewedVersionDTO permits RepositoryCommitVersionDTO, SubmittedVersionDTO {}
