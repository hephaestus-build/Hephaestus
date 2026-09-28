package de.tum.cit.aet.hephaestus.integration.scm.gitlab.pullrequest.dto;

import static de.tum.cit.aet.hephaestus.core.LoggingUtils.sanitizeForLog;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.RequestedReviewer;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.dto.GitLabWebhookUser;
import java.util.Locale;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A reviewer in a merge request webhook payload, with where their review stands: {@code unreviewed},
 * {@code review_started}, {@code reviewed}, {@code requested_changes}, {@code approved} or {@code unapproved}. GitLab
 * before 18.6 sends no state: its hooks list reviewers by {@code User#hook_attrs} alone.
 *
 * @see <a href="https://docs.gitlab.com/user/project/integrations/webhook_events/#merge-request-events">GitLab
 *     merge request events</a>
 * @see <a href="https://gitlab.com/gitlab-org/gitlab/-/blob/v18.4.0-ee/lib/gitlab/data_builder/issuable.rb#L31">GitLab
 *     18.4: reviewers without a state</a>
 * @see <a href="https://gitlab.com/gitlab-org/gitlab/-/blob/v18.6.0-ee/app/models/concerns/issuable.rb#L589-597">GitLab
 *     18.6: reviewers with their state</a>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GitLabMergeRequestReviewerDTO(
        @Nullable Long id,
        @Nullable String username,
        @Nullable String name,
        @JsonProperty("avatar_url") @Nullable String avatarUrl,
        @Nullable String email,
        @Nullable String state) {

    private static final Logger log = LoggerFactory.getLogger(GitLabMergeRequestReviewerDTO.class);

    public GitLabWebhookUser user() {
        return new GitLabWebhookUser(id, username, name, avatarUrl, email);
    }

    /**
     * A review state GitLab sent, spelled as in a webhook ({@code approved}) or in GraphQL ({@code APPROVED}); none
     * for one Hephaestus does not know, so the review counts as still requested until the reviewer's standing verdict.
     */
    public static RequestedReviewer.@Nullable ReviewState reviewState(String value) {
        try {
            return RequestedReviewer.ReviewState.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            log.warn("Stored the reviewer without a state: reason=unknownGitLabState, state={}", sanitizeForLog(value));
            return null;
        }
    }
}
