package de.tum.cit.aet.hephaestus.integration.scm.github.check.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubEventAction;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubWebhookEvent;
import de.tum.cit.aet.hephaestus.integration.scm.github.repository.dto.GitHubRepositoryRefDTO;
import org.jspecify.annotations.Nullable;

/**
 * A {@code status} webhook event: one commit status ({@code pending}, {@code success},
 * {@code failure}, {@code error}) on one commit. It carries no {@code action}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GitHubStatusEventDTO(
        @JsonProperty("sha") @Nullable String sha,
        @JsonProperty("state") @Nullable String state,
        @JsonProperty("repository") @Nullable GitHubRepositoryRefDTO repository)
        implements GitHubWebhookEvent {
    @Override
    public String action() {
        return GitHubEventAction.Status.REPORTED.value();
    }

    @Override
    public GitHubEventAction.Status actionType() {
        return GitHubEventAction.Status.REPORTED;
    }
}
