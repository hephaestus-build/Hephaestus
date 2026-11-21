package de.tum.cit.aet.hephaestus.integration.scm.workspace.gitlab;

import com.fasterxml.jackson.annotation.JsonProperty;

/** A GitLab group, as {@code GET /api/v4/groups/{path}} answers the default workspace bootstrap. */
public record GitLabRestGroupResponse(
        Long id,
        String name,
        @JsonProperty("avatar_url") String avatarUrl,
        @JsonProperty("web_url") String webUrl) {}
