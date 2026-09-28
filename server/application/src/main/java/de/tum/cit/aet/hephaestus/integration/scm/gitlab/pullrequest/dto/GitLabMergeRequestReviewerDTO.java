package de.tum.cit.aet.hephaestus.integration.scm.gitlab.pullrequest.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.dto.GitLabWebhookUser;
import org.jspecify.annotations.Nullable;

/**
 * A reviewer in a merge request webhook payload, with where their review stands: {@code unreviewed},
 * {@code review_started}, {@code reviewed}, {@code requested_changes}, {@code approved} or {@code unapproved}.
 * A GitLab that predates reviewer states sends none.
 *
 * @see <a href="https://docs.gitlab.com/user/project/integrations/webhook_events/#merge-request-events">GitLab
 *     merge request events</a>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GitLabMergeRequestReviewerDTO(
        @Nullable Long id,
        @Nullable String username,
        @Nullable String name,
        @JsonProperty("avatar_url") @Nullable String avatarUrl,
        @Nullable String email,
        @Nullable String state) {

    public GitLabWebhookUser user() {
        return new GitLabWebhookUser(id, username, name, avatarUrl, email);
    }
}
