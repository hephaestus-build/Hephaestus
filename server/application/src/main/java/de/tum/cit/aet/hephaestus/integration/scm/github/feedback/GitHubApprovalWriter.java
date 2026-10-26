package de.tum.cit.aet.hephaestus.integration.scm.github.feedback;

import static de.tum.cit.aet.hephaestus.integration.scm.github.feedback.GitHubPrNodeIdResolver.GRAPHQL_TIMEOUT;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.egress.OutboundEgressGateway;
import de.tum.cit.aet.hephaestus.integration.core.egress.OutboundEgressGuard;
import de.tum.cit.aet.hephaestus.integration.core.spi.ApprovalWriter;
import de.tum.cit.aet.hephaestus.integration.core.spi.FeedbackDeliveryException;
import de.tum.cit.aet.hephaestus.integration.core.spi.FeedbackTarget;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.scm.feedback.ProviderMentions;
import de.tum.cit.aet.hephaestus.integration.scm.feedback.ScmFeedbackTargetResolver;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubGraphQlClientProvider;
import de.tum.cit.aet.hephaestus.integration.scm.github.feedback.GitHubSummaryWriter.PrCoordinates;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.graphql.client.ClientGraphQlResponse;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnServerRole
@OutboundEgressGateway
public class GitHubApprovalWriter implements ApprovalWriter {

    private static final Logger log = LoggerFactory.getLogger(GitHubApprovalWriter.class);

    private final GitHubGraphQlClientProvider gitHubProvider;
    private final GitHubPrNodeIdResolver prNodeIdResolver;
    private final OutboundEgressGuard egressGuard;
    private final ScmFeedbackTargetResolver targets;

    public GitHubApprovalWriter(
            GitHubGraphQlClientProvider gitHubProvider,
            GitHubPrNodeIdResolver prNodeIdResolver,
            OutboundEgressGuard egressGuard,
            ScmFeedbackTargetResolver targets) {
        this.gitHubProvider = gitHubProvider;
        this.prNodeIdResolver = prNodeIdResolver;
        this.egressGuard = egressGuard;
        this.targets = targets;
    }

    @Override
    public IntegrationKind kind() {
        return IntegrationKind.GITHUB;
    }

    @Override
    public void approve(FeedbackTarget target, String message) {
        egressGuard.requireDeliveryAllowed("github.approve");
        long scopeId = target.integration().workspaceId();
        if (gitHubProvider.isRateLimitCritical(scopeId)) {
            throw new FeedbackDeliveryException(
                    "The GitHub rate limit is critical. Hephaestus skips the approval for scope " + scopeId);
        }

        PrCoordinates pr = GitHubSummaryWriter.parseSubjectExternalId(targets.subject(target, IntegrationKind.GITHUB));
        String prNodeId = prNodeIdResolver.resolve(scopeId, pr.owner(), pr.name(), pr.number());

        ClientGraphQlResponse response = gitHubProvider
                .forScope(scopeId)
                .documentName("ApprovePullRequest")
                .variable("pullRequestId", prNodeId)
                .variable("body", ProviderMentions.escape(message))
                .execute()
                .block(GRAPHQL_TIMEOUT);

        if (response == null) {
            throw new FeedbackDeliveryException("The ApprovePullRequest mutation returned no response");
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
