package de.tum.cit.aet.hephaestus.integration.scm.github.subissue.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubEventAction;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubWebhookEvent;
import de.tum.cit.aet.hephaestus.integration.scm.github.issue.dto.GitHubIssueDTO;
import de.tum.cit.aet.hephaestus.integration.scm.github.repository.dto.GitHubRepositoryRefDTO;
import de.tum.cit.aet.hephaestus.integration.scm.github.user.dto.GitHubUserDTO;
import org.jspecify.annotations.Nullable;

/**
 * DTO for GitHub sub_issues webhook events.
 * <p>
 * {@code repository} is the repository of the side that reports the change. The payload names the other
 * side's repository: {@code sub_issue_repo} on a {@code sub_issue_*} action, {@code parent_issue_repo} on a
 * {@code parent_issue_*} action.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GitHubSubIssuesEventDTO(
        @JsonProperty("action") String action,
        @JsonProperty("sub_issue") GitHubIssueDTO subIssue,
        @JsonProperty("sub_issue_repo") @Nullable GitHubRepositoryRefDTO subIssueRepo,
        @JsonProperty("parent_issue") GitHubIssueDTO parentIssue,
        @JsonProperty("parent_issue_repo") @Nullable GitHubRepositoryRefDTO parentIssueRepo,
        @JsonProperty("repository") GitHubRepositoryRefDTO repository,
        @JsonProperty("sender") GitHubUserDTO sender)
        implements GitHubWebhookEvent {
    @Override
    public GitHubEventAction.SubIssue actionType() {
        return GitHubEventAction.SubIssue.fromString(action);
    }

    @Override
    public GitHubRepositoryRefDTO repository() {
        return repository;
    }
}
