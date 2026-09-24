package de.tum.cit.aet.hephaestus.integration.scm.github.pullrequestreviewcomment;

import de.tum.cit.aet.hephaestus.integration.core.events.EventContext;
import de.tum.cit.aet.hephaestus.integration.core.events.ScmDomainEvent;
import de.tum.cit.aet.hephaestus.integration.core.events.ScmEventPayload;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.AuthorAssociation;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.ProcessingContext;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreview.PullRequestReviewRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreviewcomment.PullRequestReviewComment;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreviewcomment.PullRequestReviewCommentRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreviewthread.PullRequestReviewThread;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreviewthread.PullRequestReviewThreadRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.github.pullrequest.dto.GitHubPullRequestDTO;
import de.tum.cit.aet.hephaestus.integration.scm.github.pullrequestreviewcomment.dto.GitHubPullRequestReviewCommentEventDTO;
import de.tum.cit.aet.hephaestus.integration.scm.github.user.GitHubUserProcessor;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Processor for GitHub pull request review comments.
 * <p>
 * This service handles the conversion of GitHubReviewCommentDTO to PullRequestReviewComment entities,
 * persists them, and publishes appropriate domain events.
 * <p>
 * <b>Design Principles:</b>
 * <ul>
 * <li>Single processing path for all data sources (sync and webhooks)</li>
 * <li>Idempotent operations via upsert pattern</li>
 * <li>Domain events published for reactive feature development</li>
 * <li>Works exclusively with DTOs for complete field coverage</li>
 * </ul>
 */
@Service
public class GitHubPullRequestReviewCommentProcessor {

    private static final Logger log = LoggerFactory.getLogger(GitHubPullRequestReviewCommentProcessor.class);

    private final PullRequestReviewCommentRepository commentRepository;
    private final PullRequestRepository prRepository;
    private final PullRequestReviewRepository reviewRepository;
    private final PullRequestReviewThreadRepository threadRepository;
    private final GitHubUserProcessor userProcessor;
    private final ApplicationEventPublisher eventPublisher;

    public GitHubPullRequestReviewCommentProcessor(
            PullRequestReviewCommentRepository commentRepository,
            PullRequestRepository prRepository,
            PullRequestReviewRepository reviewRepository,
            PullRequestReviewThreadRepository threadRepository,
            GitHubUserProcessor userProcessor,
            ApplicationEventPublisher eventPublisher) {
        this.commentRepository = commentRepository;
        this.prRepository = prRepository;
        this.reviewRepository = reviewRepository;
        this.threadRepository = threadRepository;
        this.userProcessor = userProcessor;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Process a GitHub review comment DTO and persist it as a PullRequestReviewComment entity.
     * <p>
     * This overload requires the parent PR to already exist in the database.
     * For webhook processing where the parent might not exist, use
     * {@link #processCreatedWithParentCreation(GitHubPullRequestReviewCommentEventDTO.GitHubReviewCommentDTO, GitHubPullRequestDTO, ProcessingContext)} instead.
     *
     * @param dto the GitHub review comment DTO
     * @param repositoryId the database ID of the repository
     * @param prNumber the pull request number within the repository
     * @param context processing context with scope information
     * @return the persisted PullRequestReviewComment entity, or null if processing failed
     */
    @Transactional
    public @Nullable PullRequestReviewComment processCreated(
            GitHubPullRequestReviewCommentEventDTO.GitHubReviewCommentDTO dto,
            long repositoryId,
            int prNumber,
            @NonNull ProcessingContext context) {
        if (dto == null || dto.id() == null) {
            log.warn("Skipped review comment processing: reason=nullOrMissingId");
            return null;
        }

        if (commentRepository.existsByNativeIdAndProviderId(dto.id(), Objects.requireNonNull(context.providerId()))) {
            log.debug("Skipped comment creation: reason=alreadyExists, commentId={}", dto.id());
            return null;
        }

        PullRequest pr =
                prRepository.findByRepositoryIdAndNumber(repositoryId, prNumber).orElse(null);
        if (pr == null) {
            log.warn(
                    "Skipped comment creation: reason=prNotFound, repositoryId={}, prNumber={}, commentId={}",
                    repositoryId,
                    prNumber,
                    dto.id());
            return null;
        }

        return processCreatedInternal(dto, pr, context);
    }

    /**
     * Process a GitHub review comment DTO and persist it as a PullRequestReviewComment entity,
     * creating a minimal parent PR entity if it doesn't exist.
     * <p>
     * This method solves the message ordering problem where review comment webhooks arrive
     * before the parent PR webhook. Instead of failing and retrying, we create
     * a minimal parent entity from the webhook's embedded PR data. The full entity
     * will be updated later when the proper PR webhook arrives or during GraphQL sync.
     * <p>
     * <b>Why this approach?</b>
     * <ul>
     *   <li>GitHub sends pull_request_review_comment events with embedded PR data</li>
     *   <li>The webhook includes PR id, number, title, body, state, etc.</li>
     *   <li>Creating a stub entity is better than losing data or retrying indefinitely</li>
     *   <li>The stub will be hydrated by the PR webhook or scheduled sync</li>
     * </ul>
     *
     * @param dto the GitHub review comment DTO
     * @param prDto the GitHub PR DTO from the webhook (contains parent entity data)
     * @param context processing context with scope information
     * @return the persisted PullRequestReviewComment entity, or null if processing failed
     */
    @Transactional
    public @Nullable PullRequestReviewComment processCreatedWithParentCreation(
            GitHubPullRequestReviewCommentEventDTO.GitHubReviewCommentDTO dto,
            GitHubPullRequestDTO prDto,
            ProcessingContext context) {
        if (dto == null || dto.id() == null) {
            log.warn("Skipped review comment processing: reason=nullOrMissingId");
            return null;
        }

        if (commentRepository.existsByNativeIdAndProviderId(dto.id(), Objects.requireNonNull(context.providerId()))) {
            log.debug("Skipped comment creation: reason=alreadyExists, commentId={}", dto.id());
            return null;
        }

        // Try to find existing parent entity using repository ID and PR number (not database ID)
        // This avoids inconsistencies between GraphQL and webhook database IDs
        PullRequest pr = prRepository
                .findByRepositoryIdAndNumber(
                        Objects.requireNonNull(context.repository()).getId(), prDto.number())
                .orElse(null);

        // If parent doesn't exist, create a minimal entity from webhook data
        if (pr == null) {
            pr = createMinimalPullRequest(prDto, context);
            if (pr == null) {
                log.warn(
                        "Skipped review comment processing: reason=failedToCreateParent, prNumber={}, commentId={}",
                        prDto.number(),
                        dto.id());
                return null;
            }
        }

        return processCreatedInternal(dto, pr, context);
    }

    /**
     * Internal method that processes a review comment given a resolved parent PR.
     */
    private PullRequestReviewComment processCreatedInternal(
            GitHubPullRequestReviewCommentEventDTO.GitHubReviewCommentDTO dto,
            PullRequest pr,
            @NonNull ProcessingContext context) {
        PullRequestReviewThread thread = resolveThread(dto, pr);
        PullRequestReviewComment comment = createComment(dto, pr, thread);

        PullRequestReviewComment saved = commentRepository.save(comment);
        eventPublisher.publishEvent(new ScmDomainEvent.ReviewCommentCreated(
                ScmEventPayload.ReviewCommentData.from(saved), pr.getId(), EventContext.from(context)));
        log.debug("Created review comment: commentId={}, threadId={}", dto.id(), thread.getId());
        return saved;
    }

    @Transactional
    public @Nullable PullRequestReviewComment processEdited(
            GitHubPullRequestReviewCommentEventDTO.GitHubReviewCommentDTO dto,
            Long prId,
            @NonNull ProcessingContext context) {
        return commentRepository
                .findByNativeIdAndProviderId(dto.id(), Objects.requireNonNull(context.providerId()))
                .map(comment -> {
                    comment.setBody(dto.body());
                    comment.setUpdatedAt(dto.updatedAt());
                    // Rows stored before the side was kept get it on their next edit or sync.
                    if (dto.side() != null && comment.getSide() == null) {
                        comment.setSide(mapSide(dto.side()));
                    }
                    PullRequestReviewComment saved = commentRepository.save(comment);
                    eventPublisher.publishEvent(new ScmDomainEvent.ReviewCommentEdited(
                            ScmEventPayload.ReviewCommentData.from(saved),
                            prId,
                            Set.of("body"),
                            EventContext.from(context)));
                    log.debug("Updated review comment: commentId={}", dto.id());
                    return saved;
                })
                .orElseGet(() -> {
                    log.debug("Skipped comment edit: reason=commentNotFound, commentId={}", dto.id());
                    return null;
                });
    }

    @Transactional
    public void processDeleted(Long commentId, Long prId, @NonNull ProcessingContext context) {
        commentRepository
                .findByNativeIdAndProviderId(commentId, Objects.requireNonNull(context.providerId()))
                .ifPresent(comment -> {
                    // CRITICAL: For bidirectional @OneToMany with orphanRemoval=true,
                    // we MUST remove the comment from the thread's collection BEFORE deleting.
                    // Otherwise, the thread's in-memory collection still references the deleted
                    // entity, causing TransientObjectException on flush.
                    PullRequestReviewThread thread = comment.getThread();
                    if (thread != null) {
                        thread.getComments().remove(comment);
                    }

                    // Also clean up the inReplyTo relationship to avoid dangling references
                    comment.getReplies().forEach(reply -> reply.setInReplyTo(null));

                    commentRepository.delete(comment);
                    log.debug(
                            "Deleted review comment: commentId={}, threadId={}",
                            commentId,
                            thread != null ? thread.getId() : "null");
                });

        eventPublisher.publishEvent(
                new ScmDomainEvent.ReviewCommentDeleted(commentId, prId, EventContext.from(context)));
    }

    private PullRequestReviewComment createComment(
            GitHubPullRequestReviewCommentEventDTO.GitHubReviewCommentDTO dto,
            PullRequest pr,
            PullRequestReviewThread thread) {
        PullRequestReviewComment comment = new PullRequestReviewComment();
        comment.setNativeId(dto.id());
        comment.setProvider(pr.requireRepository().getProvider());
        comment.setBody(dto.body());
        comment.setDiffHunk(dto.diffHunk());
        comment.setPath(dto.path());
        // htmlUrl is required - use DTO value or construct from PR htmlUrl and comment ID
        if (dto.htmlUrl() != null) {
            comment.setHtmlUrl(dto.htmlUrl());
        } else if (pr.getHtmlUrl() != null && dto.id() != null) {
            // Construct htmlUrl from PR URL and comment ID as fallback
            comment.setHtmlUrl(pr.getHtmlUrl() + "#discussion_r" + dto.id());
            log.debug("Constructed htmlUrl from PR URL for review comment: commentId={}", dto.id());
        } else {
            // Last resort fallback - should rarely happen
            log.warn("Unable to determine htmlUrl for review comment: commentId={}, prId={}", dto.id(), pr.getId());
            comment.setHtmlUrl("https://github.com");
        }
        comment.setCreatedAt(dto.createdAt());
        comment.setUpdatedAt(dto.updatedAt());
        comment.setPullRequest(pr);
        comment.setThread(thread);

        // Set required fields with sensible defaults
        comment.setCommitId(dto.commitId() != null ? dto.commitId() : "");
        comment.setOriginalCommitId(
                dto.originalCommitId() != null ? dto.originalCommitId() : dto.commitId() != null ? dto.commitId() : "");
        comment.setAuthorAssociation(mapAuthorAssociation(dto.authorAssociation()));
        comment.setLine(dto.line() != null ? dto.line() : 0);
        comment.setOriginalLine(dto.originalLine() != null ? dto.originalLine() : comment.getLine());

        // Optional multi-line fields
        if (dto.startLine() != null) {
            comment.setStartLine(dto.startLine());
        }
        if (dto.originalStartLine() != null) {
            comment.setOriginalStartLine(dto.originalStartLine());
        }

        // Outdated flag (whether the comment's code context has changed)
        comment.setOutdated(dto.outdated());

        // Which side of the diff the comment sits on: the webhook's own `side`, the sync's thread side.
        if (dto.side() != null) {
            comment.setSide(mapSide(dto.side()));
        }
        if (dto.startSide() != null) {
            comment.setStartSide(mapSide(dto.startSide()));
        }

        // Link to review if present (reviewId is the provider's native ID, not the JPA PK)
        if (dto.reviewId() != null) {
            reviewRepository
                    .findByNativeIdAndProviderId(
                            dto.reviewId(),
                            Objects.requireNonNull(
                                    pr.requireRepository().getProvider().getId()))
                    .ifPresent(comment::setReview);
        }

        // Link author if present - ensure user exists (create if needed)
        if (dto.author() != null) {
            User author = userProcessor.ensureExists(
                    dto.author(),
                    Objects.requireNonNull(pr.requireRepository().getProvider().getId()));
            if (author != null) {
                comment.setAuthor(author);
            }
        }

        // Link to parent comment if this is a reply
        if (dto.inReplyToId() != null) {
            commentRepository
                    .findByNativeIdAndProviderId(
                            dto.inReplyToId(),
                            Objects.requireNonNull(
                                    pr.requireRepository().getProvider().getId()))
                    .ifPresent(comment::setInReplyTo);
        }

        return comment;
    }

    /**
     * Resolves or creates a thread for a comment.
     * <p>
     * If the comment is a reply (has inReplyToId), use the parent comment's thread.
     * Otherwise, create a new thread using the comment ID as the thread ID (synthetic thread).
     * <p>
     * <b>CRITICAL for reply ordering:</b> When a reply arrives before its parent comment,
     * we use the PARENT's ID as the thread ID (not this comment's ID). This ensures that
     * when the parent comment eventually arrives, it will be added to the same thread,
     * preserving the threading relationship.
     */
    private PullRequestReviewThread resolveThread(
            GitHubPullRequestReviewCommentEventDTO.GitHubReviewCommentDTO dto, PullRequest pr) {
        // If this is a reply, use the parent comment's thread
        if (dto.inReplyToId() != null) {
            // First try to get the thread from the existing parent comment
            Long providerId =
                    Objects.requireNonNull(pr.requireRepository().getProvider().getId());
            var parentThread = commentRepository
                    .findByNativeIdAndProviderId(dto.inReplyToId(), providerId)
                    .map(PullRequestReviewComment::getThread)
                    .orElse(null);

            if (parentThread != null) {
                return parentThread;
            }

            // Parent comment doesn't exist yet (out-of-order message delivery).
            // Create/find a thread using the PARENT's ID so that when the parent
            // arrives later, it will find the same thread. This preserves threading.
            log.debug(
                    "Reply arrived before parent: commentId={}, inReplyToId={}, using parent's ID for thread",
                    dto.id(),
                    dto.inReplyToId());
            return findOrCreateThreadForParent(dto.inReplyToId(), dto, pr);
        }

        // Root comment - create a new thread using the comment ID as thread ID
        return createSyntheticThread(dto, pr);
    }

    /**
     * Finds or creates a thread for a parent comment that hasn't been synced yet.
     * Uses the PARENT's ID as the thread ID to ensure proper thread association
     * when the parent comment arrives later.
     */
    private PullRequestReviewThread findOrCreateThreadForParent(
            Long parentCommentId, GitHubPullRequestReviewCommentEventDTO.GitHubReviewCommentDTO dto, PullRequest pr) {
        Long providerId =
                Objects.requireNonNull(pr.requireRepository().getProvider().getId());
        return threadRepository
                .findByNativeIdAndProviderId(parentCommentId, providerId)
                .orElseGet(() -> {
                    PullRequestReviewThread thread = new PullRequestReviewThread();
                    thread.setNativeId(parentCommentId); // Use parent's ID, not this comment's ID
                    thread.setProvider(pr.requireRepository().getProvider());
                    thread.setPullRequest(pr);
                    thread.setPath(dto.path()); // Use reply's path as best guess
                    thread.setLine(dto.line());
                    thread.setStartLine(dto.startLine());
                    thread.setSide(mapSide(Objects.requireNonNullElse(dto.side(), "RIGHT")));
                    thread.setState(PullRequestReviewThread.State.UNRESOLVED);
                    thread.setCreatedAt(dto.createdAt());
                    thread.setUpdatedAt(dto.updatedAt());
                    return threadRepository.save(thread);
                });
    }

    /**
     * Creates a synthetic thread for a root comment.
     * Uses the comment ID as the thread ID since GitHub doesn't provide explicit thread IDs in webhooks.
     */
    private PullRequestReviewThread createSyntheticThread(
            GitHubPullRequestReviewCommentEventDTO.GitHubReviewCommentDTO dto, PullRequest pr) {
        Long providerId =
                Objects.requireNonNull(pr.requireRepository().getProvider().getId());
        return threadRepository
                .findByNativeIdAndProviderId(dto.id(), providerId)
                .orElseGet(() -> {
                    PullRequestReviewThread thread = new PullRequestReviewThread();
                    thread.setNativeId(dto.id());
                    thread.setProvider(pr.requireRepository().getProvider());
                    thread.setPullRequest(pr);
                    thread.setPath(dto.path());
                    thread.setLine(dto.line());
                    thread.setStartLine(dto.startLine());
                    thread.setSide(mapSide(Objects.requireNonNullElse(dto.side(), "RIGHT")));
                    thread.setState(PullRequestReviewThread.State.UNRESOLVED);
                    thread.setCreatedAt(dto.createdAt());
                    thread.setUpdatedAt(dto.updatedAt());
                    return threadRepository.save(thread);
                });
    }

    /**
     * Maps string author association to enum.
     */
    private AuthorAssociation mapAuthorAssociation(String value) {
        if (value == null) {
            log.debug("Author association is null, using NONE");
            return AuthorAssociation.NONE;
        }
        return switch (value.toUpperCase()) {
            case "COLLABORATOR" -> AuthorAssociation.COLLABORATOR;
            case "CONTRIBUTOR" -> AuthorAssociation.CONTRIBUTOR;
            case "FIRST_TIMER" -> AuthorAssociation.FIRST_TIMER;
            case "FIRST_TIME_CONTRIBUTOR" -> AuthorAssociation.FIRST_TIME_CONTRIBUTOR;
            case "MANNEQUIN" -> AuthorAssociation.MANNEQUIN;
            case "MEMBER" -> AuthorAssociation.MEMBER;
            case "NONE" -> AuthorAssociation.NONE;
            case "OWNER" -> AuthorAssociation.OWNER;
            default -> {
                log.warn("Unknown author association '{}', using NONE", value);
                yield AuthorAssociation.NONE;
            }
        };
    }

    /**
     * Maps string side value to enum.
     */
    private PullRequestReviewComment.Side mapSide(String value) {
        if (value == null) {
            log.debug("Comment side is null, using RIGHT as default");
            return PullRequestReviewComment.Side.RIGHT;
        }
        return switch (value.toUpperCase()) {
            case "LEFT" -> PullRequestReviewComment.Side.LEFT;
            case "RIGHT" -> PullRequestReviewComment.Side.RIGHT;
            default -> {
                log.debug("Unknown comment side '{}', using UNKNOWN", value);
                yield PullRequestReviewComment.Side.UNKNOWN;
            }
        };
    }

    // Private Helper Methods

    /**
     * Creates a minimal PullRequest entity from webhook data.
     * <p>
     * This method is called when a review comment webhook arrives before the parent PR exists.
     * We create a "stub" entity with the data available in the webhook payload. The full
     * entity will be populated later by the proper PR webhook or GraphQL sync.
     * <p>
     * <b>Hydration Strategy:</b> Stubs rely on natural hydration through:
     * <ol>
     *   <li>The pull_request webhook (typically arrives within seconds)</li>
     *   <li>The scheduled GraphQL sync (safety net for missed webhooks)</li>
     * </ol>
     *
     * @param dto the PR DTO from the webhook payload
     * @param context the processing context with repository information
     * @return the created PullRequest entity, or null if creation failed
     */
    @Nullable
    private PullRequest createMinimalPullRequest(GitHubPullRequestDTO dto, ProcessingContext context) {
        Repository repository = context.repository();
        if (repository == null) {
            log.warn("Cannot create parent PR: reason=noRepository, prId={}", dto.getDatabaseId());
            return null;
        }

        Long prId = dto.getDatabaseId();
        if (prId == null) {
            return null;
        }

        PullRequest pr = new PullRequest();
        pr.setNativeId(prId);
        pr.setProvider(Objects.requireNonNull(context.provider()));
        pr.setNumber(dto.number());
        pr.setTitle(Objects.requireNonNullElse(sanitize(dto.title()), ""));
        pr.setBody(sanitize(dto.body()));
        pr.setState(convertState(Objects.requireNonNullElse(dto.state(), "OPEN")));
        pr.setHtmlUrl(dto.htmlUrl());
        pr.setCreatedAt(dto.createdAt());
        pr.setUpdatedAt(dto.updatedAt());
        pr.setClosedAt(dto.closedAt());
        pr.setMergedAt(dto.mergedAt());
        pr.setDraft(dto.isDraft());
        pr.setMerged(dto.isMerged());
        pr.setLocked(dto.locked());
        pr.setAdditions(dto.additions());
        pr.setDeletions(dto.deletions());
        pr.setChangedFiles(dto.changedFiles());
        pr.setCommits(dto.commits());
        pr.setCommentsCount(dto.commentsCount());
        pr.setRepository(repository);
        pr.setLastSyncAt(Instant.now());

        // Head/base branch references
        if (dto.head() != null) {
            pr.setHeadRefName(dto.head().ref());
            pr.setHeadRefOid(dto.head().sha());
        }
        if (dto.base() != null) {
            pr.setBaseRefName(dto.base().ref());
            pr.setBaseRefOid(dto.base().sha());
        }

        // Link author
        if (dto.author() != null) {
            User author = userProcessor.ensureExists(
                    dto.author(),
                    Objects.requireNonNull(repository.getProvider().getId()));
            pr.setAuthor(author);
        }

        PullRequest saved = prRepository.save(pr);
        log.info(
                "Created stub PullRequest from review comment webhook (will be hydrated by PR webhook or sync): "
                        + "prId={}, prNumber={}, repoName={}",
                saved.getId(),
                saved.getNumber(),
                repository.getNameWithOwner());
        return saved;
    }

    /**
     * Sanitizes a string for safe storage.
     */
    @Nullable
    private String sanitize(@Nullable String value) {
        if (value == null) {
            return null;
        }
        // Replace null bytes which can cause issues with PostgreSQL
        return value.replace("\u0000", "");
    }

    /**
     * Converts a GitHub API state string to Issue.State enum.
     */
    private Issue.State convertState(String state) {
        if (state == null) {
            log.warn("PR state is null when creating stub from review comment webhook, defaulting to OPEN. "
                    + "This may indicate missing data in webhook payload.");
            return Issue.State.OPEN;
        }
        return switch (state.toUpperCase()) {
            case "OPEN" -> Issue.State.OPEN;
            case "CLOSED" -> Issue.State.CLOSED;
            case "MERGED" -> Issue.State.MERGED;
            default -> {
                log.warn(
                        "Unknown PR state '{}' when creating stub from review comment webhook, defaulting to OPEN",
                        state);
                yield Issue.State.OPEN;
            }
        };
    }
}
