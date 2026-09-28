package de.tum.cit.aet.hephaestus.integration.scm.github.issuedependency.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubEventAction;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubWebhookEvent;
import de.tum.cit.aet.hephaestus.integration.scm.github.issue.dto.GitHubIssueDTO;
import de.tum.cit.aet.hephaestus.integration.scm.github.repository.dto.GitHubRepositoryRefDTO;
import de.tum.cit.aet.hephaestus.integration.scm.github.user.dto.GitHubUserDTO;
import org.jspecify.annotations.Nullable;

/**
 * DTO for GitHub issue_dependencies webhook events.
 * <p>
 * {@code repository} is the repository of the side that reports the change. The payload names the other
 * side's repository: {@code blocking_issue_repo} on a {@code blocked_by_*} action, {@code blocked_issue_repo} on a
 * {@code blocking_*} action.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GitHubIssueDependenciesEventDTO(
        @JsonProperty("action") String action,
        @JsonProperty("blocked_issue") GitHubIssueDTO blockedIssue,
        @JsonProperty("blocked_issue_repo") @Nullable GitHubRepositoryRefDTO blockedIssueRepo,
        @JsonProperty("blocking_issue") GitHubIssueDTO blockingIssue,
        @JsonProperty("blocking_issue_repo") @Nullable GitHubRepositoryRefDTO blockingIssueRepo,
        @JsonProperty("repository") GitHubRepositoryRefDTO repository,
        @JsonProperty("sender") GitHubUserDTO sender)
        implements GitHubWebhookEvent {
    @Override
    public GitHubEventAction.IssueDependency actionType() {
        return GitHubEventAction.IssueDependency.fromString(action);
    }

    @Override
    public GitHubRepositoryRefDTO repository() {
        return repository;
    }
}
