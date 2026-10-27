package de.tum.cit.aet.hephaestus.integration.scm.github.issuecomment;

import de.tum.cit.aet.hephaestus.integration.core.connection.IdentityProviderType;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.AuthorAssociation;
import de.tum.cit.aet.hephaestus.integration.scm.domain.common.ProcessingContext;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.IssueRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issuecomment.IssueComment;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issuecomment.IssueCommentRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.item.ScmItemEvents;
import de.tum.cit.aet.hephaestus.integration.scm.domain.label.Label;
import de.tum.cit.aet.hephaestus.integration.scm.domain.label.LabelRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.milestone.Milestone;
import de.tum.cit.aet.hephaestus.integration.scm.domain.milestone.MilestoneRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequest;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.repository.Repository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.User;
import de.tum.cit.aet.hephaestus.integration.scm.domain.user.UserRepository;
import de.tum.cit.aet.hephaestus.integration.scm.events.EventContext;
import de.tum.cit.aet.hephaestus.integration.scm.events.ScmDomainEvent;
import de.tum.cit.aet.hephaestus.integration.scm.events.ScmEventPayload;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.BaseGitHubProcessor;
import de.tum.cit.aet.hephaestus.integration.scm.github.common.GitHubMisfiledWork;
import de.tum.cit.aet.hephaestus.integration.scm.github.issue.dto.GitHubIssueDTO;
import de.tum.cit.aet.hephaestus.integration.scm.github.issuecomment.dto.GitHubIssueCommentEventDTO.GitHubCommentDTO;
import de.tum.cit.aet.hephaestus.integration.scm.github.label.dto.GitHubLabelDTO;
import de.tum.cit.aet.hephaestus.integration.scm.github.user.GitHubUserProcessor;
import de.tum.cit.aet.hephaestus.integration.scm.github.user.dto.GitHubUserDTO;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Processor for GitHub issue comments.
 * <p>
 * This service handles the conversion of GitHubCommentDTO to IssueComment entities,
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
public class GitHubIssueCommentProcessor extends BaseGitHubProcessor {

    private static final Logger log = LoggerFactory.getLogger(GitHubIssueCommentProcessor.class);

    private final IssueCommentRepository commentRepository;
    private final IssueRepository issueRepository;
    private final PullRequestRepository pullRequestRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final GitHubMisfiledWork misfiledWork;

    public GitHubIssueCommentProcessor(
            IssueCommentRepository commentRepository,
            IssueRepository issueRepository,
            PullRequestRepository pullRequestRepository,
            UserRepository userRepository,
            LabelRepository labelRepository,
            MilestoneRepository milestoneRepository,
            GitHubUserProcessor gitHubUserProcessor,
            ApplicationEventPublisher eventPublisher,
            GitHubMisfiledWork misfiledWork) {
        super(userRepository, labelRepository, milestoneRepository, gitHubUserProcessor);
        this.commentRepository = commentRepository;
        this.issueRepository = issueRepository;
        this.pullRequestRepository = pullRequestRepository;
        this.eventPublisher = eventPublisher;
        this.misfiledWork = misfiledWork;
    }

    /**
     * Process a GitHub comment DTO and persist it as an IssueComment entity.
     * <p>
     * This overload requires the parent issue/PR to already exist in the database.
     * For webhook processing where the parent might not exist, use
     * {@link #processWithParentCreation(GitHubCommentDTO, GitHubIssueDTO, ProcessingContext)} instead.
     *
     * @param dto the GitHub comment DTO
     * @param issueNumber the issue number within the repository (natural key, consistent across all sources)
     * @param context processing context with scope information (must contain repository)
     * @return the persisted IssueComment entity, or null if processing failed
     */
    @Transactional
    public @Nullable IssueComment process(@Nullable GitHubCommentDTO dto, int issueNumber, ProcessingContext context) {
        if (dto == null || dto.id() == null) {
            log.warn("Skipped comment processing: reason=nullOrMissingId");
            return null;
        }

        if (context.repository() == null) {
            log.warn("Skipped comment processing: reason=noRepository, commentId={}", dto.id());
            return null;
        }

        Issue issue = findParent(context.repository(), issueNumber, context);
        if (issue == null) {
            log.warn(
                    "Skipped comment processing: reason=parentNotFound, repoId={}, issueNumber={}, commentId={}",
                    context.repository().getId(),
                    issueNumber,
                    dto.id());
            return null;
        }

        return processCommentInternal(dto, issue, context);
    }

    /**
     * Process a GitHub comment DTO and persist it as an IssueComment entity,
     * creating a minimal parent entity (Issue or PullRequest) if it doesn't exist.
     * <p>
     * This method solves the message ordering problem where comment webhooks arrive
     * before the parent issue/PR webhook. Instead of failing and retrying, we create
     * a minimal parent entity from the webhook's embedded issue data. The full entity
     * will be updated later when the proper issue/PR webhook arrives or during GraphQL sync.
     * <p>
     * <b>Why this approach?</b>
     * <ul>
     *   <li>GitHub sends issue_comment events for both issues AND pull requests</li>
     *   <li>The webhook includes full issue data (id, number, title, body, state, etc.)</li>
     *   <li>For PRs, the issue.pull_request field is populated (minimal info)</li>
     *   <li>Creating a stub entity is better than losing data or retrying indefinitely</li>
     * </ul>
     *
     * @param dto the GitHub comment DTO
     * @param issueDto the GitHub issue DTO from the webhook (contains parent entity data)
     * @param context processing context with scope information
     * @return the persisted IssueComment entity, or null if processing failed
     */
    @Transactional
    public @Nullable IssueComment processWithParentCreation(
            GitHubCommentDTO dto, GitHubIssueDTO issueDto, ProcessingContext context) {
        if (dto == null || dto.id() == null) {
            log.warn("Skipped comment processing: reason=nullOrMissingId");
            return null;
        }

        if (context.repository() == null) {
            log.warn("Skipped comment processing: reason=noRepository, commentId={}", dto.id());
            return null;
        }

        if (issueDto.number() <= 0) {
            log.warn(
                    "Skipped comment processing: reason=invalidIssueNumber, issueNumber={}, commentId={}",
                    issueDto.number(),
                    dto.id());
            return null;
        }

        Issue issue = findParent(context.repository(), issueDto.number(), context);

        // An issue row filed under another repository holds the issue's id; a stub here would collide with it. A pull
        // request's comment names the issue resource id, which its row does not hold.
        Long issueId = issueDto.getDatabaseId();
        if (issue == null && !issueDto.isPullRequest() && issueId != null) {
            var relocation = misfiledWork.relocate(
                    Objects.requireNonNull(context.repository()),
                    Objects.requireNonNull(context.providerId(), "Comment processing requires a provider"),
                    issueId,
                    issueDto.number(),
                    "ISSUE",
                    ScmItemEvents.ISSUE);
            if (relocation == GitHubMisfiledWork.Outcome.SKIPPED) return null;
            if (relocation == GitHubMisfiledWork.Outcome.MOVED)
                issue = findParent(context.repository(), issueDto.number(), context);
        }

        // If parent doesn't exist, create a minimal entity from webhook data
        if (issue == null) {
            issue = createMinimalParentEntityWithRetry(issueDto, context);
            if (issue == null) {
                log.warn(
                        "Skipped comment processing: reason=failedToCreateParent, repoId={}, issueNumber={}, commentId={}",
                        context.repository().getId(),
                        issueDto.number(),
                        dto.id());
                return null;
            }
        }

        return processCommentInternal(dto, issue, context);
    }

    /**
     * Internal method that processes a comment given a resolved parent Issue/PR.
     */
    private IssueComment processCommentInternal(GitHubCommentDTO dto, Issue issue, ProcessingContext context) {
        Long issueId = issue.getId();
        Long commentId = Objects.requireNonNull(dto.id());
        Long providerId = Objects.requireNonNull(context.providerId());
        Optional<IssueComment> existingOpt =
                commentRepository.findForUpdateByNativeIdAndProviderId(commentId, providerId);
        boolean isNew = existingOpt.isEmpty();
        // An older payload would restore older words (sync-lifecycle.md).
        if (!isNew
                && dto.updatedAt() != null
                && existingOpt.get().getUpdatedAt() != null
                && dto.updatedAt().isBefore(existingOpt.get().getUpdatedAt())) {
            log.debug(
                    "Skipped stale comment update: commentId={}",
                    existingOpt.get().getId());
            return existingOpt.get();
        }

        IssueComment comment = existingOpt.orElseGet(IssueComment::new);
        Set<String> changedFields = new HashSet<>();

        // Set nativeId and provider for new comments
        if (isNew) {
            comment.setNativeId(commentId);
            comment.setProvider(Objects.requireNonNull(context.provider()));
        }

        // Update body if changed
        if (dto.body() != null && !dto.body().equals(comment.getBody())) {
            changedFields.add("body");
            comment.setBody(dto.body());
        }

        // Set html URL (only on create typically)
        if (dto.htmlUrl() != null && !dto.htmlUrl().equals(comment.getHtmlUrl())) {
            changedFields.add("htmlUrl");
            comment.setHtmlUrl(dto.htmlUrl());
        }

        // Set timestamps
        if (dto.createdAt() != null) {
            comment.setCreatedAt(dto.createdAt());
        }
        if (dto.updatedAt() != null) {
            comment.setUpdatedAt(dto.updatedAt());
        }

        // Set author association
        AuthorAssociation authorAssociation = AuthorAssociation.fromString(dto.authorAssociation());
        if (!authorAssociation.equals(comment.getAuthorAssociation())) {
            changedFields.add("authorAssociation");
            comment.setAuthorAssociation(authorAssociation);
        }

        // Set issue relationship
        comment.setIssue(issue);

        // Link author if present and not already set
        if (dto.author() != null && comment.getAuthor() == null) {
            User author = findOrCreateUser(dto.author(), providerId);
            if (author != null) {
                comment.setAuthor(author);
                changedFields.add("author");
            }
        }

        IssueComment saved = commentRepository.save(comment);

        // Publish domain events with DTOs (safe for async handlers)
        if (isNew) {
            eventPublisher.publishEvent(new ScmDomainEvent.CommentCreated(
                    ScmEventPayload.CommentData.from(saved), issueId, EventContext.from(context)));
            log.debug("Created comment: commentId={}, issueId={}", saved.getId(), issueId);
        } else if (!changedFields.isEmpty()) {
            eventPublisher.publishEvent(new ScmDomainEvent.CommentUpdated(
                    ScmEventPayload.CommentData.from(saved), issueId, changedFields, EventContext.from(context)));
            log.debug("Updated comment: commentId={}, changedFields={}", saved.getId(), changedFields);
        }

        return saved;
    }

    /**
     * Delete an issue comment by ID.
     * Publishes a Deleted domain event.
     * <p>
     * IMPORTANT: For bidirectional @OneToMany with orphanRemoval=true,
     * we must remove the comment from the parent Issue's collection before deleting
     * to avoid TransientObjectException when Hibernate flushes the persistence context.
     *
     * @param commentId the ID of the comment to delete
     * @param context processing context
     */
    @Transactional
    public void delete(@Nullable Long commentId, ProcessingContext context) {
        if (commentId == null) {
            return;
        }

        commentRepository
                .findByNativeIdAndProviderId(commentId, Objects.requireNonNull(context.providerId()))
                .ifPresent(comment -> {
                    Long issueId =
                            comment.getIssue() != null ? comment.getIssue().getId() : null;

                    // CRITICAL: Remove from parent Issue's collection BEFORE deleting
                    // to prevent TransientObjectException with orphanRemoval=true
                    Issue parentIssue = comment.getIssue();
                    if (parentIssue != null) {
                        parentIssue.getComments().remove(comment);
                    }

                    commentRepository.delete(comment);
                    eventPublisher.publishEvent(
                            new ScmDomainEvent.CommentDeleted(commentId, issueId, EventContext.from(context)));
                    log.info("Deleted comment: commentId={}", commentId);
                });
    }

    // Private Helper Methods

    /**
     * Creates a minimal Issue or PullRequest entity from webhook data, with retry on conflict.
     * <p>
     * This method handles the race condition where multiple threads might try to create
     * the same parent entity concurrently. The database has a unique constraint on
     * (repository_id, issue_type, number), so if another thread wins the race, our insert will fail
     * with a constraint violation. In that case, we simply look up the entity that was
     * created by the other thread.
     *
     * @param issueDto the issue DTO from the webhook payload
     * @param context the processing context with repository information
     * @return the created or found Issue/PullRequest entity, or null if creation failed
     */
    @Nullable
    private Issue createMinimalParentEntityWithRetry(GitHubIssueDTO issueDto, ProcessingContext context) {
        Repository repository = Objects.requireNonNull(context.repository());
        try {
            return createMinimalParentEntity(issueDto, context);
        } catch (DataIntegrityViolationException e) {
            // Another thread created the entity first (unique constraint violation)
            // This is expected behavior in concurrent webhook processing
            log.debug(
                    "Concurrent parent creation detected, looking up existing entity: repoId={}, issueNumber={}",
                    repository.getId(),
                    issueDto.number());
            return findParent(repository, issueDto.number(), context);
        }
    }

    /**
     * The stored parent of a comment on {@code number}. GitHub numbers issues and pull requests in one space
     * per repository, so a stored pull request of this provider is the parent even where an issue row with the
     * same number also exists; that issue row is the parent only without one. The issue row is not changed.
     */
    private @Nullable Issue findParent(Repository repository, int number, ProcessingContext context) {
        Optional<PullRequest> pullRequest = pullRequestRepository
                .findByRepositoryIdAndNumber(repository.getId(), number)
                .filter(pr -> Objects.equals(pr.getProvider().getId(), context.providerId()));
        if (repository.getProvider().getType() == IdentityProviderType.GITHUB && pullRequest.isPresent()) {
            return pullRequest.get();
        }
        return issueRepository
                .findByRepositoryIdAndNumber(repository.getId(), number)
                .filter(issue -> Objects.equals(issue.getProvider().getId(), context.providerId()))
                .orElseGet(() -> pullRequest.orElse(null));
    }

    /** A bounded parent repair: the last comment id it covered and how many comments it moved. */
    public record ParentRepair(long lastId, int moved) {}

    /** The highest comment id {@link #moveToCanonicalParents} may move in this pass. */
    @Transactional(readOnly = true)
    public long canonicalParentCeiling(long repositoryId) {
        return commentRepository.findCanonicalParentCeiling(repositoryId);
    }

    /**
     * Moves one chunk of this repository's comments, ids in {@code (after, ceiling]}, from an issue row a stored
     * pull request contradicts to that pull request. Its own transaction, and a bulk update that clears the
     * persistence context, keep it apart from any managed parent and its orphan-removing comment collection.
     * Comment ids, content, authors, times and the source rows stay; no comment event is published, as only
     * the parent moved.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ParentRepair moveToCanonicalParents(long repositoryId, long after, long ceiling, int limit) {
        List<Long> ids = commentRepository.findCanonicalParentChunk(repositoryId, after, ceiling, limit);
        if (ids.isEmpty()) {
            return new ParentRepair(ceiling, 0);
        }
        long last = ids.getLast();
        return new ParentRepair(last, commentRepository.moveToCanonicalPullRequest(repositoryId, ids));
    }

    /**
     * Creates a minimal Issue or PullRequest entity from webhook data.
     * <p>
     * This method is called when a comment webhook arrives before the parent entity exists.
     * We create a "stub" entity with the data available in the webhook payload. The full
     * entity will be populated later by the proper issue/PR webhook or GraphQL sync.
     * <p>
     * <b>Important:</b> We must determine whether to create an Issue or PullRequest based
     * on the {@code issueDto.isPullRequest()} flag. GitHub numbers issues and pull requests in one
     * space per repository, so if we create an Issue when it should be a PR, the later PR webhook
     * will fail to update it (different entity type). The discriminator column prevents this.
     * <p>
     * <b>Note:</b> This method may throw {@link DataIntegrityViolationException} if another
     * thread creates the entity concurrently. Use {@link #createMinimalParentEntityWithRetry}
     * for proper handling.
     *
     * @param issueDto the issue DTO from the webhook payload
     * @param context the processing context with repository information
     * @return the created Issue/PullRequest entity, or null if creation failed
     * @throws DataIntegrityViolationException if the entity was created concurrently
     */
    @Nullable
    private Issue createMinimalParentEntity(GitHubIssueDTO issueDto, ProcessingContext context) {
        Repository repository = context.repository();
        if (repository == null) {
            log.warn("Cannot create parent entity: reason=noRepository, issueId={}", issueDto.getDatabaseId());
            return null;
        }

        Long entityId = issueDto.getDatabaseId();
        if (entityId == null) {
            return null;
        }

        // Determine if this is a PR or Issue based on the webhook payload
        if (issueDto.isPullRequest()) {
            return createMinimalPullRequest(issueDto, repository, context);
        } else {
            return createMinimalIssue(issueDto, repository, context);
        }
    }

    /**
     * Creates a minimal Issue entity from webhook data.
     * <p>
     * <b>Hydration Strategy:</b> Stubs rely on natural hydration through:
     * <ol>
     *   <li>The issues webhook (typically arrives within seconds)</li>
     *   <li>The scheduled GraphQL sync (safety net for missed webhooks)</li>
     * </ol>
     */
    private Issue createMinimalIssue(GitHubIssueDTO dto, Repository repository, ProcessingContext context) {
        Issue issue = new Issue();
        populateBaseIssueFields(issue, dto, repository, context);
        Issue saved = issueRepository.save(issue);
        log.info(
                "Created stub Issue from comment webhook (will be hydrated by issue webhook or sync): issueId={}, issueNumber={}, repoName={}",
                saved.getId(),
                saved.getNumber(),
                repository.getNameWithOwner());
        return saved;
    }

    /**
     * Creates a minimal PullRequest entity from webhook data.
     * <p>
     * Note: The issue_comment webhook for PRs contains limited PR-specific data,
     * but enough to create a valid entity. PR-specific fields like additions,
     * deletions, mergedAt, etc. will be populated by the pull_request webhook
     * or GraphQL sync.
     * <p>
     * The comment payload describes the pull request as an issue resource, so the stub's native id is
     * that issue id, not the pull request's own id. It is provisional: the next full pull request
     * payload replaces it with the pull request's id and keeps the stub's internal id and comments.
     * <p>
     * <b>Hydration Strategy:</b> Stubs are intentionally NOT eagerly fetched via API.
     * Instead, they rely on natural hydration through:
     * <ol>
     *   <li>The pull_request webhook (typically arrives within seconds)</li>
     *   <li>The scheduled GraphQL sync (safety net for missed webhooks)</li>
     * </ol>
     * This avoids complexity (async tasks, rate limit management, scope resolution)
     * for marginal benefit since PR webhooks usually follow comment webhooks quickly.
     */
    private PullRequest createMinimalPullRequest(GitHubIssueDTO dto, Repository repository, ProcessingContext context) {
        PullRequest pr = new PullRequest();
        populateBaseIssueFields(pr, dto, repository, context);

        // PR-specific fields from webhook (limited data available)
        // Most PR fields will be set to defaults and updated later
        pr.setDraft(false);
        pr.setMerged(false);
        pr.setAdditions(0);
        pr.setDeletions(0);
        pr.setChangedFiles(0);
        pr.setCommits(0);

        PullRequest saved = pullRequestRepository.save(pr);
        log.info(
                "Created stub PullRequest from comment webhook (will be hydrated by PR webhook or sync): prId={}, prNumber={}, repoName={}",
                saved.getId(),
                saved.getNumber(),
                repository.getNameWithOwner());
        return saved;
    }

    /**
     * Populates base Issue fields common to both Issue and PullRequest entities.
     */
    private void populateBaseIssueFields(
            Issue issue, GitHubIssueDTO dto, Repository repository, ProcessingContext context) {
        issue.setNativeId(Objects.requireNonNull(dto.getDatabaseId()));
        issue.setProvider(Objects.requireNonNull(context.provider()));
        issue.setNumber(dto.number());
        issue.setTitle(Objects.requireNonNullElse(sanitize(dto.title()), "Issue " + dto.number()));
        issue.setBody(sanitize(dto.body()));
        issue.setState(Objects.requireNonNull(convertState(dto.state())));
        issue.setHtmlUrl(dto.htmlUrl());
        issue.setCommentsCount(dto.commentsCount());
        issue.setLocked(dto.locked());
        issue.setCreatedAt(dto.createdAt());
        issue.setUpdatedAt(dto.updatedAt());
        issue.setClosedAt(dto.closedAt());
        issue.setRepository(repository);
        issue.setLastSyncAt(Instant.now());

        // Author
        if (dto.author() != null) {
            User author = findOrCreateUser(dto.author(), Objects.requireNonNull(context.providerId()));
            issue.setAuthor(author);
        }

        // Assignees
        if (dto.assignees() != null) {
            for (GitHubUserDTO assigneeDto : dto.assignees()) {
                User assignee = findOrCreateUser(assigneeDto, Objects.requireNonNull(context.providerId()));
                if (assignee != null) {
                    issue.getAssignees().add(assignee);
                }
            }
        }

        // Labels
        if (dto.labels() != null) {
            for (GitHubLabelDTO labelDto : dto.labels()) {
                Label label = findOrCreateLabel(labelDto, repository);
                if (label != null) {
                    issue.getLabels().add(label);
                }
            }
        }

        // Milestone
        if (dto.milestone() != null) {
            Milestone milestone = findOrCreateMilestone(dto.milestone(), repository);
            issue.setMilestone(milestone);
        }
    }

    /**
     * Converts a GitHub API state string to Issue.State enum.
     */
    private Issue.@Nullable State convertState(@Nullable String state) {
        if (state == null) {
            log.warn(
                    "Issue state is null when creating stub from comment webhook, defaulting to OPEN. This may indicate missing data in webhook payload.");
            return Issue.State.OPEN;
        }
        return switch (state.toUpperCase(Locale.ROOT)) {
            case "OPEN" -> Issue.State.OPEN;
            case "CLOSED" -> Issue.State.CLOSED;
            default -> {
                log.warn("Unknown issue state '{}' when creating stub from comment webhook, defaulting to OPEN", state);
                yield Issue.State.OPEN;
            }
        };
    }
}
