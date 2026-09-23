package de.tum.cit.aet.hephaestus.integration.scm.github.check.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubEventAction;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubWebhookEvent;
import de.tum.cit.aet.hephaestus.integration.scm.github.repository.dto.GitHubRepositoryRefDTO;
import org.jspecify.annotations.Nullable;

/** A {@code check_suite} webhook event: one suite of checks on one head, and how it ended. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GitHubCheckSuiteEventDTO(
        @JsonProperty("action") String action,
        @JsonProperty("check_suite") @Nullable CheckSuite checkSuite,
        @JsonProperty("repository") @Nullable GitHubRepositoryRefDTO repository)
        implements GitHubWebhookEvent {
    @Override
    public GitHubEventAction.CheckSuite actionType() {
        return GitHubEventAction.CheckSuite.fromString(action);
    }

    /**
     * {@code status} is {@code queued}, {@code in_progress} or {@code completed}; {@code conclusion}
     * is set once completed: {@code success}, {@code failure}, {@code neutral}, {@code cancelled},
     * {@code timed_out}, {@code action_required}, {@code stale}, {@code skipped}, {@code startup_failure}.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CheckSuite(
            @JsonProperty("head_sha") @Nullable String headSha,
            @JsonProperty("status") @Nullable String status,
            @JsonProperty("conclusion") @Nullable String conclusion) {}
}
