package de.tum.cit.aet.hephaestus.integration.scm.github.pullrequest.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubEventAction;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubWebhookEvent;
import de.tum.cit.aet.hephaestus.integration.scm.github.label.dto.GitHubLabelDTO;
import de.tum.cit.aet.hephaestus.integration.scm.github.repository.dto.GitHubRepositoryRefDTO;
import de.tum.cit.aet.hephaestus.integration.scm.github.user.dto.GitHubUserDTO;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * DTO for GitHub pull request webhook event payloads.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GitHubPullRequestEventDTO(
        @JsonProperty("action") String action,
        @JsonProperty("number") int number,
        @JsonProperty("pull_request") @Nullable GitHubPullRequestDTO pullRequest,
        @JsonProperty("repository") @Nullable GitHubRepositoryRefDTO repository,
        @JsonProperty("sender") @Nullable GitHubUserDTO sender,
        @JsonProperty("label") @Nullable GitHubLabelDTO label,
        @JsonProperty("requested_reviewer") @Nullable GitHubUserDTO requestedReviewer,
        @JsonProperty("changes") @Nullable Map<String, Object> changes,
        @JsonProperty("before") @Nullable String before)
        implements GitHubWebhookEvent {
    @Override
    public GitHubEventAction.PullRequest actionType() {
        return GitHubEventAction.PullRequest.fromString(action);
    }
}
