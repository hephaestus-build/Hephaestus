package de.tum.cit.aet.hephaestus.integration.scm.github.feedback;

import static de.tum.cit.aet.hephaestus.integration.scm.github.feedback.GitHubPrNodeIdResolver.GRAPHQL_TIMEOUT;

import de.tum.cit.aet.hephaestus.integration.core.egress.OutboundEgressGateway;
import de.tum.cit.aet.hephaestus.integration.core.egress.OutboundEgressGuard;
import de.tum.cit.aet.hephaestus.integration.core.spi.ApprovalChannel;
import de.tum.cit.aet.hephaestus.integration.core.spi.FeedbackDeliveryException;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.SummaryChannel;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubGraphQlClientProvider;
import de.tum.cit.aet.hephaestus.integration.scm.github.feedback.GitHubSummaryChannel.PrCoordinates;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.graphql.client.ClientGraphQlResponse;
import org.springframework.stereotype.Component;

/**
 * GitHub adapter for {@link ApprovalChannel}: submits an APPROVE pull-request review
 * via the {@code ApprovePullRequest} mutation ({@code addPullRequestReview} specialised
 * to {@code event: APPROVE}).
 */
@Component
@OutboundEgressGateway
public class GitHubApprovalChannel implements ApprovalChannel {

    private static final Logger log = LoggerFactory.getLogger(GitHubApprovalChannel.class);

    private final GitHubGraphQlClientProvider gitHubProvider;
    private final GitHubPrNodeIdResolver prNodeIdResolver;
    private final OutboundEgressGuard egressGuard;

    public GitHubApprovalChannel(
            GitHubGraphQlClientProvider gitHubProvider,
            GitHubPrNodeIdResolver prNodeIdResolver,
            OutboundEgressGuard egressGuard) {
        this.gitHubProvider = gitHubProvider;
        this.prNodeIdResolver = prNodeIdResolver;
        this.egressGuard = egressGuard;
    }

    @Override
    public IntegrationKind kind() {
        return IntegrationKind.GITHUB;
    }

    @Override
    public void approve(SummaryChannel.FeedbackTarget target, String message) {
        egressGuard.requireDeliveryAllowed("github.approve");
        long scopeId = target.ref().workspaceId();
        if (gitHubProvider.isRateLimitCritical(scopeId)) {
            throw new FeedbackDeliveryException("GitHub rate limit critical — skipping approval for scope " + scopeId);
        }

        PrCoordinates pr = GitHubSummaryChannel.parseSubjectExternalId(target.subjectExternalId());
        String prNodeId = prNodeIdResolver.resolve(scopeId, pr.owner(), pr.name(), pr.number());

        ClientGraphQlResponse response = gitHubProvider
                .forScope(scopeId)
                .documentName("ApprovePullRequest")
                .variable("pullRequestId", prNodeId)
                .variable("body", message)
                .execute()
                .block(GRAPHQL_TIMEOUT);

        if (response == null) {
            throw new FeedbackDeliveryException("Null response from ApprovePullRequest mutation");
        }
        gitHubProvider.trackRateLimit(scopeId, response);

        if (response.getErrors() != null && !response.getErrors().isEmpty()) {
            throw new FeedbackDeliveryException("GitHub ApprovePullRequest failed: " + response.getErrors());
        }

        String reviewId =
                response.field("addPullRequestReview.pullRequestReview.id").getValue();
        log.info(
                "Posted GitHub approval review: workspaceId={}, prNodeId={}, reviewId={}", scopeId, prNodeId, reviewId);
    }
}
