package de.tum.cit.aet.hephaestus.integration.scm.gitlab.pipeline.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.dto.GitLabWebhookProject;
import org.jspecify.annotations.Nullable;

/**
 * A GitLab Pipeline Hook ({@code object_kind: "pipeline"}): one pipeline on one commit, with the
 * merge request it belongs to when GitLab knows one.
 *
 * @param objectAttributes the pipeline: its {@code status} in GitLab's lowercase REST vocabulary
 *     ({@code pending}, {@code running}, {@code success}, {@code failed}, {@code canceled},
 *     {@code skipped}, …) and the {@code sha} it ran for
 * @param mergeRequest the merge request the pipeline is for, when it is a merge request pipeline
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GitLabPipelineEventDTO(
        @JsonProperty("object_kind") String objectKind,
        @Nullable GitLabWebhookProject project,
        @JsonProperty("object_attributes") @Nullable ObjectAttributes objectAttributes,
        @JsonProperty("merge_request") @Nullable MergeRequestRef mergeRequest) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ObjectAttributes(
            long id, @Nullable String status, @Nullable String sha) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MergeRequestRef(long id, int iid) {}
}
