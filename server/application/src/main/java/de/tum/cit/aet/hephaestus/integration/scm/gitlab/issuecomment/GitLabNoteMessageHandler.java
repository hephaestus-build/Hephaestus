package de.tum.cit.aet.hephaestus.integration.scm.gitlab.issuecomment;

import static de.tum.cit.aet.hephaestus.core.LoggingUtils.sanitizeForLog;

import de.tum.cit.aet.hephaestus.integration.core.events.BotCommandReceivedEvent;
import de.tum.cit.aet.hephaestus.integration.core.handler.AbstractIntegrationMessageHandler;
import de.tum.cit.aet.hephaestus.integration.core.spi.IntegrationKind;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.NatsMessageDeserializer;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.ProcessingContext;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.BaseGitLabProcessor;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabEventAction;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabEventType;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabWebhookContextResolver;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.issuecomment.dto.GitLabNoteEventDTO;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.pullrequest.GitLabMergeRequestProcessor;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.pullrequestreview.GitLabReviewReconciler;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.pullrequestreviewcomment.GitLabDiffNoteWebhookProcessor;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.user.GitLabUserService;
import java.time.Instant;
import java.util.HashSet;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Handles GitLab note webhook events, routing to the appropriate processor by noteable type. */
@Component
@ConditionalOnProperty(name = "hephaestus.integration.gitlab.enabled", havingValue = "true", matchIfMissing = false)
public class GitLabNoteMessageHandler extends AbstractIntegrationMessageHandler<GitLabNoteEventDTO> {

    private static final Logger log = LoggerFactory.getLogger(GitLabNoteMessageHandler.class);
    private static final String BOT_COMMAND_PREFIX = "/hephaestus ";

    private final GitLabIssueCommentProcessor issueCommentProcessor;
    private final GitLabDiffNoteWebhookProcessor diffNoteProcessor;
    private final GitLabMergeRequestProcessor mergeRequestProcessor;
    private final GitLabWebhookContextResolver contextResolver;
    private final PullRequestRepository pullRequestRepository;
    private final UserRepository userRepository;
    private final GitLabUserService userService;
    private final GitLabReviewReconciler reviewReconciler;
    private final ApplicationEventPublisher eventPublisher;

    GitLabNoteMessageHandler(
            GitLabIssueCommentProcessor issueCommentProcessor,
            GitLabDiffNoteWebhookProcessor diffNoteProcessor,
            GitLabMergeRequestProcessor mergeRequestProcessor,
            GitLabWebhookContextResolver contextResolver,
            PullRequestRepository pullRequestRepository,
            UserRepository userRepository,
            GitLabUserService userService,
            GitLabReviewReconciler reviewReconciler,
            NatsMessageDeserializer deserializer,
            TransactionTemplate transactionTemplate,
            ApplicationEventPublisher eventPublisher) {
        super(
                IntegrationKind.GITLAB,
                GitLabEventType.NOTE.getValue(),
                GitLabNoteEventDTO.class,
                deserializer,
                transactionTemplate);
        this.issueCommentProcessor = issueCommentProcessor;
        this.diffNoteProcessor = diffNoteProcessor;
        this.mergeRequestProcessor = mergeRequestProcessor;
        this.contextResolver = contextResolver;
        this.userService = userService;
        this.reviewReconciler = reviewReconciler;
        this.pullRequestRepository = pullRequestRepository;
        this.userRepository = userRepository;
        this.eventPublisher = eventPublisher;
    }

    @Override
    protected void handleEvent(GitLabNoteEventDTO event) {
        if (event.objectAttributes() == null) {
            log.warn("Received note event with missing object_attributes");
            return;
        }

        if (event.project() == null) {
            log.warn("Received note event with missing project data");
            return;
        }

        // A system note is not a comment, but the three that record a review decision on a merge
        // request are the only place GitLab says who approved, withdrew an approval or requested
        // changes, and when; the sync reads the same notes, so the record agrees either way.
        if (event.isSystemNote()) {
            if ("MergeRequest".equals(event.noteableType())
                    && GitLabReviewReconciler.isReviewDecision(
                            event.objectAttributes().note())) {
                recordReviewDecision(event);
            } else {
                log.debug(
                        "Skipped system note: noteId={}",
                        event.objectAttributes().id());
            }
            return;
        }

        // Skip internal/confidential notes
        if (event.isInternalNote()) {
            log.debug(
                    "Skipped internal note: noteId={}", event.objectAttributes().id());
            return;
        }

        // Skip notes on confidential issues
        if (event.isConfidentialIssue()) {
            log.debug(
                    "Skipped note on confidential issue: noteId={}",
                    event.objectAttributes().id());
            return;
        }

        String projectPath = event.project().pathWithNamespace();
        if (projectPath == null || projectPath.isBlank()) {
            log.warn("Received note event with missing project path");
            return;
        }
        String safeProjectPath = Objects.requireNonNullElse(sanitizeForLog(projectPath), "<unknown>");
        GitLabEventAction action = event.actionType();
        String noteableType = event.noteableType();

        if (action == GitLabEventAction.UNKNOWN) {
            log.debug(
                    "Skipped note with unknown action: projectPath={}, noteId={}",
                    safeProjectPath,
                    event.objectAttributes().id());
            return;
        }

        log.info(
                "Processing note event: projectPath={}, noteableType={}, noteId={}, action={}",
                safeProjectPath,
                noteableType,
                event.objectAttributes().id(),
                action);

        ProcessingContext context = contextResolver.resolve(projectPath, action.getValue(), "note");
        if (context == null) {
            return;
        }

        // Bot command detection: check for commands like "/hephaestus review" on MR notes.
        // Publishes an event so the agent module can process it asynchronously.
        if ("MergeRequest".equals(noteableType)
                && action == GitLabEventAction.CREATE
                && isBotCommand(event.objectAttributes().note())) {
            handleBotCommand(event, context, safeProjectPath);
        }

        switch (noteableType) {
            case "Issue" -> issueCommentProcessor.processIssueNote(event, context);
            case "MergeRequest" -> {
                if (event.isDiffNote()) {
                    diffNoteProcessor.processDiffNote(event, context);
                } else {
                    issueCommentProcessor.processMergeRequestNote(event, context);
                }
                // Detect "Request changes" signal: GitLab's batch review with "Request changes"
                // does NOT fire an MR webhook (GitLab bug #517909). Instead, the note events
                // carry merge_request.detailed_merge_status = "requested_changes".
                detectRequestedChanges(event, context);
            }
            case "Commit" ->
                log.debug(
                        "Skipped commit note: projectPath={}, noteId={}",
                        safeProjectPath,
                        event.objectAttributes().id());
            case null, default ->
                log.debug(
                        "Skipped note with unsupported noteable type: projectPath={}, noteableType={}, noteId={}",
                        safeProjectPath,
                        noteableType,
                        event.objectAttributes().id());
        }
    }

    private static boolean isBotCommand(String noteBody) {
        return noteBody != null
                && !noteBody.isBlank()
                && noteBody.strip().toLowerCase().startsWith(BOT_COMMAND_PREFIX);
    }

    /**
     * Detects the "Request changes" signal from MR note events.
     *
     * <p>GitLab's "Request changes" (Premium, GA 17.3) fires NO dedicated webhook.
     * However, note events from the batch review carry the updated
     * {@code merge_request.detailed_merge_status}. When this transitions to
     * {@code "requested_changes"}, we create a CHANGES_REQUESTED review for the note author.
     */
    private void recordReviewDecision(GitLabNoteEventDTO event) {
        var note = Objects.requireNonNull(event.objectAttributes());
        var mr = event.mergeRequest();
        var project = event.project();
        String projectPath = project == null ? null : project.pathWithNamespace();
        if (mr == null || mr.iid() == null || projectPath == null || projectPath.isBlank() || note.id() == null) {
            return;
        }
        ProcessingContext context =
                contextResolver.resolve(projectPath, event.actionType().getValue(), "note");
        Repository repository = context == null ? null : context.repository();
        Long providerId = context == null ? null : context.providerId();
        if (repository == null || providerId == null) {
            return;
        }
        Instant at = BaseGitLabProcessor.parseGitLabTimestamp(note.createdAt());
        User author = userService.findOrCreateUser(event.user(), providerId);
        PullRequest pullRequest = pullRequestRepository
                .findByRepositoryIdAndNumber(repository.getId(), mr.iid())
                .orElse(null);
        if (at == null || author == null || pullRequest == null) {
            log.debug(
                    "Skipped review-decision note: reason=unresolved, noteId={}, mrIid={}, authorPresent={}, mrPresent={}",
                    note.id(),
                    mr.iid(),
                    author != null,
                    pullRequest != null);
            return;
        }
        // The same GID the GraphQL sync gives the note, so a later sync finds the row this made.
        reviewReconciler.recordSystemNote(
                pullRequest,
                author,
                new GitLabReviewReconciler.SystemNote(note.note(), at, "gid://gitlab/Note/" + note.id(), true),
                pullRequest.getProvider(),
                new HashSet<>());
    }

    private void detectRequestedChanges(GitLabNoteEventDTO event, ProcessingContext context) {
        var mr = event.mergeRequest();
        if (mr == null || mr.iid() == null || mr.detailedMergeStatus() == null) return;

        if (!"requested_changes".equals(mr.detailedMergeStatus())) return;

        // The note author is the person who requested changes
        if (event.user() == null || event.user().id() == null) return;

        // Don't create CHANGES_REQUESTED for the MR author commenting on their own PR
        var repository = context.repository();
        if (repository == null) return;
        var pullRequest = pullRequestRepository
                .findByRepositoryIdAndNumber(repository.getId(), mr.iid())
                .orElse(null);
        if (pullRequest == null) return;

        // Self-review guard: skip if reviewer == MR author
        if (pullRequest.getAuthor() != null
                && pullRequest.getAuthor().getNativeId() != null
                && pullRequest
                        .getAuthor()
                        .getNativeId()
                        .equals(event.user().id().longValue())) {
            return;
        }

        Long providerId = context.providerId();
        if (providerId == null) return;
        User reviewer = userRepository
                .findByNativeIdAndProviderId(event.user().id(), providerId)
                .orElse(null);
        if (reviewer == null) return;

        mergeRequestProcessor.processRequestedChangesFromNote(pullRequest, reviewer, context);
    }

    private void handleBotCommand(GitLabNoteEventDTO event, ProcessingContext context, String safeProjectPath) {
        var attrs = event.objectAttributes();
        if (attrs == null) {
            return;
        }
        var mr = event.mergeRequest();
        if (mr == null || mr.iid() == null) {
            log.warn(
                    "Bot command on MR note but no embedded merge_request data: projectPath={}, noteId={}",
                    safeProjectPath,
                    attrs.id());
            return;
        }

        var repository = context.repository();
        if (repository == null) {
            log.warn("Bot command: cannot resolve repository, projectPath={}, noteId={}", safeProjectPath, attrs.id());
            return;
        }

        // Dropped here rather than published unattributed: a command without a named author cannot be
        // authorized downstream, and the subscriber would still spend real budget on it.
        var user = event.user();
        Long providerId = context.providerId();
        Long scopeId = context.scopeId();
        if (user == null || user.id() == null || providerId == null || scopeId == null) {
            log.warn(
                    "Bot command: no identifiable author on the note payload, projectPath={}, mrIid={}, noteId={}",
                    safeProjectPath,
                    mr.iid(),
                    attrs.id());
            return;
        }

        log.info(
                "Bot command detected: command={}, projectPath={}, mrIid={}, author={}, noteId={}",
                attrs.note().strip(),
                safeProjectPath,
                mr.iid(),
                user.username(),
                attrs.id());

        String username = user.username();
        if (username == null) {
            log.warn("Bot command: note author has no username, projectPath={}, mrIid={}", safeProjectPath, mr.iid());
            return;
        }

        eventPublisher.publishEvent(new BotCommandReceivedEvent(
                IntegrationKind.GITLAB,
                repository.getId(),
                mr.iid(),
                attrs.note(),
                username,
                providerId,
                user.id().longValue(),
                attrs.id(),
                scopeId));
    }
}
