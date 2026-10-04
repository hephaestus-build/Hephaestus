package de.tum.cit.aet.hephaestus.integration.scm.github.feedback;

import de.tum.cit.aet.hephaestus.integration.core.spi.FeedbackDeliveryException;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubGraphQlClientProvider;
import java.time.Duration;
import java.util.List;
import org.springframework.graphql.client.ClientGraphQlResponse;
import org.springframework.stereotype.Component;

/**
 * Resolves a GitHub pull request's node ID via the {@code GetPullRequestNodeId}
 * GraphQL query. Shared by the GitHub feedback adapters — each needs the node ID before issuing its
 * mutation, and centralising the lookup keeps mutation timeouts and error-shape handling in one place.
 *
 * <p>Throws {@link FeedbackDeliveryException} on any failure so callers can mark
 * delivery as failed with a clear reason.
 */
@Component
class GitHubPrNodeIdResolver {

    static final Duration GRAPHQL_TIMEOUT = Duration.ofSeconds(15);

    private final GitHubGraphQlClientProvider gitHubProvider;

    GitHubPrNodeIdResolver(GitHubGraphQlClientProvider gitHubProvider) {
        this.gitHubProvider = gitHubProvider;
    }

    String resolve(long scopeId, String owner, String name, int number) {
        return resolveNodeId(scopeId, owner, name, number, "GetPullRequestNodeId", "repository.pullRequest.id", "PR");
    }

    /**
     * Resolves a GitHub issue's node ID via {@code GetIssueNodeId}. Needed because GitHub addresses PRs
     * and issues identically as {@code owner/repo#N}, so the PR resolver's {@code repository.pullRequest}
     * field is null for a real issue — the issue must be resolved through {@code repository.issue} instead.
     */
    String resolveIssue(long scopeId, String owner, String name, int number) {
        return resolveNodeId(scopeId, owner, name, number, "GetIssueNodeId", "repository.issue.id", "Issue");
    }

    private String resolveNodeId(
            long scopeId, String owner, String name, int number, String documentName, String idField, String kind) {
        String ref = owner + "/" + name + "#" + number;
        ClientGraphQlResponse response = gitHubProvider
                .forScope(scopeId)
                .documentName(documentName)
                .variable("owner", owner)
                .variable("name", name)
                .variable("number", number)
                .execute()
                .block(GRAPHQL_TIMEOUT);

        if (response == null) {
            throw new FeedbackDeliveryException("The lookup of the " + kind + " node ID returned no response: " + ref);
        }
        gitHubProvider.trackRateLimit(scopeId, response);

        String nodeId = response.field(idField).getValue();
        if (nodeId == null) {
            List<?> errors = response.getErrors();
            throw new FeedbackDeliveryException(
                    kind + " not found through GraphQL: " + ref + (errors.isEmpty() ? "" : ", errors=" + errors));
        }
        return nodeId;
    }
}
