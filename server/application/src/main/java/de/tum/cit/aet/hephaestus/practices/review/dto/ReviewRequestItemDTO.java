package de.tum.cit.aet.hephaestus.practices.review.dto;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import io.swagger.v3.oas.annotations.media.DiscriminatorMapping;
import io.swagger.v3.oas.annotations.media.Schema;

/** The item that a review request names, at the version to review. */
@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.EXISTING_PROPERTY,
        property = "kind",
        visible = true)
@JsonSubTypes({
    @JsonSubTypes.Type(value = RepositoryCommitRequestItemDTO.class, name = "REPOSITORY_COMMIT"),
    @JsonSubTypes.Type(value = SubmittedVersionRequestItemDTO.class, name = "SUBMITTED_VERSION")
})
@Schema(
        oneOf = {RepositoryCommitRequestItemDTO.class, SubmittedVersionRequestItemDTO.class},
        discriminatorProperty = "kind",
        discriminatorMapping = {
            @DiscriminatorMapping(value = "REPOSITORY_COMMIT", schema = RepositoryCommitRequestItemDTO.class),
            @DiscriminatorMapping(value = "SUBMITTED_VERSION", schema = SubmittedVersionRequestItemDTO.class)
        })
public sealed interface ReviewRequestItemDTO permits RepositoryCommitRequestItemDTO, SubmittedVersionRequestItemDTO {}
