package de.tum.cit.aet.hephaestus.integration.scm.workspace.gitlab;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;

/** The user of a GitLab token, as {@code GET /api/v4/user} answers the default workspace bootstrap. */
public record GitLabTokenUserResponse(
        Long id,
        String username,
        String name,
        @JsonProperty("avatar_url") String avatarUrl,
        @JsonProperty("web_url") String webUrl,
        @Nullable Boolean bot) {}
