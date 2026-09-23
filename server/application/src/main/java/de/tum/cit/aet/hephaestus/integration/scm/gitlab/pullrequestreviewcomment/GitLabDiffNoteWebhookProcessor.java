package de.tum.cit.aet.hephaestus.integration.scm.gitlab.pullrequestreviewcomment;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProvider;
import de.tum.cit.aet.hephaestus.integration.core.spi.RepositoryScopeFilter;
import de.tum.cit.aet.hephaestus.integration.core.spi.ScopeIdResolver;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.ProcessingContext;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.label.LabelRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreview.PullRequestReview;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreviewcomment.PullRequestReviewComment;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreviewcomment.PullRequestReviewCommentRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreviewthread.PullRequestReviewThread;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.RepositoryRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.BaseGitLabProcessor;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabFieldUtils;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.common.GitLabProperties;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.issuecomment.dto.GitLabNoteEventDTO;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.issuecomment.dto.GitLabNoteEventDTO.NoteAttributes;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.pullrequestreview.GitLabReviewReconciler;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.pullrequestreviewthread.GitLabPullRequestReviewThreadProcessor;
import de.tum.cit.aet.hephaestus.integration.scm.gitlab.user.GitLabUserService;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Processes GitLab diff note webhooks into {@link PullRequestReviewComment} + {@link PullRequestReviewThread}.
 * <p>
 * When a diff note webhook arrives, we:
 * <ol>
 *   <li>Find the parent MR (PullRequest) by repository + IID</li>
 *   <li>Create/find a thread using the discussion_id from the webhook payload</li>
 *   <li>Create the review comment within that thread</li>
 * </ol>
 * <p>
 * Note: GitLab webhook payloads include {@code discussion_id} in {@code object_attributes},
 * which uniquely identifies the discussion thread. Reply notes to the same discussion share
 * the same discussion_id, enabling correct thread grouping without waiting for GraphQL sync.
 */
@Service
@ConditionalOnProperty(name = "hephaestus.integration.gitlab.enabled", havingValue = "true", matchIfMissing = false)
public class GitLabDiffNoteWebhookProcessor extends BaseGitLabProcessor {

    private static final Logger log = LoggerFactory.getLogger(GitLabDiffNoteWebhookProcessor.class);

    private final PullRequestRepository pullRequestRepository;
    private final GitLabPullRequestReviewThreadProcessor threadProcessor;
    private final GitLabPullRequestReviewCommentProcessor reviewCommentProcessor;
    private final PullRequestReviewCommentRepository reviewCommentRepository;
    private final GitLabReviewReconciler reviewReconciler;

    public GitLabDiffNoteWebhookProcessor(
            GitLabUserService gitLabUserService,
            UserRepository userRepository,
            LabelRepository labelRepository,
            RepositoryRepository repositoryRepository,
            ScopeIdResolver scopeIdResolver,
            RepositoryScopeFilter repositoryScopeFilter,
            GitLabProperties gitLabProperties,
            PullRequestRepository pullRequestRepository,
            GitLabPullRequestReviewThreadProcessor threadProcessor,
            GitLabPullRequestReviewCommentProcessor reviewCommentProcessor,
            PullRequestReviewCommentRepository reviewCommentRepository,
            GitLabReviewReconciler reviewReconciler) {
        super(
                gitLabUserService,
                userRepository,
                labelRepository,
                repositoryRepository,
                scopeIdResolver,
                repositoryScopeFilter,
                gitLabProperties);
        this.pullRequestRepository = pullRequestRepository;
        this.threadProcessor = threadProcessor;
        this.reviewCommentProcessor = reviewCommentProcessor;
        this.reviewCommentRepository = reviewCommentRepository;
        this.reviewReconciler = reviewReconciler;
    }

    /**
     * Processes a diff note webhook event for a merge request.
     */
    @Transactional
    @Nullable
    public PullRequestReviewComment processDiffNote(GitLabNoteEventDTO event, ProcessingContext context) {
        NoteAttributes attrs = event.objectAttributes();
        GitLabNoteEventDTO.EmbeddedMergeRequest embeddedMr = event.mergeRequest();

        if (attrs == null || attrs.id() == null || embeddedMr == null || embeddedMr.iid() == null) {
            log.warn("Skipped diff note: reason=missingData");
            return null;
        }

        // Find parent MR, creating a minimal stub if it doesn't exist yet
        // (diff note webhooks can arrive before the MR webhook)
        PullRequest pr = pullRequestRepository
                .findByRepositoryIdAndNumber(
                        Objects.requireNonNull(context.repository()).getId(), embeddedMr.iid())
                .orElse(null);

        if (pr == null) {
            pr = createMinimalPullRequestWithRetry(embeddedMr, context);
            if (pr == null) {
                log.warn(
                        "Skipped diff note: reason=failedToCreateParent, mrIid={}, noteId={}",
                        embeddedMr.iid(),
                        attrs.id());
                return null;
            }
        }

        IdentityProvider provider = Objects.requireNonNull(context.provider());

        // Extract position data from the webhook payload
        @SuppressWarnings("unchecked")
        Map<String, Object> position = attrs.position() instanceof Map ? (Map<String, Object>) attrs.position() : null;

        String filePath = null;
        Integer newLine = null;
        Integer oldLine = null;
        String newPath = null;
        String oldPath = null;
        String headSha = null;
        String baseSha = null;
        String startSha = null;

        if (position != null) {
            String np = GitLabFieldUtils.asString(position.get("new_path"));
            filePath = np != null ? np : GitLabFieldUtils.asString(position.get("old_path"));
            newLine = GitLabFieldUtils.toInteger(position.get("new_line"));
            oldLine = GitLabFieldUtils.toInteger(position.get("old_line"));
            newPath = GitLabFieldUtils.asString(position.get("new_path"));
            oldPath = GitLabFieldUtils.asString(position.get("old_path"));
            headSha = GitLabFieldUtils.asString(position.get("head_sha"));
            baseSha = GitLabFieldUtils.asString(position.get("base_sha"));
            startSha = GitLabFieldUtils.asString(position.get("start_sha"));
        }

        // Capture effectively final for lambda
        final String threadPath = filePath;
        final Integer threadLine = GitLabPullRequestReviewThreadProcessor.anchoredLine(newLine, oldLine);

        Instant createdAt = parseWebhookTimestamp(attrs.createdAt());
        Instant updatedAt = parseWebhookTimestamp(attrs.updatedAt());

        // Use the discussion_id from the webhook as the thread nativeId.
        // This correctly groups reply notes into the same thread and matches the
        // GraphQL sync's discussion-based threads. Falls back to note ID if absent.
        long threadNativeId;
        if (attrs.discussionId() != null) {
            // Build the same GID format as the GraphQL sync uses, then hash with the same algorithm
            String discussionGid = "gid://gitlab/Discussion/" + attrs.discussionId();
            threadNativeId = GitLabPullRequestReviewThreadProcessor.deterministicNativeId(discussionGid);
        } else {
            log.warn("Diff note webhook missing discussion_id, falling back to note ID: noteId={}", attrs.id());
            threadNativeId = attrs.id();
        }
        var webhookThreadData = new GitLabPullRequestReviewThreadProcessor.WebhookThreadData(
                threadNativeId, threadPath, threadLine, createdAt, updatedAt);
        PullRequestReviewThread thread = threadProcessor.findOrCreateWebhookThread(webhookThreadData, pr, provider);

        // Resolve author
        User author = findOrCreateUser(event.user(), Objects.requireNonNull(context.providerId()));

        // Create the diff note data
        String noteGlobalId = "gid://gitlab/DiffNote/" + attrs.id();

        var noteData = new GitLabPullRequestReviewCommentProcessor.DiffNoteData(
                noteGlobalId,
                attrs.note(),
                attrs.url(),
                filePath,
                newLine,
                oldLine,
                newPath,
                oldPath,
                headSha,
                baseSha,
                startSha,
                createdAt,
                updatedAt);

        // Resolve parent: if the thread already has a starter comment, this note is a reply.
        // This mirrors GitLab's semantics where all notes in a discussion share the same
        // discussion_id; the earliest note is the thread starter and subsequent notes reply to it.
        PullRequestReviewComment inReplyTo = thread.getId() != null
                ? reviewCommentRepository
                        .findFirstByThreadIdOrderByCreatedAtAsc(thread.getId())
                        .orElse(null)
                : null;

        // Reconcile a synthetic COMMENTED review per (author, discussion) so the note links
        // to a review row, matching GitHub parity and unblocking profile/leaderboard scoring.
        PullRequestReview review = null;
        if (author != null && attrs.discussionId() != null) {
            String discussionGid = "gid://gitlab/Discussion/" + attrs.discussionId();
            review = reviewReconciler.findOrCreateCommentedReview(
                    pr, author, discussionGid, createdAt, provider, context);
        }

        var commentContext = new GitLabPullRequestReviewCommentProcessor.CommentContext(
                thread, pr, author, provider, inReplyTo, review, Objects.requireNonNull(context.scopeId()));
        PullRequestReviewComment comment = reviewCommentProcessor.findOrCreateComment(noteData, commentContext);

        if (comment != null) {
            log.debug("Processed diff note webhook: noteId={}, mrIid={}", attrs.id(), embeddedMr.iid());
        }

        return comment;
    }

    @Nullable
    private static Instant parseWebhookTimestamp(@Nullable String value) {
        // Delegates to BaseGitLabProcessor which handles both webhook format
        // ("2026-01-31 19:03:35 +0100") and ISO-8601 ("2026-01-31T19:03:35Z")
        return parseGitLabTimestamp(value);
    }

    /**
     * Creates a minimal stub PullRequest from the embedded MR data in a note webhook,
     * with retry on concurrent creation (DataIntegrityViolationException).
     */
    @Nullable
    private PullRequest createMinimalPullRequestWithRetry(
            GitLabNoteEventDTO.EmbeddedMergeRequest dto, ProcessingContext context) {
        try {
            return createMinimalPullRequest(dto, context);
        } catch (DataIntegrityViolationException e) {
            log.debug("Concurrent MR creation, looking up: iid={}", dto.iid());
            return pullRequestRepository
                    .findByRepositoryIdAndNumber(
                            Objects.requireNonNull(context.repository()).getId(), dto.iid())
                    .orElse(null);
        }
    }

    @Nullable
    private PullRequest createMinimalPullRequest(
            GitLabNoteEventDTO.EmbeddedMergeRequest dto, ProcessingContext context) {
        Repository repository = Objects.requireNonNull(context.repository());
        if (repository == null || dto.id() == null) return null;

        Issue.State mappedState = convertMrState(dto.state());

        PullRequest pr = new PullRequest();
        pr.setNativeId(dto.id());
        pr.setProvider(Objects.requireNonNull(context.provider()));
        pr.setNumber(dto.iid());
        pr.setTitle(Objects.requireNonNullElse(sanitize(dto.title()), ""));
        pr.setBody(sanitize(dto.description()));
        pr.setState(mappedState);
        pr.setHtmlUrl(dto.url());
        pr.setDraft(dto.draft());
        pr.setMerged(mappedState == Issue.State.MERGED);
        pr.setAdditions(0);
        pr.setDeletions(0);
        pr.setChangedFiles(0);
        pr.setCommits(0);
        pr.setHeadRefName(dto.sourceBranch());
        pr.setBaseRefName(dto.targetBranch());
        pr.setCreatedAt(parseGitLabTimestamp(dto.createdAt()));
        pr.setUpdatedAt(parseGitLabTimestamp(dto.updatedAt()));
        pr.setRepository(repository);
        pr.setLastSyncAt(Instant.now());

        PullRequest saved = pullRequestRepository.save(pr);
        log.info(
                "Created stub PullRequest from diff note webhook: prId={}, iid={}, repo={}",
                saved.getId(),
                saved.getNumber(),
                repository.getNameWithOwner());
        return saved;
    }

    private static Issue.State convertMrState(@Nullable String state) {
        if (state == null) return Issue.State.OPEN;
        return switch (state.toLowerCase()) {
            case "opened" -> Issue.State.OPEN;
            case "closed" -> Issue.State.CLOSED;
            case "merged" -> Issue.State.MERGED;
            default -> Issue.State.OPEN;
        };
    }
}
