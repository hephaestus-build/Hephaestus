package de.tum.cit.aet.hephaestus.integration.scm.github.issuecomment;

import static de.tum.cit.aet.hephaestus.core.LoggingUtils.sanitizeForLog;

import de.tum.cit.aet.hephaestus.integration.core.events.BotCommandReceivedEvent;
import de.tum.cit.aet.hephaestus.integration.core.handler.AbstractIntegrationMessageHandler;
import de.tum.cit.aet.hephaestus.integration.core.handler.EventPayloadDeserializer;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.ProcessingContext;
import de.tum.cit.aet.hephaestus.integration.scm.domain.item.ScmPullRequestItems;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubEventAction;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubEventType;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.ProcessingContextFactory;
import de.tum.cit.aet.hephaestus.integration.scm.github.feedback.GitHubCommentReplySink;
import de.tum.cit.aet.hephaestus.integration.scm.github.issue.GitHubIssueProcessor;
import de.tum.cit.aet.hephaestus.integration.scm.github.issue.dto.GitHubIssueDTO;
import de.tum.cit.aet.hephaestus.integration.scm.github.issuecomment.dto.GitHubIssueCommentEventDTO;
import de.tum.cit.aet.hephaestus.integration.scm.github.issuecomment.dto.GitHubIssueCommentEventDTO.GitHubCommentDTO;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Handles GitHub issue_comment webhook events.
 * <p>
 * This handler processes comments on both Issues and Pull Requests. GitHub sends
 * issue_comment events for both entity types, with a pull_request field present
 * when the comment is on a PR.
 * <p>
 * <b>Key Design Decision:</b> For Issues, we always process the parent entity first
 * to ensure it exists. For PRs, we rely on the comment processor to create a stub
 * PR entity if needed, since the PR webhook may not have arrived yet. This prevents
 * the 769+ ParentEntityNotFoundException errors that occurred due to message ordering.
 *
 * @see GitHubIssueCommentProcessor#processWithParentCreation
 */
@Component
public class GitHubIssueCommentMessageHandler extends AbstractIntegrationMessageHandler<GitHubIssueCommentEventDTO> {

    private static final Logger log = LoggerFactory.getLogger(GitHubIssueCommentMessageHandler.class);

    /** The one command a pull request comment can carry. Only a comment that is exactly this text counts. */
    private static final String REVIEW_COMMAND = "/hephaestus review";

    private final ProcessingContextFactory contextFactory;
    private final GitHubIssueProcessor issueProcessor;
    private final GitHubIssueCommentProcessor commentProcessor;
    private final PullRequestRepository pullRequests;
    private final ScmPullRequestItems pullRequestItems;
    private final ApplicationEventPublisher eventPublisher;

    GitHubIssueCommentMessageHandler(
            ProcessingContextFactory contextFactory,
            GitHubIssueProcessor issueProcessor,
            GitHubIssueCommentProcessor commentProcessor,
            PullRequestRepository pullRequests,
            ScmPullRequestItems pullRequestItems,
            ApplicationEventPublisher eventPublisher,
            EventPayloadDeserializer deserializer,
            TransactionTemplate transactionTemplate) {
        super(
                IntegrationKind.GITHUB,
                "repository." + GitHubEventType.ISSUE_COMMENT.getValue(),
                GitHubIssueCommentEventDTO.class,
                deserializer,
                transactionTemplate);
        this.contextFactory = contextFactory;
        this.issueProcessor = issueProcessor;
        this.commentProcessor = commentProcessor;
        this.pullRequests = pullRequests;
        this.pullRequestItems = pullRequestItems;
        this.eventPublisher = eventPublisher;
    }

    @Override
    protected void handleEvent(GitHubIssueCommentEventDTO event) {
        var commentDto = event.comment();
        var issueDto = event.issue();

        if (commentDto == null || issueDto == null) {
            log.warn("Received issue_comment event with missing data: action={}", event.action());
            return;
        }

        log.debug(
                "Received issue_comment event: action={}, issueNumber={}, commentId={}, repoName={}, isPullRequest={}",
                event.action(),
                issueDto.number(),
                commentDto.id(),
                event.repository() != null ? sanitizeForLog(event.repository().fullName()) : "unknown",
                issueDto.isPullRequest());

        ProcessingContext context = contextFactory.forWebhookEvent(event).orElse(null);
        if (context == null) {
            return;
        }

        // For regular Issues: process the Issue first to ensure it exists.
        // This creates/updates the Issue entity before we try to attach a comment.
        if (!issueDto.isPullRequest()) {
            issueProcessor.process(issueDto, context);
        }
        // For PRs: We don't call issueProcessor.process() because that would create
        // an Issue entity with discriminator "ISSUE", but we need "PULL_REQUEST".
        // The PR should be created by pull_request webhooks, but due to message
        // ordering, the comment may arrive first. The comment processor will handle
        // creating a stub PR if needed.

        // Handle comment action
        if (event.actionType() == GitHubEventAction.IssueComment.DELETED) {
            commentProcessor.delete(Objects.requireNonNull(commentDto.id()), context);
        } else {
            // Use processWithParentCreation to handle the case where the parent
            // entity (Issue or PR) doesn't exist yet. This creates a minimal
            // entity from the webhook data instead of failing.
            commentProcessor.processWithParentCreation(commentDto, issueDto, context);
            if (event.actionType() == GitHubEventAction.IssueComment.CREATED && issueDto.isPullRequest()) {
                publishReviewCommand(commentDto, issueDto, context);
            }
        }
    }

    /**
     * Publishes {@code /hephaestus review} on a new pull request comment, as the GitLab note handler does for a merge
     * request. An edited comment, an app's or bot's comment, and a comment with any other text do not count. The
     * listener resolves the commenter by the provider's own user id, never by login.
     */
    private void publishReviewCommand(GitHubCommentDTO comment, GitHubIssueDTO pullRequest, ProcessingContext context) {
        if (comment.body() == null || !comment.body().strip().equalsIgnoreCase(REVIEW_COMMAND)) {
            return;
        }
        var author = comment.author();
        Long scopeId = context.scopeId();
        var repository = context.repository();
        Long providerId = context.provider() == null ? null : context.provider().getId();
        if (author == null
                || author.type() == User.Type.BOT
                || author.id() == null
                || scopeId == null
                || repository == null
                || providerId == null
                || comment.id() == null) {
            log.info(
                    "Skipped review command: reason=unattributed, commentId={}, pullRequest={}",
                    comment.id(),
                    pullRequest.number());
            return;
        }
        // The scope is the workspace whose consumer received the comment; only its own item may be reviewed.
        var item = pullRequests
                .findByRepositoryIdAndNumber(repository.getId(), pullRequest.number())
                .flatMap(pull -> pullRequestItems.find(scopeId, pull.getId()))
                .orElse(null);
        if (item == null) {
            log.info(
                    "Skipped review command: reason=noWorkspaceItem, workspaceId={}, pullRequest={}",
                    scopeId,
                    pullRequest.number());
            return;
        }
        eventPublisher.publishEvent(new BotCommandReceivedEvent(
                IntegrationKind.GITHUB,
                scopeId,
                item.itemId(),
                comment.body(),
                author.login(),
                providerId,
                author.id(),
                comment.id(),
                GitHubCommentReplySink.address(repository.getNameWithOwner(), pullRequest.number())));
    }
}
