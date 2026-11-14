package de.tum.cit.aet.hephaestus.integration.scm.github.repository.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubEventAction;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubWebhookEvent;
import de.tum.cit.aet.hephaestus.integration.scm.github.user.dto.GitHubUserDTO;

/**
 * DTO for GitHub member webhook events (collaborator changes).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GitHubMemberEventDTO(
        @JsonProperty("action") String action,
        @JsonProperty("member") GitHubUserDTO member,
        @JsonProperty("repository") GitHubRepositoryRefDTO repository,
        @JsonProperty("sender") GitHubUserDTO sender)
        implements GitHubWebhookEvent {
    @Override
    public GitHubEventAction.Member actionType() {
        return GitHubEventAction.Member.fromString(action);
    }

    @Override
    public GitHubRepositoryRefDTO repository() {
        return repository;
    }
}
