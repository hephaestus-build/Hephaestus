package de.tum.cit.aet.hephaestus.integration.scm.github.feedback;

import de.tum.cit.aet.hephaestus.core.runtime.ConditionalOnServerRole;
import de.tum.cit.aet.hephaestus.integration.core.egress.OutboundEgressGateway;
import de.tum.cit.aet.hephaestus.integration.core.egress.OutboundEgressGuard;
import de.tum.cit.aet.hephaestus.integration.core.egress.OutboundEgressSuppressedException;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.core.spi.ScmCommentReplySink;
import de.tum.cit.aet.hephaestus.integration.scm.feedback.ScmFeedbackFormatter;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubGraphQlClientProvider;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * {@link ScmCommentReplySink} for GitHub: a comment on the pull request. A pull request conversation has no threads,
 * so the answer is a new comment there. The reply address is {@code owner/repo#number}: a comment payload names the
 * pull request's issue node, not the pull request's own node, so the node is resolved when the answer is posted.
 */
@Component
@ConditionalOnServerRole
@OutboundEgressGateway
@Slf4j
public class GitHubCommentReplySink implements ScmCommentReplySink {
    private static final Duration GRAPHQL_TIMEOUT = Duration.ofSeconds(10);

    private final GitHubGraphQlClientProvider gitHub;
    private final GitHubPrNodeIdResolver pullRequestNodes;
    private final OutboundEgressGuard egressGuard;
    private final ScmFeedbackFormatter formatter;

    GitHubCommentReplySink(
            GitHubGraphQlClientProvider gitHub,
            GitHubPrNodeIdResolver pullRequestNodes,
            OutboundEgressGuard egressGuard,
            ScmFeedbackFormatter formatter) {
        this.gitHub = gitHub;
        this.pullRequestNodes = pullRequestNodes;
        this.egressGuard = egressGuard;
        this.formatter = formatter;
    }

    /** The reply address of a comment on pull request {@code number} of {@code nameWithOwner}. */
    public static String address(String nameWithOwner, int number) {
        return nameWithOwner + "#" + number;
    }

    @Override
    public IntegrationKind kind() {
        return IntegrationKind.GITHUB;
    }

    @Override
    public void reply(long workspaceId, long itemId, String replyAddress, String text) {
        try {
            var pullRequest = GitHubSummaryWriter.parseSubjectExternalId(replyAddress);
            String body = formatter.requestAnswer(workspaceId, itemId, text);
            egressGuard.requireDeliveryAllowed("github.answer-request-comment");
            String subject = pullRequestNodes.resolve(
                    workspaceId, pullRequest.owner(), pullRequest.name(), pullRequest.number());
            var response = gitHub.forScope(workspaceId)
                    .documentName("AddPullRequestComment")
                    .variable("subjectId", subject)
                    .variable("body", body)
                    .execute()
                    .block(GRAPHQL_TIMEOUT);
            if (response != null) gitHub.trackRateLimit(workspaceId, response);
            if (response == null
                    || !response.isValid()
                    || !response.getErrors().isEmpty()
                    || response.field("addComment.commentEdge.node.id").getValue() == null)
                log.warn(
                        "GitHub did not post the answer to a request comment: workspaceId={}, itemId={}",
                        workspaceId,
                        itemId);
        } catch (OutboundEgressSuppressedException suppressed) {
            log.debug("Silent Mode suppressed the answer to a request comment: workspaceId={}", workspaceId);
        } catch (RuntimeException failure) {
            log.warn(
                    "Could not answer a request comment: workspaceId={}, itemId={}, error={}",
                    workspaceId,
                    itemId,
                    failure.getClass().getSimpleName());
        }
    }
}
